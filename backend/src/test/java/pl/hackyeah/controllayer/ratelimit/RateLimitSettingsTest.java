package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import pl.hackyeah.controllayer.policy.PolicyProperties;

class RateLimitSettingsTest {
    @Test
    void missingAndPartialConfigurationProtectsEveryRole() {
        var missing = new PolicyProperties(Map.of());
        assertEquals("block", missing.rateLimit().mode());
        assertEquals(30, missing.rateLimit().requestsPerMinute());
        assertEquals(5, missing.rateLimit().burstCapacity());
        assertEquals(2, missing.rateLimit().maxConcurrentPerUser());
        assertEquals(8, missing.rateLimit().maxConcurrentGlobal());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "policy.roles.chat.models[0]", "test-model",
                "policy.roles.chat.rate-limit.requests-per-minute", "60")));
        var bound = binder.bind("policy", Bindable.of(PolicyProperties.class)).get();
        var effective = bound.rateLimit().forRole(bound.roles().get("chat").rateLimit());
        assertEquals("block", effective.mode());
        assertEquals(60, effective.requestsPerMinute());
        assertEquals(5, effective.burstCapacity());
        assertEquals(2, effective.maxConcurrentPerUser());
        assertEquals(8, effective.maxConcurrentGlobal());
    }

    @Test
    void invalidGlobalAndRoleValuesFailBinding() {
        for (var values : java.util.List.of(
                Map.of("policy.rate-limit.mode", "redact"),
                Map.of("policy.rate-limit.requests-per-minute", "0"),
                Map.of("policy.roles.chat.rate-limit.burst-capacity", "-1"),
                Map.of("policy.rate-limit.pipeline-timeout", "0s"))) {
            assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                    () -> new Binder(new MapConfigurationPropertySource(values))
                            .bind("policy", Bindable.of(PolicyProperties.class)));
        }
    }

    @Test
    void offRequiresExplicitConfiguration() {
        assertEquals("off", new RateLimitSettings("off", null, null, null, null, null).mode());
        assertEquals("monitor", new RateLimitSettings("monitor", null, null, null, null, null).mode());
    }
}
