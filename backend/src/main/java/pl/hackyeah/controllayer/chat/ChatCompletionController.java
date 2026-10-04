package pl.hackyeah.controllayer.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pl.hackyeah.controllayer.audit.AuditEntry;
import pl.hackyeah.controllayer.audit.AuditLog;
import pl.hackyeah.controllayer.audit.AuditProperties;
import pl.hackyeah.controllayer.chat.ChatExecutionGate.Execution;
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.chat.upstream.OpenAiChatCompletionResponse;
import pl.hackyeah.controllayer.chat.upstream.UpstreamModelException;
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardChainResult;
import pl.hackyeah.controllayer.guard.GuardChainResult.Action;
import pl.hackyeah.controllayer.guard.ConversationGuard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.PolicySource;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
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
    private static final String MODEL_ACCESS_POLICY = "policy.model-access";
    private static final String AUTH_POLICY = "auth.required";
    private static final String AUDIT_POLICY = "audit.write";
    private static final String ROLE_PREFIX = "ROLE_";

    private final ModelCatalog modelCatalog;
    private final ModelAccessPolicy modelAccessPolicy;
    private final OllamaChatClient upstreamClient;
    private final GuardChain guardChain;
    private final ConversationGuard conversationGuard;
    private final AuditLog auditLog;
    private final AuditProperties auditProperties;
    private final PolicySource policySource;
    private final ChatExecutionGate executionGate;

    public ChatCompletionController(ModelCatalog modelCatalog, ModelAccessPolicy modelAccessPolicy,
            OllamaChatClient upstreamClient, GuardChain guardChain, AuditLog auditLog,
            AuditProperties auditProperties, ChatExecutionGate executionGate, PolicySource policySource) {
        this.modelCatalog = modelCatalog;
        this.modelAccessPolicy = modelAccessPolicy;
        this.upstreamClient = upstreamClient;
        this.guardChain = guardChain;
        this.conversationGuard = new ConversationGuard(guardChain);
        this.auditLog = auditLog;
        this.auditProperties = auditProperties;
        this.policySource = policySource;
        this.executionGate = executionGate;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<GuardedChatResponse>> chatCompletions(
            @Valid @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String requestId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        long startedAt = System.nanoTime();
        // Jeden snapshot polityki na całe żądanie: zapis nowej wersji w trakcie nie miesza dwóch wersji.
        ActivePolicy policy = policySource.current();

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(callerOf(context.getAuthentication())))
                .flatMap(caller -> Mono.defer(() -> complete(policy, request, caller, requestId, startedAt))
                        .onErrorResume(error -> {
                            log.error("requestId={} pipeline failed errorType={}", requestId, error.getClass().getSimpleName());
                            var trace = List.of(new ControlTrace("pipeline.availability", "deterministic", "block",
                                    elapsedMillis(startedAt), "chat pipeline unavailable (fail-closed)"));
                            return Mono.just(ResponseEntity.status(503)
                                    .body(GuardedChatResponse.block(requestId, "pipeline.availability", trace)));
                        })
                        .map(response -> new Outcome(caller, response)))
                .switchIfEmpty(Mono.fromSupplier(() -> new Outcome(null, unauthenticated(requestId))))
                .flatMap(outcome -> audited(policy, outcome, request, sessionId, requestId, occurredAt, startedAt));
    }

    private record Outcome(Caller caller, ResponseEntity<GuardedChatResponse> response) {}

    /**
     * Zapis decyzji do audytu przed wysłaniem odpowiedzi (AUDIT-001). Bez treści wiadomości —
     * tylko metadane i ścieżka kontroli. Gdy zapis się nie uda, a audyt jest fail-closed,
     * klient dostaje 503 zamiast odpowiedzi modelu (AUDIT-008).
     */
    private Mono<ResponseEntity<GuardedChatResponse>> audited(ActivePolicy policy, Outcome outcome,
            ChatCompletionRequest request,
            String sessionId, String requestId, Instant occurredAt, long startedAt) {
        GuardedChatResponse body = outcome.response().getBody();
        Caller caller = outcome.caller();
        var entry = new AuditEntry(
                requestId,
                occurredAt,
                caller == null ? null : caller.login(),
                caller == null ? null : caller.role(),
                sessionId,
                request.model(),
                body.action(),
                body.blockedBy(),
                outcome.response().getStatusCode().value(),
                elapsedMillis(startedAt),
                body.usage() == null ? null : body.usage().promptTokens(),
                body.usage() == null ? null : body.usage().completionTokens(),
                request.messages().size(),
                body.trace(),
                policy.version());
        var stamped = ResponseEntity.status(outcome.response().getStatusCode())
                .headers(outcome.response().getHeaders())
                .body(body.withPolicy(policy.version(), policy.hash()));
        return Mono.fromRunnable(() -> auditLog.append(entry))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(stamped)
                .onErrorResume(error -> {
                    log.error("requestId={} audit write failed failClosed={}",
                            requestId, auditProperties.failClosed(), error);
                    if (!auditProperties.failClosed()) {
                        return Mono.just(stamped);
                    }
                    var trace = new ArrayList<>(body.trace());
                    trace.add(new ControlTrace(AUDIT_POLICY, "deterministic", "block", elapsedMillis(startedAt),
                            "audit log unavailable (fail-closed)"));
                    return Mono.just(ResponseEntity.status(503)
                            .body(GuardedChatResponse.block(requestId, AUDIT_POLICY, trace)
                                    .withPolicy(policy.version(), policy.hash())));
                });
    }

    private Mono<ResponseEntity<GuardedChatResponse>> complete(ActivePolicy policy, ChatCompletionRequest request,
            Caller caller,
            String requestId, long startedAt) {
        var allowedModel = modelCatalog.resolveAllowed(policy, request.model());
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

        if (!modelAccessPolicy.allowsModel(policy, caller.role(), request.model())) {
            log.info("requestId={} caller={} role={} model={} action=block reason=policy",
                    requestId, caller.login(), caller.role(), request.model());
            var policyTrace = List.of(new ControlTrace(
                    MODEL_ACCESS_POLICY,
                    "deterministic",
                    "block",
                    elapsedMillis(startedAt),
                    "role " + caller.role() + " may not use model " + request.model()));
            return Mono.just(ResponseEntity.status(403)
                    .body(GuardedChatResponse.block(requestId, MODEL_ACCESS_POLICY, policyTrace)));
        }

        ModelCatalogProperties.ModelEntry model = allowedModel.get();
        Duration timeout = modelCatalog.modelTimeout();
        // Guards run on boundedElastic while the deadline is observed on a timer thread.
        var trace = new CopyOnWriteArrayList<ControlTrace>();
        trace.add(new ControlTrace(MODEL_ALLOWLIST_POLICY, "deterministic", "allow", elapsedMillis(startedAt), null));

        return executionGate.execute(policy, caller.login(), caller.role(), requestId, startedAt, request.messages(), trace,
                execution -> completeLimited(policy, request, requestId, startedAt, model, timeout, trace, execution));
    }

    private Mono<ResponseEntity<GuardedChatResponse>> completeLimited(ActivePolicy policy, ChatCompletionRequest request,
            String requestId, long startedAt, ModelCatalogProperties.ModelEntry model, Duration timeout,
            List<ControlTrace> trace, Execution execution) {
        var budget = execution.budget();
        return Mono.fromCallable(() -> guardInput(policy, requestId, request.messages()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(input -> {
                    trace.addAll(input.trace());
                    if (input.action() == Action.BLOCK) {
                        log.info("requestId={} model={} action=block blockedBy={}",
                                requestId, request.model(), input.blockedBy());
                        // Żądanie nigdy nie dotarło do modelu — zwalniamy rezerwację, inaczej
                        // zablokowane prompty cicho zjadałyby budżet roli (BUDGET-004).
                        return execution.reconcile(0)
                                .map(usedAfter -> ResponseEntity.status(403)
                                        .body(GuardedChatResponse.block(
                                                requestId, input.blockedBy(), trace, budget.toUsage(usedAfter))));
                    }
                    var guardedRequest = new ChatCompletionRequest(request.model(), input.messages())
                            .withMaxTokens(budget.maxOutputTokens());
                    return upstreamClient
                            .complete(model.baseUrl(), guardedRequest, timeout)
                            .doOnSubscribe(subscription -> execution.upstreamStarted())
                            .doOnNext(response -> execution.upstreamFinished(tokensToCharge(response, budget.reservedTokens())))
                            .flatMap(response -> execution.reconcile(tokensToCharge(response, budget.reservedTokens()))
                                    .flatMap(usedAfter -> Mono.fromCallable(() -> buildResponse(
                                                    policy, requestId, input, response, trace,
                                                    budget.toUsage(usedAfter)))
                                            .subscribeOn(Schedulers.boundedElastic())))
                            .doOnNext(entity -> log.info("requestId={} model={} action={} latencyMs={}",
                                    requestId, request.model(), entity.getBody().action(), elapsedMillis(startedAt)));
                })
                .onErrorResume(UpstreamModelException.class, error -> {
                    log.warn("requestId={} model={} action=block reason=upstream-error",
                            requestId, request.model(), error);
                    trace.add(new ControlTrace(
                            "upstream.availability",
                            "deterministic",
                            "block",
                            elapsedMillis(startedAt),
                            error.getMessage()));
                    long chargedTokens = error.requestNotSent() ? 0 : budget.reservedTokens();
                    if (!error.upstreamMayStillBeRunning()) {
                        execution.upstreamFinished(chargedTokens);
                    }
                    // Only uncertain failures retain the concurrency lease. Without known
                    // usage, charge the reservation unless connection establishment failed.
                    return execution.reconcile(chargedTokens)
                            .map(usedAfter -> ResponseEntity.status(502)
                                    .body(GuardedChatResponse.block(
                                            requestId, "upstream-error", trace, budget.toUsage(usedAfter))));
                });
    }

    private static long tokensToCharge(OpenAiChatCompletionResponse response, long reservedTokens) {
        Usage usage = extractUsage(response);
        return usage == null ? reservedTokens : (long) usage.promptTokens() + usage.completionTokens();
    }

    private static ResponseEntity<GuardedChatResponse> unauthenticated(String requestId) {
        var trace = List.of(new ControlTrace(
                AUTH_POLICY, "deterministic", "block", 0, "no authenticated caller with a role"));
        return ResponseEntity.status(401)
                .body(GuardedChatResponse.block(requestId, AUTH_POLICY, trace));
    }

    /** Wywołujący po uwierzytelnieniu: login do logów i rola do polityki. */
    private record Caller(String login, String role) {
    }

    private static Optional<Caller> callerOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()).toLowerCase(Locale.ROOT))
                .findFirst()
                .map(role -> new Caller(authentication.getName(), role));
    }

    /**
     * Guardy INPUT dla rozmowy ({@link ConversationGuard}). Bieżący jest ostatni prompt użytkownika —
     * tylko on może zablokować żądanie albo nadać mu akcję redact. Historia (także wcześniejsze,
     * zablokowane prompty bez odpowiedzi) jest czyszczona po stronie serwera: redakcja albo placeholder.
     */
    private InputCheck guardInput(ActivePolicy policy, String requestId, List<ChatMessage> messages) {
        int current = messages.size() - 1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                current = i;
                break;
            }
        }
        var items = new ArrayList<ConversationGuard.Item>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            items.add(new ConversationGuard.Item(messages.get(i).content(), i == current));
        }
        var result = conversationGuard.check(policy, requestId, items);
        if (result.blocked()) {
            return new InputCheck(Action.BLOCK, result.blockedBy(), messages, result.trace(), null);
        }
        var guarded = new ArrayList<ChatMessage>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            guarded.add(new ChatMessage(messages.get(i).role(), result.texts().get(i)));
        }
        String prompt = messages.get(current).content();
        String sent = result.texts().get(current);
        return new InputCheck(result.action(), null, guarded, result.trace(), sent.equals(prompt) ? null : sent);
    }

    /** Guardy OUTPUT na odpowiedzi modelu i złożenie końcowej odpowiedzi gatewaya. */
    private ResponseEntity<GuardedChatResponse> buildResponse(ActivePolicy policy, String requestId, InputCheck input,
            OpenAiChatCompletionResponse response, List<ControlTrace> trace, BudgetUsage budgetUsage) {
        ChatMessage reply = extractMessage(response);
        GuardChainResult output =
                guardChain.run(policy, Stage.OUTPUT, new GuardContext(requestId, reply.content(), null, null));
        trace.addAll(output.trace());
        if (output.blocked()) {
            return ResponseEntity.status(403)
                    .body(GuardedChatResponse.block(requestId, output.blockedBy(), trace, budgetUsage));
        }

        var message = new ChatMessage(reply.role(), output.text());
        Usage usage = extractUsage(response);
        boolean redacted = input.action() == Action.REDACT || output.action() == Action.REDACT;
        var body = redacted
                ? GuardedChatResponse.redact(requestId, message, usage, trace, budgetUsage)
                : GuardedChatResponse.allow(requestId, message, usage, trace, budgetUsage);
        return ResponseEntity.ok(body.withRedactedPrompt(input.redactedPrompt()));
    }

    private record InputCheck(Action action, String blockedBy, List<ChatMessage> messages, List<ControlTrace> trace,
            String redactedPrompt) {}

    private static ChatMessage extractMessage(OpenAiChatCompletionResponse response) {
        return response.choices().getFirst().message();
    }

    private static Usage extractUsage(OpenAiChatCompletionResponse response) {
        var usage = response.usage();
        if (usage == null || usage.promptTokens() == null || usage.completionTokens() == null) {
            return null;
        }
        return new Usage(usage.promptTokens(), usage.completionTokens());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
