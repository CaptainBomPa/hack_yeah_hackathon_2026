package pl.hackyeah.controllayer.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.budget.BudgetGate.BudgetCheck;
import pl.hackyeah.controllayer.ratelimit.RateLimitGate;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.ratelimit.RateLimitGate.Permit;
import reactor.core.publisher.Mono;

/** Admission and resource lifecycle for chat; the supplied operation runs only when admitted. */
@Service
public class ChatExecutionGate {
    private static final Logger log = LoggerFactory.getLogger(ChatExecutionGate.class);
    private final RateLimitGate rateLimitGate;
    private final BudgetGate budgetGate;

    public ChatExecutionGate(RateLimitGate rateLimitGate, BudgetGate budgetGate) {
        this.rateLimitGate = rateLimitGate;
        this.budgetGate = budgetGate;
    }

    public Mono<ResponseEntity<GuardedChatResponse>> execute(String login, String role, String requestId,
            long startedAt, List<ChatMessage> messages, List<ControlTrace> trace,
            Function<Execution, Mono<ResponseEntity<GuardedChatResponse>>> operation) {
        return execute(rateLimitGate.currentPolicy(), login, role, requestId, startedAt, messages, trace, operation);
    }

    public Mono<ResponseEntity<GuardedChatResponse>> execute(ActivePolicy snapshot, String login, String role, String requestId,
            long startedAt, List<ChatMessage> messages, List<ControlTrace> trace,
            Function<Execution, Mono<ResponseEntity<GuardedChatResponse>>> operation) {
        return Mono.defer(() -> {
            // ControlTrace.latencyMs to czas własny kontroli, więc admisja i rezerwacja budżetu
            // mierzą się same, a nie od startu żądania (inaczej suma po trace zawyża koszt kontroli).
            long admissionStartedAt = System.nanoTime();
            var acquisition = rateLimitGate.acquire(snapshot, login, role, requestId).cache();
            return Mono.usingWhen(Mono.just(acquisition),
                    pending -> pending.flatMap(permit -> executeAdmitted(snapshot, login, role, requestId,
                            startedAt, admissionStartedAt, messages, trace, permit, operation)),
                    this::releaseAcquisition, (pending, error) -> releaseAcquisition(pending), this::releaseAcquisition);
        });
    }

    private Mono<ResponseEntity<GuardedChatResponse>> executeAdmitted(ActivePolicy snapshot, String login, String role, String requestId,
            long startedAt, long admissionStartedAt, List<ChatMessage> messages, List<ControlTrace> trace, Permit permit,
            Function<Execution, Mono<ResponseEntity<GuardedChatResponse>>> operation) {
        var decision = permit.decision();
        String policy = decision.reason() == null ? "rate.requests" : decision.reason();
        trace.add(new ControlTrace(policy, "deterministic", permit.traceAction(),
                elapsedMillis(admissionStartedAt), decision.reason()));
        if (!decision.allowed()) {
            return Mono.just(ResponseEntity.status("rate.store".equals(policy) ? 503 : 429)
                    .header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                    .header("Cache-Control", "no-store")
                    .body(GuardedChatResponse.block(requestId, policy, trace)));
        }
        return withBudget(snapshot, login, role, requestId, messages, trace, permit, operation)
                .timeout(permit.limits().pipelineTimeout())
                .onErrorResume(java.util.concurrent.TimeoutException.class, error -> {
                    var timedOut = new ArrayList<>(trace);
                    // Deadline pipeline'u: jego "czas własny" to czas, po którym się poddaliśmy,
                    // czyli liczony od startu żądania — tak samo jak pipeline.availability.
                    timedOut.add(new ControlTrace("rate.pipeline-timeout", "deterministic", "block",
                            elapsedMillis(startedAt), "pipeline deadline exceeded"));
                    return Mono.just(ResponseEntity.status(503).header("Retry-After", "1")
                            .body(GuardedChatResponse.block(requestId, "rate.pipeline-timeout", timedOut)));
                });
    }

    private Mono<Void> releaseAcquisition(Mono<Permit> acquisition) {
        return acquisition.flatMap(rateLimitGate::release).then();
    }

    private Mono<ResponseEntity<GuardedChatResponse>> withBudget(ActivePolicy snapshot, String login, String role, String requestId,
            List<ChatMessage> messages, List<ControlTrace> trace, Permit permit,
            Function<Execution, Mono<ResponseEntity<GuardedChatResponse>>> operation) {
        // Own the lifecycle before starting the database reservation. Cancellation cleanup
        // awaits the same acquisition, including a result delivered after cancellation.
        long budgetStartedAt = System.nanoTime();
        return Mono.usingWhen(Mono.fromSupplier(() -> new Execution(snapshot, role, messages, permit)),
                execution -> execution.acquisition.flatMap(budget -> {
                    trace.add(budget.toTrace(elapsedMillis(budgetStartedAt)));
                    if (!budget.allowed()) {
                        log.info("requestId={} caller={} role={} action=block blockedBy={}",
                                requestId, login, role, budget.blockedBy());
                        BudgetUsage usage = budget.isDailyCapExceeded() ? budget.toUsage(budget.dailyLimit()) : null;
                        return Mono.just(ResponseEntity.status(budget.httpStatus())
                                .body(GuardedChatResponse.block(requestId, budget.blockedBy(), trace, usage)));
                    }
                    return operation.apply(execution);
                }), Execution::cleanup, (execution, error) -> execution.cleanup(), Execution::cleanup);
    }

    /** Per-request state: known usage wins, otherwise uncertain upstreams charge the reservation. */
    public final class Execution {
        private final String role;
        private final Mono<BudgetCheck> acquisition;
        private final Permit permit;
        private volatile BudgetCheck check;
        private volatile long actualTokens = -1;
        private Mono<Long> settlement;

        private Execution(ActivePolicy snapshot, String role, List<ChatMessage> messages, Permit permit) {
            this.role = role;
            this.permit = permit;
            // cache keeps the blocking reservation alive and shares its result with cleanup.
            this.acquisition = Mono.defer(() -> budgetGate.check(snapshot, role, messages))
                    .doOnNext(budget -> check = budget).cache();
        }

        public BudgetCheck budget() { return check; }

        public void upstreamStarted() { permit.upstreamStarted(); }

        public void upstreamFinished(long tokens) {
            actualTokens = tokens;
            permit.upstreamFinished();
        }

        /** Cached reconciliation continues through cancellation and cannot be applied twice. */
        public synchronized Mono<Long> reconcile(long tokens) {
            if (settlement == null) settlement = budgetGate.reconcile(role, check, tokens).cache();
            return settlement;
        }

        private Mono<Void> cleanup() {
            return acquisition
                    // Preserve an acquisition failure; there is no known reservation to settle.
                    .onErrorResume(error -> Mono.empty())
                    .flatMap(budget -> reconcile(tokensToCharge(budget))).then();
        }

        private long tokensToCharge(BudgetCheck budget) {
            if (actualTokens >= 0) return actualTokens;
            if (permit.upstreamIsUncertain()) return budget.reservedTokens();
            return 0;
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
