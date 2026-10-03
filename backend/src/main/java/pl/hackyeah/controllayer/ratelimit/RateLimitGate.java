package pl.hackyeah.controllayer.ratelimit;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.policy.PolicyProperties;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.PolicySource;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class RateLimitGate {
    private static final Logger log = LoggerFactory.getLogger(RateLimitGate.class);
    private static final Duration STORE_TIMEOUT = Duration.ofSeconds(4);
    private final PolicySource policySource;
    private final RateLimitStore store;
    private final MeterRegistry meters;

    public RateLimitGate(PolicyProperties policy, RateLimitStore store, MeterRegistry meters) {
        this(PolicySource.fixed(PolicyDocument.fromConfig(policy, null, null, null)), store, meters);
    }

    @Autowired
    public RateLimitGate(PolicySource policySource, RateLimitStore store, MeterRegistry meters) {
        this.policySource = policySource;
        this.store = store;
        this.meters = meters;
    }

    public Mono<Permit> acquire(String authenticatedLogin, String role, String requestId) {
        return acquire(currentPolicy(), authenticatedLogin, role, requestId);
    }

    public ActivePolicy currentPolicy() { return policySource.current(); }

    public Mono<Permit> acquire(ActivePolicy snapshot, String authenticatedLogin, String role, String requestId) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            var rolePolicy = snapshot.document().roles().get(role);
            var limits = snapshot.document().rateLimit().forRole(rolePolicy == null ? null : rolePolicy.rateLimit());
            UUID id = UUID.fromString(requestId);
            if ("off".equals(limits.mode())) {
                return Mono.just(new Permit(id, limits, new RateLimitStore.Decision(true, null, 0, false)));
            }
            var acquisition = Mono.fromCallable(() -> new Permit(id, limits,
                            store.acquireForLogin(authenticatedLogin, id, limits)))
                    .subscribeOn(Schedulers.boundedElastic()).cache();
            // Own acquisition before it starts. A timed-out or cancelled database call
            // may still commit; await its result and release the late lease.
            return Mono.usingWhen(Mono.just(acquisition), pending -> pending,
                            pending -> Mono.empty(), (pending, error) -> Mono.empty(),
                            pending -> pending.flatMap(this::release).onErrorComplete())
                    .timeout(STORE_TIMEOUT)
                    .onErrorResume(error -> {
                        log.warn("Rate-limit store unavailable", error);
                        return Mono.just(new Permit(id, limits, new RateLimitStore.Decision(false, "rate.store", 1, false)));
                    })
                    .doOnNext(permit -> {
                        if (permit.decision.expiredLeases() > 0) {
                            meters.counter("control.rate.leases.expired").increment(permit.decision.expiredLeases());
                        }
                        meters.counter("control.rate.admissions", "result", permit.decision.reason() == null
                                ? "allow" : permit.decision.reason(), "mode", limits.mode()).increment();
                        meters.timer("control.rate.check").record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    });
        });
    }

    public Mono<Void> release(Permit permit) {
        if (!permit.decision.leased() || permit.upstreamUncertain.get()) return Mono.empty();
        return Mono.fromRunnable(() -> store.release(permit.requestId))
                .subscribeOn(Schedulers.boundedElastic()).then()
                .timeout(STORE_TIMEOUT)
                .onErrorResume(error -> {
                    meters.counter("control.rate.release.errors").increment();
                    log.warn("Rate-limit lease release failed; awaiting expiry", error);
                    return Mono.empty();
                });
    }

    public static final class Permit {
        private final UUID requestId;
        private final RateLimitSettings limits;
        private final RateLimitStore.Decision decision;
        private final AtomicBoolean upstreamUncertain = new AtomicBoolean();

        Permit(UUID requestId, RateLimitSettings limits, RateLimitStore.Decision decision) {
            this.requestId = requestId;
            this.limits = limits;
            this.decision = decision;
        }

        public UUID requestId() { return requestId; }
        public RateLimitSettings limits() { return limits; }
        public RateLimitStore.Decision decision() { return decision; }

        public String traceAction() {
            if (!decision.allowed()) return "block";
            if ("off".equals(limits.mode())) return "off";
            return decision.reason() == null ? "allow" : "monitor";
        }

        public void upstreamStarted() { upstreamUncertain.set(true); }
        public void upstreamFinished() { upstreamUncertain.set(false); }
        public boolean upstreamIsUncertain() { return upstreamUncertain.get(); }
    }
}
