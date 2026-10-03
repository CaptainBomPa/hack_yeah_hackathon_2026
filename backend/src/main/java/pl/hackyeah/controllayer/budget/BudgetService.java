package pl.hackyeah.controllayer.budget;

import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * BUDGET-003 (rezerwacja atomowa) i BUDGET-004 (rozliczenie) z
 * docs/deterministic/14-token-budget-quotas.md §9. Jeden wiersz per (rola, doba); `subject` jest
 * dziś zawsze {@code "role:" + rola} — węziej niż pełna macierz user/agent/model/global z case
 * file'a, celowo (VISION.md: nie dodawać wymiarów, których jeszcze nikt nie potrzebuje).
 *
 * Rezerwacja/rozliczenie celowo nie używa {@code RETURNING} (różnice H2/Postgres w trybie
 * zgodności): rezerwacja to UPDATE z warunkiem w WHERE + odczyt stanu po fakcie, co jest równie
 * atomowe (PostgreSQL i H2 re-ewaluują WHERE pod blokadą wiersza), ale przenośne między profilami
 * `local` (H2) i `prod` (Postgres).
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);
    private static final String PERIOD_KIND = "day";

    private final JdbcTemplate jdbcTemplate;

    public BudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Próbuje zarezerwować {@code tokensToReserve} dla roli na dziś. {@code dailyLimit.isEmpty()}
     * (brak sekcji `budget` w policy.yaml dla tej roli) oznacza brak limitu — nie dotyka bazy.
     */
    Mono<BudgetReservation> reserve(String role, Long dailyLimit, long tokensToReserve) {
        if (dailyLimit == null) {
            return Mono.just(BudgetReservation.unlimited());
        }
        return Mono.fromCallable(() -> reserveBlocking(subjectOf(role), dailyLimit, tokensToReserve))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Zwalnia rezerwację i księguje rzeczywiste zużycie (`usage` z Ollamy jest źródłem prawdy —
     * case file §4.5). Wywoływane też na błąd upstreamu, z {@code actualTokens = 0}, żeby padnięty
     * request nie zjadał budżetu na zawsze zarezerwowanych, nigdy nierozliczonych tokenów.
     * Zwraca {@code used_tokens} po rozliczeniu — do pokazania klientowi, ile zużył dzisiaj
     * (`GuardedChatResponse.budget`).
     */
    Mono<Long> reconcile(String role, Long dailyLimit, long reservedTokens, long actualTokens) {
        if (dailyLimit == null || reservedTokens == 0) {
            return Mono.just(0L);
        }
        return Mono.fromCallable(() -> reconcileBlocking(subjectOf(role), reservedTokens, actualTokens))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private BudgetReservation reserveBlocking(String subject, long dailyLimit, long tokensToReserve) {
        LocalDate today = LocalDate.now();
        ensureRowExists(subject, today);

        int updated = jdbcTemplate.update(
                "UPDATE budget_counter SET reserved = reserved + ? "
                        + "WHERE subject = ? AND period_kind = ? AND period_start = ? "
                        + "AND used_tokens + reserved + ? <= ?",
                tokensToReserve, subject, PERIOD_KIND, today, tokensToReserve, dailyLimit);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT used_tokens, reserved FROM budget_counter "
                        + "WHERE subject = ? AND period_kind = ? AND period_start = ?",
                subject, PERIOD_KIND, today);
        long usedAfter = ((Number) row.get("used_tokens")).longValue() + ((Number) row.get("reserved")).longValue();

        if (updated == 0) {
            log.info("budget subject={} action=deny reserve={} limit={}", subject, tokensToReserve, dailyLimit);
            return BudgetReservation.denied(dailyLimit);
        }
        return new BudgetReservation(true, tokensToReserve, usedAfter, dailyLimit);
    }

    private long reconcileBlocking(String subject, long reservedTokens, long actualTokens) {
        LocalDate today = LocalDate.now();
        jdbcTemplate.update(
                "UPDATE budget_counter SET reserved = GREATEST(reserved - ?, 0), used_tokens = used_tokens + ? "
                        + "WHERE subject = ? AND period_kind = ? AND period_start = ?",
                reservedTokens, actualTokens, subject, PERIOD_KIND, today);

        Long usedTokens = jdbcTemplate.queryForObject(
                "SELECT used_tokens FROM budget_counter WHERE subject = ? AND period_kind = ? AND period_start = ?",
                Long.class, subject, PERIOD_KIND, today);
        return usedTokens == null ? actualTokens : usedTokens;
    }

    /**
     * `INSERT ... ON CONFLICT DO NOTHING` nie jest przenośne między H2 (profil `local`) a
     * Postgresem (`prod`) — H2 go nie wspiera nawet w trybie zgodności. Zwykły INSERT + złapanie
     * naruszenia unikalności jest portowe: dwa równoczesne pierwsze żądania dla tej samej roli
     * i dnia najwyżej obie spróbują wstawić ten sam wiersz, druga dostanie wyjątek, który tu
     * ignorujemy — o to, kto założył wiersz, nic dalej nie zależy.
     */
    private void ensureRowExists(String subject, LocalDate periodStart) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO budget_counter (subject, period_kind, period_start) VALUES (?, ?, ?)",
                    subject, PERIOD_KIND, periodStart);
        } catch (DataIntegrityViolationException alreadyExists) {
            // wiersz już istnieje (ta sama rola, ten sam dzień) — nic do zrobienia.
        }
    }

    private static String subjectOf(String role) {
        return "role:" + role;
    }

    /** Dzisiejsze zużycie per rola (do dashboardu). Blokujące — wołać poza event loopem. */
    public Map<String, RoleUsage> todayUsageByRole() {
        var usage = new java.util.TreeMap<String, RoleUsage>();
        jdbcTemplate.query(
                "SELECT subject, used_tokens, reserved FROM budget_counter WHERE period_kind = ? AND period_start = ?",
                rs -> {
                    String subject = rs.getString("subject");
                    if (subject.startsWith("role:")) {
                        usage.put(subject.substring("role:".length()),
                                new RoleUsage(rs.getLong("used_tokens"), rs.getLong("reserved")));
                    }
                },
                PERIOD_KIND, LocalDate.now());
        return usage;
    }

    public record RoleUsage(long usedTokens, long reservedTokens) {}
}
