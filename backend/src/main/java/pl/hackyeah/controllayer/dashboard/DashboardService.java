package pl.hackyeah.controllayer.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.audit.AuditEvent;
import pl.hackyeah.controllayer.audit.AuditEventRepository;
import pl.hackyeah.controllayer.audit.AuditService;
import pl.hackyeah.controllayer.budget.BudgetService;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.policy.PolicySource;

/**
 * Metryki dashboardu liczone z audytu (VISION.md §6). Jedno zapytanie po rekordy okna czasowego
 * i agregacja w Javie — przenośne między H2 (local) a Postgresem (prod), bez percentyli w SQL.
 * Limit {@value #MAX_EVENTS} rekordów wystarcza na demo na Pi; powyżej wynik ma `truncated=true`.
 */
@Service
public class DashboardService {

    static final int MAX_EVENTS = 50_000;
    static final List<String> ACTIONS = List.of("allow", "monitor", "redact", "require_approval", "block");
    private static final Set<String> REACHED_MODEL = Set.of("allow", "monitor", "redact");
    private static final int TOP = 10;

    /** Okno → długość i szerokość słupka na osi czasu. */
    enum Window {
        HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(5)),
        DAY("24h", Duration.ofHours(24), Duration.ofHours(1)),
        WEEK("7d", Duration.ofDays(7), Duration.ofHours(6));

        final String id;
        final Duration length;
        final Duration bucket;

        Window(String id, Duration length, Duration bucket) {
            this.id = id;
            this.length = length;
            this.bucket = bucket;
        }

        static Window of(String id) {
            for (Window w : values()) {
                if (w.id.equals(id)) {
                    return w;
                }
            }
            return HOUR;
        }
    }

    private final AuditEventRepository repository;
    private final AuditService auditService;
    private final BudgetService budgetService;
    private final PolicySource policy;
    private final Clock clock;

    @Autowired
    public DashboardService(AuditEventRepository repository, AuditService auditService,
            BudgetService budgetService, PolicySource policy) {
        this(repository, auditService, budgetService, policy, Clock.systemUTC());
    }

    DashboardService(AuditEventRepository repository, AuditService auditService, BudgetService budgetService,
            PolicySource policy, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.budgetService = budgetService;
        this.policy = policy;
        this.clock = clock;
    }

    /** Blokujące (JPA/JDBC) — wołać poza event loopem WebFlux. */
    public DashboardView compute(String windowId) {
        Window window = Window.of(windowId);
        Instant to = clock.instant();
        // Początek wyrównany do słupka, żeby słupki nie „pływały” między odświeżeniami.
        long bucketMs = window.bucket.toMillis();
        Instant from = Instant.ofEpochMilli(((to.minus(window.length).toEpochMilli()) / bucketMs + 1) * bucketMs);

        List<AuditEvent> events = repository.findByOccurredAtGreaterThanEqual(
                from, PageRequest.of(0, MAX_EVENTS, Sort.by(Sort.Direction.DESC, "seq")));
        boolean truncated = events.size() >= MAX_EVENTS;

        Map<String, Long> byAction = zeroByAction();
        long errors = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        List<Long> latencies = new ArrayList<>();
        Map<Long, Map<String, Long>> buckets = new TreeMap<>();
        Map<String, Long> controlHits = new HashMap<>();
        Map<String, long[]> models = new HashMap<>();
        Map<String, long[]> principals = new HashMap<>();
        Map<String, String> principalRoles = new HashMap<>();

        for (long start = from.toEpochMilli(); start < to.toEpochMilli(); start += bucketMs) {
            buckets.put(start, zeroByAction());
        }

        for (AuditEvent e : events) {
            String action = e.getAction();
            byAction.merge(action, 1L, Long::sum);
            if (e.getHttpStatus() >= 500) {
                errors++;
            }
            long tokens = nz(e.getPromptTokens()) + nz(e.getCompletionTokens());
            promptTokens += nz(e.getPromptTokens());
            completionTokens += nz(e.getCompletionTokens());
            if (REACHED_MODEL.contains(action)) {
                latencies.add(e.getLatencyMs());
            }
            long bucketStart = (e.getOccurredAt().toEpochMilli() / bucketMs) * bucketMs;
            buckets.computeIfAbsent(bucketStart, k -> zeroByAction()).merge(action, 1L, Long::sum);

            for (ControlTrace t : auditService.traceOf(e)) {
                // `off` to informacja o kontroli wyłączonej w polityce, nie jej trafienie —
                // wliczone podbijałoby "top controls" tym, co w ogóle się nie wykonało.
                if (ControlTrace.isHit(t.action())) {
                    controlHits.merge(t.policy() + "\u0000" + t.action(), 1L, Long::sum);
                }
            }

            boolean blocked = "block".equals(action);
            if (e.getModel() != null) {
                add(models.computeIfAbsent(e.getModel(), k -> new long[3]), blocked, tokens);
            }
            if (e.getPrincipal() != null) {
                add(principals.computeIfAbsent(e.getPrincipal(), k -> new long[3]), blocked, tokens);
                principalRoles.putIfAbsent(e.getPrincipal(), e.getRole());
            }
        }

        return new DashboardView(
                window.id,
                from,
                to,
                truncated,
                new DashboardView.Totals(events.size(), byAction, errors),
                latency(latencies),
                new DashboardView.Tokens(promptTokens, completionTokens),
                buckets.entrySet().stream()
                        .map(b -> new DashboardView.Bucket(Instant.ofEpochMilli(b.getKey()), b.getValue()))
                        .toList(),
                controlHits.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(TOP)
                        .map(h -> {
                            String[] key = h.getKey().split("\u0000", 2);
                            return new DashboardView.ControlHits(key[0], key[1], h.getValue());
                        })
                        .toList(),
                models.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, long[]> m) -> m.getValue()[0]).reversed())
                        .limit(TOP)
                        .map(m -> new DashboardView.ModelStats(m.getKey(), m.getValue()[0], m.getValue()[1], m.getValue()[2]))
                        .toList(),
                principals.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, long[]> p) -> p.getValue()[0]).reversed())
                        .limit(TOP)
                        .map(p -> new DashboardView.PrincipalStats(p.getKey(), principalRoles.get(p.getKey()),
                                p.getValue()[0], p.getValue()[1], p.getValue()[2]))
                        .toList(),
                budgets());
    }

    /** Role z aktywnej polityki (z limitem albo bez) + role, które dziś coś zużyły. */
    private List<DashboardView.RoleBudget> budgets() {
        var usage = budgetService.todayUsageByRole();
        var roles = new TreeMap<String, Long>();
        policy.current().document().roles().forEach((role, rolePolicy) -> roles.put(role, rolePolicy.dailyTokens()));
        usage.keySet().forEach(role -> roles.putIfAbsent(role, null));
        return roles.entrySet().stream()
                .map(r -> {
                    var used = usage.get(r.getKey());
                    return new DashboardView.RoleBudget(r.getKey(),
                            used == null ? 0 : used.usedTokens(),
                            used == null ? 0 : used.reservedTokens(),
                            r.getValue());
                })
                .toList();
    }

    private static DashboardView.Latency latency(List<Long> values) {
        if (values.isEmpty()) {
            return new DashboardView.Latency(null, null, null, 0);
        }
        List<Long> sorted = values.stream().sorted().toList();
        return new DashboardView.Latency(percentile(sorted, 50), percentile(sorted, 95), sorted.getLast(), sorted.size());
    }

    /** Percentyl metodą nearest-rank. */
    static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank - 1, 0));
    }

    private static Map<String, Long> zeroByAction() {
        var map = new LinkedHashMap<String, Long>();
        ACTIONS.forEach(a -> map.put(a, 0L));
        return map;
    }

    private static void add(long[] stats, boolean blocked, long tokens) {
        stats[0]++;
        if (blocked) {
            stats[1]++;
        }
        stats[2] += tokens;
    }

    private static long nz(Integer value) {
        return value == null ? 0 : value;
    }
}
