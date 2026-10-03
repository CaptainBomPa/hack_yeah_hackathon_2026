package pl.hackyeah.controllayer.audit;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * API audytu dla dashboardu (`/api/**` wymaga roli ADMIN — SecurityConfig). Zapytania JPA są
 * blokujące, więc każde idzie na `boundedElastic`, poza event loopem WebFlux (VISION.md §2).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_EXPORT = 10_000;

    private final AuditEventRepository repository;
    private final AuditService auditService;
    private final JsonMapper json;

    public AuditController(AuditEventRepository repository, AuditService auditService, JsonMapper json) {
        this.repository = repository;
        this.auditService = auditService;
        this.json = json;
    }

    @GetMapping("/events")
    public Mono<AuditEventView.Page> events(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String blockedBy,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        var query = new AuditQuery(action, principal, model, blockedBy, sessionId, from, to, before);
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        return blocking(() -> {
            var page = repository.findAll(query.toSpecification(),
                    PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "seq")));
            List<AuditEventView> items = page.getContent().stream().map(this::view).toList();
            Long next = page.hasNext() && !items.isEmpty() ? items.getLast().seq() : null;
            return new AuditEventView.Page(items, next);
        });
    }

    @GetMapping("/events/{requestId}")
    public Mono<ResponseEntity<AuditEventView>> event(@PathVariable String requestId) {
        return blocking(() -> auditService.find(requestId)
                .map(e -> ResponseEntity.ok(view(e)))
                .orElse(ResponseEntity.notFound().build()));
    }

    @GetMapping("/verify")
    public Mono<AuditService.VerifyResult> verify() {
        return blocking(auditService::verify);
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<String>> export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String blockedBy,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        boolean asJson = "json".equals(format.toLowerCase(Locale.ROOT));
        var query = new AuditQuery(action, principal, model, blockedBy, sessionId, from, to, null);
        return blocking(() -> {
            List<AuditEventView> events = repository.findAll(query.toSpecification(),
                            PageRequest.of(0, MAX_EXPORT, Sort.by(Sort.Direction.ASC, "seq")))
                    .getContent().stream().map(this::view).toList();
            String body = asJson ? json.writeValueAsString(events) : toCsv(events);
            String filename = "audit-" + Instant.now().toString().replace(':', '-') + (asJson ? ".json" : ".csv");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .contentType(asJson ? MediaType.APPLICATION_JSON : new MediaType("text", "csv"))
                    .body(body);
        });
    }

    private AuditEventView view(AuditEvent event) {
        return AuditEventView.of(event, auditService.traceOf(event));
    }

    private String toCsv(List<AuditEventView> events) {
        var header = Stream.of("seq", "timestamp", "requestId", "principal", "role", "sessionId", "model", "action",
                "blockedBy", "httpStatus", "latencyMs", "promptTokens", "completionTokens", "controls", "recordHash");
        var lines = Stream.concat(
                Stream.of(header.collect(Collectors.joining(","))),
                events.stream().map(e -> Stream.of(
                                String.valueOf(e.seq()), e.timestamp().toString(), e.requestId(), e.principal(), e.role(),
                                e.sessionId(), e.model(), e.action(), e.blockedBy(), String.valueOf(e.httpStatus()),
                                String.valueOf(e.latencyMs()),
                                e.usage() == null ? null : String.valueOf(e.usage().promptTokens()),
                                e.usage() == null ? null : String.valueOf(e.usage().completionTokens()),
                                e.trace().stream().map(t -> t.policy() + "=" + t.action()).collect(Collectors.joining(";")),
                                e.recordHash())
                        .map(AuditController::csvCell)
                        .collect(Collectors.joining(","))));
        return lines.collect(Collectors.joining("\r\n")) + "\r\n";
    }

    /** Cytowanie RFC 4180 + neutralizacja formula injection w arkuszach (AUDIT-005). */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String cell = value;
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
