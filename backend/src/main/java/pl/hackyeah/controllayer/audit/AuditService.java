package pl.hackyeah.controllayer.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.chat.ControlTrace;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit log z łańcuchem HMAC (AUDIT-001, -003, -005). Każdy rekord zawiera
 * `prev_hash` poprzednika i `record_hash = HMAC(key, prev_hash + kanoniczny rekord)`, więc
 * edycję, usunięcie albo wstawienie rekordu wykrywa `verify()`.
 *
 * Zapis jest serializowany (`synchronized`): łańcuch wymaga jednego pisarza. Wystarcza to dla
 * jednej instancji gatewaya (Pi); przy wielu instancjach łańcuch musiałby być per instancja.
 */
@Service
public class AuditService implements AuditLog {

    static final String GENESIS_HASH = "0".repeat(64);
    private static final TypeReference<List<ControlTrace>> TRACE_LIST = new TypeReference<>() {};
    private static final int VERIFY_PAGE_SIZE = 500;

    private final AuditEventRepository repository;
    private final AuditProperties properties;
    private final JsonMapper json;
    private final SecretKeySpec chainKey;

    public AuditService(AuditEventRepository repository, AuditProperties properties, JsonMapper json) {
        this.repository = repository;
        this.properties = properties;
        this.json = json;
        this.chainKey = new SecretKeySpec(properties.chainKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public synchronized void append(AuditEntry entry) {
        // Ostatni rekord zawsze z bazy, nie z pamięci: inny proces/kontekst mógł dopisać w międzyczasie.
        // Gdyby dwóch pisarzy wzięło ten sam seq, insert drugiego padnie na kluczu (AuditEvent.isNew),
        // zamiast nadpisać rekord — a kontroler potraktuje to jak awarię audytu (fail-closed).
        var last = repository.findTopByOrderBySeqDesc();
        long seq = last.map(AuditEvent::getSeq).orElse(0L) + 1;
        String prevHash = last.map(AuditEvent::getRecordHash).orElse(GENESIS_HASH);

        AuditEntry clean = sanitized(entry);
        String controls = json.writeValueAsString(clean.trace() == null ? List.of() : clean.trace());
        String hash = hash(prevHash, seq, clean, controls);
        repository.saveAndFlush(new AuditEvent(seq, clean, controls, prevHash, hash));
    }

    public List<ControlTrace> traceOf(AuditEvent event) {
        return json.readValue(event.getControls(), TRACE_LIST);
    }

    public Optional<AuditEvent> find(String requestId) {
        return repository.findByRequestId(requestId);
    }

    /** Przechodzi cały łańcuch od początku i zwraca pierwszy uszkodzony rekord, jeśli jest. */
    public VerifyResult verify() {
        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 1;
        long checked = 0;
        int page = 0;
        while (true) {
            var batch = repository.findAll(
                    PageRequest.of(page++, VERIFY_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "seq")));
            for (AuditEvent event : batch) {
                boolean linked = event.getSeq() == expectedSeq && event.getPrevHash().equals(expectedPrev);
                boolean intact = hash(event.getPrevHash(), event.getSeq(), event.toEntry(), event.getControls())
                        .equals(event.getRecordHash());
                if (!linked || !intact) {
                    return new VerifyResult(false, checked, event.getSeq(), linked ? "record modified" : "chain broken");
                }
                expectedPrev = event.getRecordHash();
                expectedSeq++;
                checked++;
            }
            if (!batch.hasNext()) {
                return new VerifyResult(true, checked, null, null);
            }
        }
    }

    public record VerifyResult(boolean valid, long checked, Long brokenAtSeq, String reason) {}

    private AuditEntry sanitized(AuditEntry e) {
        int max = properties.maxFieldLength();
        List<ControlTrace> trace = e.trace() == null ? null : e.trace().stream()
                .map(t -> new ControlTrace(AuditSanitizer.sanitize(t.policy(), max), t.kind(), t.action(),
                        t.latencyMs(), AuditSanitizer.sanitize(t.detail(), max)))
                .toList();
        // Milisekundy: tyle bez straty przechowa każda baza, a hash liczony jest z epoch millis.
        return new AuditEntry(e.requestId(), e.occurredAt().truncatedTo(ChronoUnit.MILLIS),
                AuditSanitizer.sanitize(e.principal(), 100),
                e.role(), AuditSanitizer.sanitize(e.sessionId(), 100), AuditSanitizer.sanitize(e.model(), 200),
                e.action(), AuditSanitizer.sanitize(e.blockedBy(), 100), e.httpStatus(), e.latencyMs(),
                e.promptTokens(), e.completionTokens(), e.messageCount(), trace);
    }

    /** Kanoniczna postać rekordu: stała kolejność pól, JSON bez spacji. */
    private String hash(String prevHash, long seq, AuditEntry e, String controls) {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("seq", seq);
        canonical.put("requestId", e.requestId());
        canonical.put("occurredAt", e.occurredAt().toEpochMilli());
        canonical.put("principal", e.principal());
        canonical.put("role", e.role());
        canonical.put("sessionId", e.sessionId());
        canonical.put("model", e.model());
        canonical.put("action", e.action());
        canonical.put("blockedBy", e.blockedBy());
        canonical.put("httpStatus", e.httpStatus());
        canonical.put("latencyMs", e.latencyMs());
        canonical.put("promptTokens", e.promptTokens());
        canonical.put("completionTokens", e.completionTokens());
        canonical.put("messageCount", e.messageCount());
        canonical.put("controls", controls);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(chainKey);
            mac.update(prevHash.getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 unavailable", error);
        }
    }
}
