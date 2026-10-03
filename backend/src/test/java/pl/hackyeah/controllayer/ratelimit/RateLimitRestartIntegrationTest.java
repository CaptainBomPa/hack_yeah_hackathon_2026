package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import pl.hackyeah.controllayer.ControlLayerApplication;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Three full application lifecycles on the same persistent H2; no external services. */
class RateLimitRestartIntegrationTest {
    @TempDir Path directory;

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ControlLayerApplication.class, RateLimitTestDatabase.class)
                .profiles("rate-test").run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("rates").toString().replace('\\', '/')
                                + ";MODE=PostgreSQL;LOCK_TIMEOUT=2000",
                        "--spring.main.banner-mode=off");
    }

    @Test
    void persistedLeaseExpiresAfterRestartAndBucketIsNotReset() {
        UUID user;
        String login = "restart-" + UUID.randomUUID();
        String otherLogin = "other-" + UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();
        var limits = new RateLimitSettings("block", 10, 2, 1, 1, null);
        try (var first = start()) {
            var users = first.getBean(AppUserRepository.class);
            var hash = first.getBean(PasswordEncoder.class).encode("test-password");
            user = users.save(new AppUser(login, hash, "chat")).getId();
            users.save(new AppUser(otherLogin, hash, "chat"));
            assertTrue(first.getBean(RateLimitStore.class).acquireForLogin(login, abandoned, limits).allowed());
            // Simulate a crash after durable admission, without running request cleanup.
        }
        try (var second = start()) {
            var store = second.getBean(RateLimitStore.class);
            var jdbc = second.getBean(JdbcTemplate.class);
            assertEquals("rate.concurrent.user", store.acquireForLogin(login, UUID.randomUUID(), limits).reason());
            assertEquals("rate.concurrent.global", store.acquireForLogin(otherLogin, UUID.randomUUID(), limits).reason());
            jdbc.update("UPDATE rate_limit_lease SET expires_at = ? WHERE request_id = ?", Timestamp.from(Instant.EPOCH), abandoned);
            UUID next = UUID.randomUUID();
            assertTrue(store.acquireForLogin(login, next, limits).allowed());
            store.release(abandoned);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease WHERE request_id = ?", Integer.class, next));
            store.release(next);
            // Exhaust the persisted bucket explicitly; restart must not restore a full burst.
            jdbc.update("UPDATE rate_limit_bucket SET tokens = 0, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", user);
        }
        try (var third = start()) {
            assertEquals("rate.requests", third.getBean(RateLimitStore.class)
                    .acquireForLogin(login, UUID.randomUUID(), new RateLimitSettings(null, 1, null, null, null, null)).reason());
        }
    }
}
