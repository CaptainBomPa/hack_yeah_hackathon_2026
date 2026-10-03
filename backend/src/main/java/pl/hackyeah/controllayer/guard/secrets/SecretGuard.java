package pl.hackyeah.controllayer.guard.secrets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.guard.Verdict;

/**
 * SEC-GITLEAKS (docs/deterministic/02-secret-detection.md): wykrywanie sekretów regułami z paczki
 * Gitleaks (MIT, {@code rules/gitleaks/}). Algorytm skanu: {@link SecretScanner}.
 *
 * <p>Domyślnie redaguje sam sekret placeholderem {@code [REDACTED:<rule-id>]}; reguły z
 * {@code blockRules} (domyślnie {@code private-key}) blokują. Parametry w
 * {@code control-layer.guards.rules.SEC-GITLEAKS.params} (id reguł Gitleaks, lista albo napis z przecinkami):
 * <ul>
 *   <li>{@code disabledRules} – reguły pomijane,</li>
 *   <li>{@code blockRules} – trafienie blokuje zamiast redagować,</li>
 *   <li>{@code monitorRules} – trafienie tylko raportowane w trace, tekst bez zmian.</li>
 * </ul>
 */
@Component
public class SecretGuard implements Guard {

    static final String ID = "SEC-GITLEAKS";
    static final String PACK = "/rules/gitleaks/gitleaks.toml";
    private static final Set<String> DEFAULT_BLOCK_RULES = Set.of("private-key");
    private static final Logger log = LoggerFactory.getLogger(SecretGuard.class);

    private final SecretScanner scanner;

    public SecretGuard() {
        this(loadDefaultPack());
    }

    SecretGuard(SecretScanner scanner) {
        this.scanner = scanner;
    }

    static SecretScanner loadDefaultPack() {
        try (InputStream in = SecretGuard.class.getResourceAsStream(PACK)) {
            if (in == null) {
                throw new IllegalStateException("Brak paczki reguł na classpath: " + PACK);
            }
            var pack = GitleaksRulePack.load(in);
            var scanner = new SecretScanner(pack.rules(), pack.globalAllowlist());
            log.info("secret rules loaded pack={} rules={} skipped={} automatonStates={}",
                    PACK, pack.rules().size(), pack.skipped(), scanner.automatonStates());
            return scanner;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Stage> stages() {
        return Set.of(Stage.INPUT, Stage.OUTPUT, Stage.TOOL_CALL);
    }

    @Override
    public Verdict check(GuardContext ctx, GuardSettings settings) {
        String text = ctx.text();
        List<SecretFinding> findings = scanner.scan(text, settings.stringSetParam("disabledRules", Set.of()));
        if (findings.isEmpty()) {
            return Verdict.allow();
        }
        Set<String> blockRules = settings.stringSetParam("blockRules", DEFAULT_BLOCK_RULES);
        Set<String> monitorRules = settings.stringSetParam("monitorRules", Set.of());
        var blocked = findings.stream().filter(f -> blockRules.contains(f.ruleId())).toList();
        if (!blocked.isEmpty()) {
            return new Verdict.Block("secret " + summary(blocked));
        }
        var monitored = findings.stream().filter(f -> monitorRules.contains(f.ruleId())).toList();
        var redacted = findings.stream().filter(f -> !monitorRules.contains(f.ruleId())).toList();
        String monitorDetail = monitored.isEmpty() ? null : "monitor " + summary(monitored);
        if (redacted.isEmpty()) {
            return Verdict.allow(monitorDetail);
        }
        return new Verdict.Redact(redact(text, redacted),
                summary(redacted) + (monitorDetail == null ? "" : "; " + monitorDetail));
    }

    /** Np. {@code github-pat×1, generic-api-key×2} — tylko id reguł i liczności, bez wartości. */
    static String summary(List<SecretFinding> findings) {
        var counts = new TreeMap<String, Integer>();
        findings.forEach(f -> counts.merge(f.ruleId(), 1, Integer::sum));
        return counts.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** Podmienia zakresy sekretów (posortowane po starcie; nakładające się są scalane). */
    static String redact(String text, List<SecretFinding> findings) {
        var out = new StringBuilder(text.length());
        int cursor = 0;
        int i = 0;
        while (i < findings.size()) {
            SecretFinding first = findings.get(i);
            int start = Math.max(first.start(), cursor);
            int end = first.end();
            i++;
            while (i < findings.size() && findings.get(i).start() < end) {
                end = Math.max(end, findings.get(i).end());
                i++;
            }
            if (end <= start) {
                continue;
            }
            out.append(text, cursor, start).append("[REDACTED:").append(first.ruleId()).append(']');
            cursor = end;
        }
        return out.append(text, cursor, text.length()).toString();
    }
}
