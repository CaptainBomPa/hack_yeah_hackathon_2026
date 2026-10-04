package pl.hackyeah.controllayer.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import tools.jackson.databind.json.JsonMapper;

/** Real security chain and native upstream: gateway login must not consume Codex OAuth. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "policy.roles.agent.models[0]=gpt-5.5", "control-layer.guards.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:codex-auth-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
@ActiveProfiles("local")
class CodexAuthIntegrationTest {
    private static final List<com.sun.net.httpserver.Headers> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer UPSTREAM = upstream();
    @LocalServerPort int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("CODEX_UPSTREAM_BASE_URL", () -> "http://localhost:" + UPSTREAM.getAddress().getPort() + "/backend-api/codex");
    }

    @AfterAll static void stop() { UPSTREAM.stop(0); }

    @Test void separateGatewayCredentialsAreRequiredAndOAuthRemainsRequestScoped() throws Exception {
        String login = "agent-" + UUID.randomUUID();
        users.save(new AppUser(login, encoder.encode("gateway-password"), "agent"));
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        try (var fixtures = getClass().getResourceAsStream("/integration/codex-auth-cases.json")) {
            for (var scenario : JsonMapper.builder().build().readTree(fixtures)) {
                int before = RECEIVED.size();
                var request = client.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", scenario.path("oauth").asText("Bearer subscription-one"))
                        .header("ChatGPT-Account-ID", "account-test");
                if (scenario.has("password")) {
                    String basic = "Basic " + Base64.getEncoder().encodeToString(
                            (login + ":" + scenario.path("password").asText()).getBytes(StandardCharsets.UTF_8));
                    request = request.header(CodexHeaders.GATEWAY_AUTH, basic);
                }
                request.bodyValue("{\"model\":\"gpt-5.5\",\"input\":\"hello\"}").exchange()
                        .expectStatus().isEqualTo(scenario.path("status").asInt());
                assertEquals(before + scenario.path("calls").asInt(), RECEIVED.size(), scenario.path("name").asText());
                if (scenario.path("calls").asInt() == 1) {
                    var forwarded = RECEIVED.getLast();
                    assertEquals(scenario.path("oauth").asText("Bearer subscription-one"), forwarded.getFirst("Authorization"));
                    assertEquals("account-test", forwarded.getFirst("ChatGPT-Account-ID"));
                    assertNull(forwarded.getFirst(CodexHeaders.GATEWAY_AUTH));
                }
            }
        }
    }

    @Test void nativeModelDiscoveryRequiresBothCredentialsAndFiltersThePolicyAllowlist() {
        String login = "agent-" + UUID.randomUUID();
        users.save(new AppUser(login, encoder.encode("gateway-password"), "agent"));
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri("/v1/models").header("Authorization", "Bearer subscription-models")
                .header("ChatGPT-Account-ID", "account-test").exchange().expectStatus().isUnauthorized();
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (login + ":gateway-password").getBytes(StandardCharsets.UTF_8));
        client.get().uri("/v1/models?client_version=0.155.0").header(CodexHeaders.GATEWAY_AUTH, basic)
                .header("Authorization", "Bearer subscription-models").header("ChatGPT-Account-ID", "account-test")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.models.length()").isEqualTo(1)
                .jsonPath("$.models[0].slug").isEqualTo("gpt-5.5")
                .jsonPath("$.models[0].display_name").isEqualTo("Native metadata retained");
        assertEquals("Bearer subscription-models", RECEIVED.getLast().getFirst("Authorization"));
        assertNull(RECEIVED.getLast().getFirst(CodexHeaders.GATEWAY_AUTH));
    }

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/backend-api/codex/models", exchange -> {
                RECEIVED.add(exchange.getRequestHeaders());
                byte[] body = ("{\"models\":[{\"slug\":\"gpt-5.5\",\"display_name\":\"Native metadata retained\"},"
                        + "{\"slug\":\"forbidden-model\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.createContext("/backend-api/codex/responses", exchange -> {
                RECEIVED.add(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"output\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
