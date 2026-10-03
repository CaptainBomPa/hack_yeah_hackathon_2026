package pl.hackyeah.controllayer.budget;

import java.util.List;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.chat.BudgetUsage;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.policy.PolicyProperties;
import reactor.core.publisher.Mono;

/**
 * Fasada budżetu dla {@code ChatCompletionController} (analogicznie do {@code ModelAccessPolicy}
 * dla allowlisty modeli). Łączy limit wejścia (BUDGET-002), dzienny cap per rola z `policy.yaml`
 * (BUDGET-003) i clamp wyjścia (BUDGET-001) w jedno wywołanie przed GuardChain INPUT — tanie
 * kontrole parametrów idą przed droższym sprawdzeniem w Postgresie
 * (docs/deterministic/how-to-write-a-rule.md §9: "canonicalize/cheap checks first").
 */
@Service
public class BudgetGate {

    static final String INPUT_LIMIT_POLICY = "budget.input_limit";
    static final String DAILY_CAP_POLICY = "budget.daily_cap";
    static final String SOFT_CAP_POLICY = "budget.soft_cap";

    private final PolicyProperties policy;
    private final BudgetLimitsProperties limits;
    private final BudgetService budgetService;

    public BudgetGate(PolicyProperties policy, BudgetLimitsProperties limits, BudgetService budgetService) {
        this.policy = policy;
        this.limits = limits;
        this.budgetService = budgetService;
    }

    public Mono<BudgetCheck> check(String role, List<ChatMessage> messages) {
        long inputEstimate = TokenEstimator.estimateMessages(messages);
        if (inputEstimate > limits.maxInputTokens()) {
            return Mono.just(BudgetCheck.inputTooLarge(inputEstimate, limits.maxInputTokens()));
        }

        int maxOutputTokens = limits.maxOutputTokens();
        Long dailyLimit = dailyLimitOf(role);
        long reserve = inputEstimate + maxOutputTokens;

        return budgetService.reserve(role, dailyLimit, reserve).map(reservation -> {
            if (!reservation.allowed()) {
                return BudgetCheck.dailyCapExceeded(reservation.dailyLimit());
            }
            return BudgetCheck.allowed(maxOutputTokens, reserve, reservation.overSoftCap(), reservation.dailyLimit());
        });
    }

    /**
     * Zwalnia rezerwację i księguje rzeczywiste zużycie; wołane też na błąd upstreamu z
     * {@code actualTokens = 0}. Zwraca zużycie dzienne po rozliczeniu (0, gdy rola bez limitu
     * albo nic nie było zarezerwowane) — do {@link BudgetCheck#toUsage}.
     */
    public Mono<Long> reconcile(String role, BudgetCheck check, long actualTokens) {
        if (!check.allowed() || check.reservedTokens() == 0) {
            return Mono.just(0L);
        }
        return budgetService.reconcile(role, dailyLimitOf(role), check.reservedTokens(), actualTokens);
    }

    private Long dailyLimitOf(String role) {
        var rolePolicy = policy.roles().get(role);
        return rolePolicy == null || rolePolicy.budget() == null ? null : rolePolicy.budget().dailyTokens();
    }

    /** Wynik kontroli budżetowej — albo odmowa (z gotowym `blockedBy`/`detail`), albo zgoda z parametrami do rezerwacji/clampu. */
    public record BudgetCheck(
            boolean allowed,
            String blockedBy,
            String detail,
            int maxOutputTokens,
            long reservedTokens,
            boolean softCapWarning,
            long dailyLimit) {

        static BudgetCheck inputTooLarge(long estimate, int max) {
            return new BudgetCheck(false, INPUT_LIMIT_POLICY,
                    "estimated input tokens " + estimate + " exceed max " + max, 0, 0, false, 0);
        }

        static BudgetCheck dailyCapExceeded(long dailyLimit) {
            return new BudgetCheck(false, DAILY_CAP_POLICY,
                    "daily token budget exhausted", 0, 0, false, dailyLimit);
        }

        static BudgetCheck allowed(int maxOutputTokens, long reservedTokens, boolean softCapWarning, long dailyLimit) {
            return new BudgetCheck(true, null, null, maxOutputTokens, reservedTokens, softCapWarning, dailyLimit);
        }

        /** `dailyLimit <= 0` oznacza rolę bez limitu (patrz {@link BudgetGate#dailyLimitOf}) — `cap: null`. */
        public BudgetUsage toUsage(long used) {
            return new BudgetUsage(used, dailyLimit <= 0 ? null : dailyLimit);
        }

        public boolean isDailyCapExceeded() {
            return DAILY_CAP_POLICY.equals(blockedBy);
        }

        /** 413 dla zbyt dużego wejścia (rozmiar), 429 dla wyczerpanego budżetu (jak rate limit). */
        public int httpStatus() {
            return INPUT_LIMIT_POLICY.equals(blockedBy) ? 413 : 429;
        }

        /** Wpis do `trace`: ALLOW (z ewentualnym ostrzeżeniem soft-cap) albo BLOCK z powodem. */
        public ControlTrace toTrace(long latencyMs) {
            if (!allowed) {
                return new ControlTrace(blockedBy, "deterministic", "block", latencyMs, detail);
            }
            String softCapDetail = softCapWarning ? "soft cap reached (>=80% of daily budget)" : null;
            return new ControlTrace(
                    softCapWarning ? SOFT_CAP_POLICY : DAILY_CAP_POLICY,
                    "deterministic",
                    "allow",
                    latencyMs,
                    softCapDetail);
        }
    }
}
