package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
        properties = {"spring.datasource.url=jdbc:h2:mem:rate-upstream-failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "policy.rate-limit.burst-capacity=5", "policy.roles.chat.budget.daily-tokens=20000"})
@ActiveProfiles("rate-test")
@Import(RateLimitTestDatabase.class)
class RateLimitUpstreamFailureIntegrationTest {
    private static final int providerPort = freePort();
    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry properties) {
        properties.add("control-layer.models[0].tag", () -> "test-model");
        properties.add("control-layer.models[0].enabled", () -> "true");
        properties.add("control-layer.models[0].base-url", () -> "http://localhost:" + providerPort);
    }
    @LocalServerPort int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    private HttpServer upstream;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private AppUser user;
    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM rate_limit_lease");
        jdbc.update("DELETE FROM rate_limit_bucket");
        jdbc.update("DELETE FROM budget_counter");
        user = users.save(new AppUser("failure-" + UUID.randomUUID(), passwords.encode("test-password"), "chat"));
        upstream = HttpServer.create(new InetSocketAddress("localhost", providerPort), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
    }
    @AfterEach
    void close() { upstream.stop(0); }
    @Test
    void refusedConnectionReleasesSlotAndDoesNotChargeUnsentTokens() {
        upstream.stop(0);
        assertRepeatedFailuresReleaseSlots();
        assertEquals(0L, jdbc.queryForObject("SELECT used_tokens FROM budget_counter WHERE subject = 'role:chat'", Long.class));
    }
    @Test
    void completedHttpErrorReleasesSlot() {
        responseStatus = 503;
        assertRepeatedFailuresReleaseSlots();
    }
    @Test
    void completedMalformedResponseReleasesSlot() {
        assertRepeatedFailuresReleaseSlots();
    }
    private void assertRepeatedFailuresReleaseSlots() {
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        for (int i = 0; i < 3; i++) {
            client.post().uri("/v1/chat/completions")
                    .headers(headers -> headers.setBasicAuth(user.getLogin(), "test-password"))
                    .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hello"))))
                    .exchange().expectStatus().isEqualTo(502).expectBody()
                    .jsonPath("$.blockedBy").isEqualTo("upstream-error");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
            assertEquals(0L, jdbc.queryForObject("SELECT reserved FROM budget_counter WHERE subject = 'role:chat'", Long.class));
        }
    }
}

