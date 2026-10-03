package pl.hackyeah.controllayer.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.hackyeah.controllayer.auth.AppUserRepository;

/** All instances serialize short admissions on one database row through JPA. */
@Service
public class RateLimitStore {
    private static final Duration FULL_BUCKET_RETENTION = Duration.ofDays(1);
    private final AppUserRepository users;
    private final RateLimitLockRepository locks;
    private final RateLimitBucketRepository buckets;
    private final RateLimitLeaseRepository leases;
    private final RateLimitDatabaseSupport database;

    public RateLimitStore(AppUserRepository users, RateLimitLockRepository locks,
            RateLimitBucketRepository buckets, RateLimitLeaseRepository leases, RateLimitDatabaseSupport database) {
        this.users = users;
        this.locks = locks;
        this.buckets = buckets;
        this.leases = leases;
        this.database = database;
    }

    @Transactional(timeout = RateLimitDatabaseSupport.TRANSACTION_TIMEOUT_SECONDS)
    public Decision acquireForLogin(String authenticatedLogin, UUID requestId, RateLimitSettings limits) {
        UUID userId = users.findByLogin(authenticatedLogin)
                .orElseThrow(() -> new IllegalStateException("Missing authenticated account identity")).getId();
        return acquireInTransaction(userId, requestId, limits);
    }

    private Decision acquireInTransaction(UUID userId, UUID requestId, RateLimitSettings limits) {
        Instant now = lockAndTime();
        int expired = purgeAt(now);
        var existing = buckets.findById(userId);
        double tokens = existing.isEmpty() ? limits.burstCapacity()
                : Math.min(limits.burstCapacity(), existing.get().tokens()
                    + Math.max(0, Duration.between(existing.get().updatedAt(), now).toMillis() / 1000.0)
                    * limits.requestsPerMinute() / 60.0);
        String reason = null;
        long retry = 1;
        if (tokens < 1) {
            reason = "rate.requests";
            retry = Math.max(1, (long) Math.ceil((1 - tokens) * 60 / limits.requestsPerMinute()));
        } else if (leases.countByUserId(userId) >= limits.maxConcurrentPerUser()) {
            reason = "rate.concurrent.user";
        } else if (leases.count() >= limits.maxConcurrentGlobal()) {
            reason = "rate.concurrent.global";
        }
        if (reason != null && "block".equals(limits.mode())) return new Decision(false, reason, retry, false, expired);
        double remaining = Math.max(0, tokens - 1);
        Instant fullAt = now.plusSeconds((long) Math.ceil(
                (limits.burstCapacity() - remaining) * 60.0 / limits.requestsPerMinute()));
        var bucket = existing.orElseGet(() -> new RateLimitBucket(userId));
        bucket.consume(remaining, now, fullAt);
        buckets.save(bucket);
        leases.save(new RateLimitLease(requestId, userId, now, now.plus(limits.leaseDuration())));
        return new Decision(true, reason, retry, true, expired);
    }

    @Transactional(timeout = RateLimitDatabaseSupport.TRANSACTION_TIMEOUT_SECONDS)
    public void release(UUID requestId) {
        leases.release(requestId);
    }

    @Transactional(timeout = RateLimitDatabaseSupport.TRANSACTION_TIMEOUT_SECONDS)
    public int purgeExpired() {
        return purgeAt(lockAndTime());
    }

    private Instant lockAndTime() {
        database.configureLockTimeout();
        locks.lockGlobal().orElseThrow(() -> new IllegalStateException("Missing rate-limit global lock"));
        return database.now();
    }

    private int purgeAt(Instant now) {
        int expired = leases.deleteExpired(now);
        buckets.deleteFullBefore(now.minus(FULL_BUCKET_RETENTION));
        return expired;
    }

    public record Decision(boolean allowed, String reason, long retryAfterSeconds, boolean leased, int expiredLeases) {
        public Decision(boolean allowed, String reason, long retryAfterSeconds, boolean leased) {
            this(allowed, reason, retryAfterSeconds, leased, 0);
        }
    }
}
