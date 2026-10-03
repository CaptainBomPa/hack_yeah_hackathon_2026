package pl.hackyeah.controllayer.ratelimit;

import java.time.Duration;
import java.util.Set;

/** Missing values inherit protection; zero never means unlimited. */
public record RateLimitSettings(String mode, Integer requestsPerMinute, Integer burstCapacity,
        Integer maxConcurrentPerUser, Integer maxConcurrentGlobal, Duration pipelineTimeout) {
    private static final Duration DEFAULT_PIPELINE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration MAX_PIPELINE_TIMEOUT = Duration.ofHours(1);
    private static final Duration LEASE_GRACE_PERIOD = Duration.ofSeconds(10);
    public RateLimitSettings {
        mode = mode == null ? "block" : mode;
        requestsPerMinute = requestsPerMinute == null ? 30 : requestsPerMinute;
        burstCapacity = burstCapacity == null ? 5 : burstCapacity;
        maxConcurrentPerUser = maxConcurrentPerUser == null ? 2 : maxConcurrentPerUser;
        maxConcurrentGlobal = maxConcurrentGlobal == null ? 8 : maxConcurrentGlobal;
        pipelineTimeout = pipelineTimeout == null ? DEFAULT_PIPELINE_TIMEOUT : pipelineTimeout;
        if (!Set.of("block", "monitor", "off").contains(mode)
                || requestsPerMinute <= 0 || burstCapacity <= 0
                || maxConcurrentPerUser <= 0 || maxConcurrentGlobal <= 0
                || pipelineTimeout.isNegative() || pipelineTimeout.isZero()
                || pipelineTimeout.compareTo(MAX_PIPELINE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Invalid policy.rateLimit: positive limits and block/monitor/off required; timeout <= 1h");
        }
    }

    public static RateLimitSettings defaults() {
        return new RateLimitSettings(null, null, null, null, null, null);
    }

    public RateLimitSettings forRole(RoleOverride role) {
        if (role == null) return this;
        return new RateLimitSettings(role.mode() == null ? mode : role.mode(),
                role.requestsPerMinute() == null ? requestsPerMinute : role.requestsPerMinute(),
                role.burstCapacity() == null ? burstCapacity : role.burstCapacity(),
                role.maxConcurrentPerUser() == null ? maxConcurrentPerUser : role.maxConcurrentPerUser(),
                maxConcurrentGlobal, pipelineTimeout);
    }

    public Duration leaseDuration() {
        return pipelineTimeout.plus(LEASE_GRACE_PERIOD);
    }

    public record RoleOverride(String mode, Integer requestsPerMinute, Integer burstCapacity,
            Integer maxConcurrentPerUser) {}
}
