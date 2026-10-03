package pl.hackyeah.controllayer.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.chat.upstream.OpenAiChatCompletionResponse;
import pl.hackyeah.controllayer.chat.upstream.UpstreamModelException;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import reactor.core.publisher.Mono;

/**
 * Publiczny endpoint gatewaya (VISION.md §7). Dziś realizuje tylko krok 4 pipeline'u
 * ("wywołanie chronionego modelu, jeśli dozwolone") plus kontrolę allowlisty modeli — reszta
 * `trace` zapełni się, gdy powstanie decision pipeline z VISION.md §9 (kroki 1-4). Celowo nie
 * jest to deklaratywny route Spring Cloud Gateway: wybór providera zależy od treści body, a
 * odpowiedź trzeba przekształcić do własnego kontraktu, co w czystym WebFlux-kontrolerze jest
 * prostsze i mniej ryzykowne niż ręczne przepisywanie URI/body na poziomie filtrów Gateway.
 */
@RestController
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);
    private static final String MODEL_ALLOWLIST_POLICY = "model.allowlist";

    private final ModelCatalog modelCatalog;
    private final OllamaChatClient upstreamClient;

    public ChatCompletionController(ModelCatalog modelCatalog, OllamaChatClient upstreamClient) {
        this.modelCatalog = modelCatalog;
        this.upstreamClient = upstreamClient;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<GuardedChatResponse>> chatCompletions(
            @Valid @RequestBody ChatCompletionRequest request) {
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        var allowedModel = modelCatalog.resolveAllowed(request.model());
        if (allowedModel.isEmpty()) {
            log.info("requestId={} model={} action=block reason=not-allowed", requestId, request.model());
            var trace = List.of(new ControlTrace(
                    MODEL_ALLOWLIST_POLICY,
                    "deterministic",
                    "block",
                    elapsedMillis(startedAt),
                    "model not allowed: " + request.model()));
            return Mono.just(ResponseEntity.status(403)
                    .body(GuardedChatResponse.block(requestId, MODEL_ALLOWLIST_POLICY, trace)));
        }

        ModelCatalogProperties.ModelEntry model = allowedModel.get();
        Duration timeout = modelCatalog.modelTimeout();

        return upstreamClient
                .complete(model.baseUrl(), request, timeout)
                .map(response -> {
                    var trace = List.of(new ControlTrace(
                            MODEL_ALLOWLIST_POLICY, "deterministic", "allow", elapsedMillis(startedAt), null));
                    GuardedChatResponse body =
                            GuardedChatResponse.allow(requestId, extractMessage(response), extractUsage(response), trace);
                    log.info("requestId={} model={} action=allow latencyMs={}",
                            requestId, request.model(), elapsedMillis(startedAt));
                    return ResponseEntity.ok(body);
                })
                .onErrorResume(UpstreamModelException.class, error -> {
                    log.warn("requestId={} model={} action=block reason=upstream-error", requestId, request.model(), error);
                    var trace = List.of(new ControlTrace(
                            "upstream.availability",
                            "deterministic",
                            "block",
                            elapsedMillis(startedAt),
                            error.getMessage()));
                    return Mono.just(ResponseEntity.status(502)
                            .body(GuardedChatResponse.block(requestId, "upstream-error", trace)));
                });
    }

    private static ChatMessage extractMessage(OpenAiChatCompletionResponse response) {
        return response.choices().stream()
                .findFirst()
                .map(OpenAiChatCompletionResponse.Choice::message)
                .orElse(new ChatMessage("assistant", ""));
    }

    private static Usage extractUsage(OpenAiChatCompletionResponse response) {
        var usage = response.usage();
        if (usage == null) {
            return new Usage(0, 0);
        }
        return new Usage(usage.promptTokens(), usage.completionTokens());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
