package pl.hackyeah.controllayer.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Odpowiedź GET /api/dashboard — `DashboardData` z frontend/src/api/types.ts. Wszystko liczone
 * z audytu (`audit_event`) w oknie czasowym, budżety z `budget_counter` + `policy.yaml`.
 *
 * @param truncated true, gdy w oknie było więcej rekordów niż limit agregacji (liczby są wtedy dolną granicą)
 */
public record DashboardView(
        String window,
        Instant from,
        Instant to,
        boolean truncated,
        Totals totals,
        Latency latency,
        Tokens tokens,
        List<Bucket> timeline,
        List<ControlHits> controls,
        List<ModelStats> models,
        List<PrincipalStats> principals,
        List<RoleBudget> budgets) {

    /** `byAction` ma zawsze wszystkie akcje z VISION §4 (0, gdy brak). `errors` = HTTP 5xx. */
    public record Totals(long requests, Map<String, Long> byAction, long errors) {}

    /** Czas odpowiedzi żądań, które doszły do modelu (allow/monitor/redact); odmowy mają ~0 ms i zaniżałyby wynik. */
    public record Latency(Long p50, Long p95, Long max, long samples) {}

    public record Tokens(long prompt, long completion) {}

    public record Bucket(Instant start, Map<String, Long> byAction) {}

    /** Kontrola, która coś zrobiła (akcja inna niż allow) — z `trace`, więc liczy też redakcje bez `blockedBy`. */
    public record ControlHits(String policy, String action, long count) {}

    public record ModelStats(String model, long requests, long blocked, long tokens) {}

    public record PrincipalStats(String principal, String role, long requests, long blocked, long tokens) {}

    /** `cap: null` = rola bez dziennego limitu w policy.yaml. */
    public record RoleBudget(String role, long usedTokens, long reservedTokens, Long cap) {}
}
