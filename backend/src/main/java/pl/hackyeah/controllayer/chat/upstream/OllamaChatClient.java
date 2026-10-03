package pl.hackyeah.controllayer.chat.upstream;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import reactor.core.publisher.Mono;

/**
 * Woła provider modelu po jego natywnym, OpenAI-compatible endpointzie. Ollama wystawia go
 * wprost pod /v1/chat/completions, więc dla tego providera nie trzeba tłumaczyć kształtu body —
 * tylko podmienić hosta na rozwiązany z katalogu modeli (ModelCatalog) i doliczyć timeout.
 *
 * To jedyne miejsce, które zna fizyczny adres providera; gdyby doszedł kolejny OpenAI-compatible
 * provider (vLLM, llama.cpp server, inny host), wystarczy dodać wpis w katalogu modeli — ten
 * klient obsłuży go bez zmian.
 */
@Component
public class OllamaChatClient {

    private final WebClient webClient;

    public OllamaChatClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Mono<OpenAiChatCompletionResponse> complete(
            String baseUrl, ChatCompletionRequest request, Duration timeout) {
        return webClient
                .post()
                .uri(baseUrl + "/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenAiChatCompletionResponse.class)
                .timeout(timeout)
                .onErrorMap(
                        error -> !(error instanceof UpstreamModelException),
                        error -> new UpstreamModelException(
                                "Model provider at " + baseUrl + " did not respond in time or failed", error));
    }
}
