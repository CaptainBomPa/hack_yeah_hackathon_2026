package pl.hackyeah.controllayer.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Maintenance is independent of correctness; admission itself excludes expired leases. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RateLimitMaintenance {
    private static final Logger log = LoggerFactory.getLogger(RateLimitMaintenance.class);
    private static final int CLEANUP_INTERVAL_MILLIS = 60_000;
    private final RateLimitStore store;
    private final MeterRegistry meters;

    RateLimitMaintenance(RateLimitStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS, initialDelay = CLEANUP_INTERVAL_MILLIS)
    void purge() {
        try {
            int expired = store.purgeExpired();
            if (expired > 0) meters.counter("control.rate.leases.expired").increment(expired);
        } catch (RuntimeException error) {
            meters.counter("control.rate.maintenance.errors").increment();
            log.warn("Rate-limit maintenance failed", error);
        }
    }
}
