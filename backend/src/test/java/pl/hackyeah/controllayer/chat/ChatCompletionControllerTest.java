package pl.hackyeah.controllayer.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;

/**
 * Testuje pełną ścieżkę /v1/chat/completions (allowlista + wywołanie providera + mapowanie
 * odpowiedzi) bez prawdziwej Ollamy — zastępuje ją minimalnym stubem HTTP zwracającym odpowiedź
 * w jej natywnym, OpenAI-compatible kształcie.
 */
class ChatCompletionControllerTest {

    private HttpServer stubUpstream;
    private WebTestClient client;

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
        String baseUrl = "http://localhost:" + stubUpstream.getAddress().getPort();

        var catalog = new ModelCatalog(new ModelCatalogProperties(
                List.of(new ModelCatalogProperties.ModelEntry("test-model", baseUrl, true)),
                Duration.ofSeconds(5)));
        var upstreamClient = new OllamaChatClient(WebClient.builder());
        var controller = new ChatCompletionController(catalog, upstreamClient);

        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ChatCompletionExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        stubUpstream.stop(0);
    }

    @Test
    void allowsAndForwardsToTheConfiguredModel() {
        client.post()
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
        client.post()
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
}
