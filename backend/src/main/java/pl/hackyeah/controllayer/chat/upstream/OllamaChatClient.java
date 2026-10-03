package pl.hackyeah.controllayer.chat.upstream;

import java.time.Duration;
import java.net.ConnectException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
                .switchIfEmpty(Mono.error(new UpstreamModelException("Model provider returned an empty response")))
                .map(OllamaChatClient::validateResponse)
                .timeout(timeout)
                .onErrorMap(
                        error -> !(error instanceof UpstreamModelException),
                        error -> classifyFailure(baseUrl, error));
    }

    private static UpstreamModelException classifyFailure(String baseUrl, Throwable error) {
        boolean requestNotSent = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                requestNotSent = true;
                break;
            }
        }
        // A completed HTTP error or a failure to connect cannot leave a generation running.
        // Timeouts and interrupted responses remain uncertain until the lease expires.
        boolean mayStillBeRunning = !requestNotSent && !(error instanceof WebClientResponseException);
        return new UpstreamModelException(
                "Model provider at " + baseUrl + " did not respond in time or failed", error,
                mayStillBeRunning, requestNotSent);
    }

    private static OpenAiChatCompletionResponse validateResponse(OpenAiChatCompletionResponse response) {
        if (response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst() == null
                || response.choices().getFirst().message() == null
                || response.choices().getFirst().message().content() == null) {
            throw new UpstreamModelException("Model provider returned no assistant message");
        }
        var usage = response.usage();
        if (usage != null && ((usage.promptTokens() != null && usage.promptTokens() < 0)
                || (usage.completionTokens() != null && usage.completionTokens() < 0))) {
            throw new UpstreamModelException("Model provider returned invalid token usage");
        }
        return response;
    }
}
