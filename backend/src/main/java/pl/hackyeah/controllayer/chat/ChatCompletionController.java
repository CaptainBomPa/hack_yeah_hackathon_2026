package pl.hackyeah.controllayer.chat;

import java.time.Duration;
import java.util.ArrayList;
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
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardChainResult;
import pl.hackyeah.controllayer.guard.GuardChainResult.Action;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publiczny endpoint gatewaya (VISION.md §7). Pipeline: allowlista modeli → guardy INPUT →
 * wywołanie chronionego modelu → guardy OUTPUT (VISION.md §9, kroki 1-4; guardy to beany
 * `Guard` spięte w `GuardChain`). Celowo nie jest to deklaratywny route Spring Cloud Gateway:
 * wybór providera zależy od treści body, a odpowiedź trzeba przekształcić do własnego kontraktu,
 * co w czystym WebFlux-kontrolerze jest prostsze i mniej ryzykowne niż ręczne przepisywanie
 * URI/body na poziomie filtrów Gateway.
 */
@RestController
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);
    private static final String MODEL_ALLOWLIST_POLICY = "model.allowlist";

    private final ModelCatalog modelCatalog;
    private final OllamaChatClient upstreamClient;
    private final GuardChain guardChain;

    public ChatCompletionController(
            ModelCatalog modelCatalog, OllamaChatClient upstreamClient, GuardChain guardChain) {
        this.modelCatalog = modelCatalog;
        this.upstreamClient = upstreamClient;
        this.guardChain = guardChain;
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
        var trace = new ArrayList<ControlTrace>();
        trace.add(new ControlTrace(MODEL_ALLOWLIST_POLICY, "deterministic", "allow", elapsedMillis(startedAt), null));

        return Mono.fromCallable(() -> guardInput(requestId, request.messages()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(input -> {
                    trace.addAll(input.trace());
                    if (input.action() == Action.BLOCK) {
                        log.info("requestId={} model={} action=block blockedBy={}",
                                requestId, request.model(), input.blockedBy());
                        return Mono.just(ResponseEntity.status(403)
                                .body(GuardedChatResponse.block(requestId, input.blockedBy(), trace)));
                    }
                    var guardedRequest = new ChatCompletionRequest(request.model(), input.messages());
                    return upstreamClient
                            .complete(model.baseUrl(), guardedRequest, timeout)
                            .flatMap(response -> Mono.fromCallable(
                                            () -> buildResponse(requestId, input.action(), response, trace))
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .doOnNext(entity -> log.info("requestId={} model={} action={} latencyMs={}",
                                    requestId, request.model(), entity.getBody().action(), elapsedMillis(startedAt)));
                })
                .onErrorResume(UpstreamModelException.class, error -> {
                    log.warn("requestId={} model={} action=block reason=upstream-error", requestId, request.model(), error);
                    trace.add(new ControlTrace(
                            "upstream.availability",
                            "deterministic",
                            "block",
                            elapsedMillis(startedAt),
                            error.getMessage()));
                    return Mono.just(ResponseEntity.status(502)
                            .body(GuardedChatResponse.block(requestId, "upstream-error", trace)));
                });
    }

    /** Guardy INPUT dla każdej wiadomości (historia też może zawierać dane wrażliwe). */
    private InputCheck guardInput(String requestId, List<ChatMessage> messages) {
        var guarded = new ArrayList<ChatMessage>();
        var trace = new ArrayList<ControlTrace>();
        var action = Action.ALLOW;
        for (ChatMessage message : messages) {
            GuardChainResult result =
                    guardChain.run(Stage.INPUT, new GuardContext(requestId, message.content(), null, null));
            trace.addAll(result.trace());
            if (result.blocked()) {
                return new InputCheck(Action.BLOCK, result.blockedBy(), messages, trace);
            }
            if (result.action() == Action.REDACT) {
                action = Action.REDACT;
            }
            guarded.add(new ChatMessage(message.role(), result.text()));
        }
        return new InputCheck(action, null, guarded, trace);
    }

    /** Guardy OUTPUT na odpowiedzi modelu i złożenie końcowej odpowiedzi gatewaya. */
    private ResponseEntity<GuardedChatResponse> buildResponse(
            String requestId, Action inputAction, OpenAiChatCompletionResponse response, List<ControlTrace> trace) {
        ChatMessage reply = extractMessage(response);
        GuardChainResult output =
                guardChain.run(Stage.OUTPUT, new GuardContext(requestId, reply.content(), null, null));
        trace.addAll(output.trace());
        if (output.blocked()) {
            return ResponseEntity.status(403).body(GuardedChatResponse.block(requestId, output.blockedBy(), trace));
        }

        var message = new ChatMessage(reply.role(), output.text());
        Usage usage = extractUsage(response);
        boolean redacted = inputAction == Action.REDACT || output.action() == Action.REDACT;
        return ResponseEntity.ok(redacted
                ? GuardedChatResponse.redact(requestId, message, usage, trace)
                : GuardedChatResponse.allow(requestId, message, usage, trace));
    }

    private record InputCheck(Action action, String blockedBy, List<ChatMessage> messages, List<ControlTrace> trace) {}

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
