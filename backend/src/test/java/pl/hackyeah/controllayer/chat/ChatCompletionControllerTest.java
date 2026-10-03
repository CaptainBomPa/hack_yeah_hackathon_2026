package pl.hackyeah.controllayer.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;
import pl.hackyeah.controllayer.audit.AuditEntry;
import pl.hackyeah.controllayer.audit.AuditLog;
import pl.hackyeah.controllayer.audit.AuditProperties;
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.budget.BudgetLimitsProperties;
import pl.hackyeah.controllayer.budget.BudgetService;
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import pl.hackyeah.controllayer.policy.PolicySource;
import pl.hackyeah.controllayer.policy.PolicyProperties;

/**
 * Testuje pełną ścieżkę /v1/chat/completions (allowlista katalogu + polityka roli + guardy +
 * wywołanie providera + mapowanie odpowiedzi) bez prawdziwej Ollamy — zastępuje ją minimalnym
 * stubem HTTP zwracającym odpowiedź w jej natywnym, OpenAI-compatible kształcie. Uwierzytelnienie
 * symuluje filtr ustawiający kontekst bezpieczeństwa, bo bindToController omija łańcuch Security.
 */
class ChatCompletionControllerTest {

    private HttpServer stubUpstream;
    private String baseUrl;
    private ModelCatalog catalog;
    private ModelCatalogProperties catalogProperties;
    private ModelAccessPolicy policy;
    private PolicyProperties policyProperties;

    @BeforeEach
    void setUp() throws IOException {
        stubUpstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubUpstream.createContext("/v1/chat/completions", exchange -> {
            String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"czesc\"}}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stubUpstream.start();
        baseUrl = "http://localhost:" + stubUpstream.getAddress().getPort();

        catalogProperties = new ModelCatalogProperties(
                List.of(new ModelCatalogProperties.ModelEntry("test-model", baseUrl, true)),
                Duration.ofSeconds(5));
        catalog = new ModelCatalog(catalogProperties);
        policyProperties = new PolicyProperties(Map.of(
                "chat", new PolicyProperties.RolePolicy(List.of("test-model"), null),
                "agent", new PolicyProperties.RolePolicy(List.of(), null)));
        policy = new ModelAccessPolicy(policyProperties);
    }

    @AfterEach
    void tearDown() {
        stubUpstream.stop(0);
    }

    @Test
    void allowsAndForwardsToTheConfiguredModelForAnAllowedRole() {
        clientAs("chat")
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.action")
                .isEqualTo("allow")
                .jsonPath("$.message.content")
                .isEqualTo("czesc")
                .jsonPath("$.usage.promptTokens")
                .isEqualTo(3)
                .jsonPath("$.usage.completionTokens")
                .isEqualTo(2);
    }

    @Test
    void blocksAModelNotInTheCatalog() {
        clientAs("chat")
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("unknown-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.action")
                .isEqualTo("block")
                .jsonPath("$.blockedBy")
                .isEqualTo("model.allowlist");
    }

    @Test
    void blocksAModelTheRoleIsNotAllowedToUse() {
        clientAs("agent")
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.action")
                .isEqualTo("block")
                .jsonPath("$.blockedBy")
                .isEqualTo("policy.model-access");
    }

    @Test
    void rejectsARequestWithoutAnAuthenticatedCaller() {
        WebTestClient.bindToController(controller())
                .controllerAdvice(new ChatCompletionExceptionHandler())
                .build()
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isEqualTo(401)
                .expectBody()
                .jsonPath("$.blockedBy")
                .isEqualTo("auth.required");
    }

    private WebTestClient clientAs(String role) {
        return WebTestClient.bindToController(controller())
                .controllerAdvice(new ChatCompletionExceptionHandler())
                .webFilter(authenticatedAs(role))
                .build();
    }

    @Test
    void auditsEveryDecisionWithoutMessageContent() {
        clientAs("chat")
                .post()
                .uri("/v1/chat/completions")
                .header("X-Session-Id", "sess-1")
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "tajne-hej"))))
                .exchange()
                .expectStatus()
                .isOk();
        clientAs("chat")
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("unknown-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isEqualTo(403);

        assertEquals(2, audited.size());
        AuditEntry allowed = audited.get(0);
        assertEquals("allow", allowed.action());
        assertEquals("tester", allowed.principal());
        assertEquals("chat", allowed.role());
        assertEquals("sess-1", allowed.sessionId());
        assertEquals(200, allowed.httpStatus());
        assertEquals(3, allowed.promptTokens());
        assertFalse(allowed.toString().contains("tajne-hej"), "treść wiadomości nie może trafić do audytu");
        AuditEntry blocked = audited.get(1);
        assertEquals("block", blocked.action());
        assertEquals("model.allowlist", blocked.blockedBy());
        assertEquals(403, blocked.httpStatus());
    }

    @Test
    void failsClosedWhenTheAuditLogIsUnavailable() {
        failingAudit = true;
        clientAs("chat")
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.action")
                .isEqualTo("block")
                .jsonPath("$.blockedBy")
                .isEqualTo("audit.write")
                .jsonPath("$.message")
                .doesNotExist();
    }

    private final List<AuditEntry> audited = new ArrayList<>();
    private boolean failingAudit;

    private ChatCompletionController controller() {
        var upstreamClient = new OllamaChatClient(WebClient.builder());
        var guardProperties = new GuardProperties(true, null);
        var guardChain = new GuardChain(List.of(), guardProperties);
        AuditLog auditLog = entry -> {
            if (failingAudit) {
                throw new IllegalStateException("database down");
            }
            audited.add(entry);
        };
        // Żadna rola w tym teście nie ma skonfigurowanego budżetu (RolePolicy.budget() == null),
        // więc BudgetGate nigdy nie dotyka JdbcTemplate — bezpiecznie można przekazać null.
        var budgetGate = new BudgetGate(
                policyProperties, new BudgetLimitsProperties(null, null), new BudgetService(null));
        // Kontroler bierze jeden snapshot polityki na żądanie — musi obejmować to samo co komponenty powyżej.
        var policySource = PolicySource.fixed(PolicyDocument.fromConfig(
                policyProperties, catalogProperties, guardProperties, new BudgetLimitsProperties(null, null)));
        return new ChatCompletionController(catalog, policy, budgetGate, upstreamClient, guardChain, auditLog,
                new AuditProperties(null, true, null), policySource);
    }

    private static WebFilter authenticatedAs(String role) {
        var authentication = new UsernamePasswordAuthenticationToken("tester", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT))));
        return (exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
