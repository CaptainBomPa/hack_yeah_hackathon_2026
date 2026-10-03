package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import pl.hackyeah.controllayer.chat.ChatMessage;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:rate-timeout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000",
                "policy.rate-limit.pipeline-timeout=1s", "policy.roles.chat.budget.daily-tokens=20000"})
@ActiveProfiles("rate-test")
@Import(RateLimitTestDatabase.class)
class RateLimitTimeoutIntegrationTest {
    private static final CountDownLatch entered = new CountDownLatch(1);
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final HttpServer upstream = upstream();

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                entered.countDown();
                try { release.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry properties) {
        properties.add("control-layer.models[0].tag", () -> "test-model");
        properties.add("control-layer.models[0].enabled", () -> "true");
        properties.add("control-layer.models[0].base-url", () -> "http://localhost:" + upstream.getAddress().getPort());
    }

    @AfterAll static void close() { release.countDown(); upstream.stop(0); }
    @LocalServerPort int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pipelineTimeoutSettlesBudgetButRetainsUncertainLeaseUntilExpiry() throws Exception {
        String password = "test-password";
        var user = users.save(new AppUser("timeout-" + UUID.randomUUID(), passwords.encode(password), "chat"));
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(8)).build();
        var body = new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hello")));
        var first = CompletableFuture.runAsync(() -> client.post().uri("/v1/chat/completions")
                .headers(headers -> headers.setBasicAuth(user.getLogin(), password)).bodyValue(body).exchange()
                .expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.blockedBy").isEqualTo("rate.pipeline-timeout"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease WHERE user_id = ?", Integer.class, user.getId()));
            assertEquals(0L, jdbc.queryForObject("SELECT reserved FROM budget_counter WHERE subject = 'role:chat'", Long.class));
            assertTrue(jdbc.queryForObject("SELECT used_tokens FROM budget_counter WHERE subject = 'role:chat'", Long.class) > 0);
            client.post().uri("/v1/chat/completions").headers(headers -> headers.setBasicAuth(user.getLogin(), password))
                    .bodyValue(body).exchange().expectStatus().isEqualTo(429).expectBody()
                    .jsonPath("$.blockedBy").isEqualTo("rate.concurrent.user");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE blocked_by = 'rate.pipeline-timeout'", Integer.class));
        } finally { release.countDown(); first.get(5, TimeUnit.SECONDS); }
    }
}
