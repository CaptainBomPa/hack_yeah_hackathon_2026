package pl.hackyeah.controllayer.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
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
    private ModelAccessPolicy policy;

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

        catalog = new ModelCatalog(new ModelCatalogProperties(
                List.of(new ModelCatalogProperties.ModelEntry("test-model", baseUrl, true)),
                Duration.ofSeconds(5)));
        policy = new ModelAccessPolicy(new PolicyProperties(Map.of(
                "chat", new PolicyProperties.RolePolicy(List.of("test-model")),
                "agent", new PolicyProperties.RolePolicy(List.of()))));
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

    private ChatCompletionController controller() {
        var upstreamClient = new OllamaChatClient(WebClient.builder());
        var guardChain = new GuardChain(List.of(), new GuardProperties(true, null));
        return new ChatCompletionController(catalog, policy, upstreamClient, guardChain);
    }

    private static WebFilter authenticatedAs(String role) {
        var authentication = new UsernamePasswordAuthenticationToken("tester", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT))));
        return (exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
