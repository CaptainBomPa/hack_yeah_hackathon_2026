package pl.hackyeah.controllayer.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.budget.BudgetGate.BudgetCheck;
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
    private final BudgetGate budgetGate;
    private final OllamaChatClient upstreamClient;
    private final GuardChain guardChain;
    private final AuditLog auditLog;
    private final AuditProperties auditProperties;

    public ChatCompletionController(ModelCatalog modelCatalog, ModelAccessPolicy modelAccessPolicy,
            BudgetGate budgetGate, OllamaChatClient upstreamClient, GuardChain guardChain, AuditLog auditLog,
            AuditProperties auditProperties) {
        this.modelCatalog = modelCatalog;
        this.modelAccessPolicy = modelAccessPolicy;
        this.budgetGate = budgetGate;
        this.upstreamClient = upstreamClient;
        this.guardChain = guardChain;
        this.auditLog = auditLog;
        this.auditProperties = auditProperties;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<GuardedChatResponse>> chatCompletions(
            @Valid @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String requestId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        long startedAt = System.nanoTime();

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(callerOf(context.getAuthentication())))
                .flatMap(caller -> complete(request, caller, requestId, startedAt)
                        .map(response -> new Outcome(caller, response)))
                .switchIfEmpty(Mono.fromSupplier(() -> new Outcome(null, unauthenticated(requestId))))
                .flatMap(outcome -> audited(outcome, request, sessionId, requestId, occurredAt, startedAt));
    }

    private record Outcome(Caller caller, ResponseEntity<GuardedChatResponse> response) {}

    /**
     * Zapis decyzji do audytu przed wysłaniem odpowiedzi (AUDIT-001). Bez treści wiadomości —
     * tylko metadane i ścieżka kontroli. Gdy zapis się nie uda, a audyt jest fail-closed,
     * klient dostaje 503 zamiast odpowiedzi modelu (AUDIT-008).
     */
    private Mono<ResponseEntity<GuardedChatResponse>> audited(Outcome outcome, ChatCompletionRequest request,
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
                body.trace());
        return Mono.fromRunnable(() -> auditLog.append(entry))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(outcome.response())
                .onErrorResume(error -> {
                    log.error("requestId={} audit write failed failClosed={}",
                            requestId, auditProperties.failClosed(), error);
                    if (!auditProperties.failClosed()) {
                        return Mono.just(outcome.response());
                    }
                    var trace = new ArrayList<>(body.trace());
                    trace.add(new ControlTrace(AUDIT_POLICY, "deterministic", "block", elapsedMillis(startedAt),
                            "audit log unavailable (fail-closed)"));
                    return Mono.just(ResponseEntity.status(503)
                            .body(GuardedChatResponse.block(requestId, AUDIT_POLICY, trace)));
                });
    }

    private Mono<ResponseEntity<GuardedChatResponse>> complete(ChatCompletionRequest request, Caller caller,
            String requestId, long startedAt) {
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

        if (!modelAccessPolicy.allowsModel(caller.role(), request.model())) {
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
        var trace = new ArrayList<ControlTrace>();
        trace.add(new ControlTrace(MODEL_ALLOWLIST_POLICY, "deterministic", "allow", elapsedMillis(startedAt), null));

        return budgetGate.check(caller.role(), request.messages()).flatMap(budget -> {
            trace.add(budget.toTrace(elapsedMillis(startedAt)));
            if (!budget.allowed()) {
                log.info("requestId={} caller={} role={} action=block blockedBy={}",
                        requestId, caller.login(), caller.role(), budget.blockedBy());
                // Dla input_limit nie znamy sensownego "used" (nie doszło nawet do sprawdzenia
                // budżetu dziennego); dla daily_cap used≈limit, bo właśnie dlatego blokujemy.
                BudgetUsage budgetUsage = budget.isDailyCapExceeded() ? budget.toUsage(budget.dailyLimit()) : null;
                return Mono.just(ResponseEntity.status(budget.httpStatus())
                        .body(GuardedChatResponse.block(requestId, budget.blockedBy(), trace, budgetUsage)));
            }

            return Mono.fromCallable(() -> guardInput(requestId, request.messages()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(input -> {
                        trace.addAll(input.trace());
                        if (input.action() == Action.BLOCK) {
                            log.info("requestId={} model={} action=block blockedBy={}",
                                    requestId, request.model(), input.blockedBy());
                            // Żądanie nigdy nie dotarło do modelu — zwalniamy rezerwację, inaczej
                            // zablokowane prompty cicho zjadałyby budżet roli (BUDGET-004).
                            return budgetGate.reconcile(caller.role(), budget, 0)
                                    .map(usedAfter -> ResponseEntity.status(403)
                                            .body(GuardedChatResponse.block(
                                                    requestId, input.blockedBy(), trace, budget.toUsage(usedAfter))));
                        }
                        var guardedRequest = new ChatCompletionRequest(request.model(), input.messages())
                                .withMaxTokens(budget.maxOutputTokens());
                        return upstreamClient
                                .complete(model.baseUrl(), guardedRequest, timeout)
                                .flatMap(response -> budgetGate
                                        .reconcile(caller.role(), budget, actualTokensOf(response))
                                        .flatMap(usedAfter -> Mono.fromCallable(() -> buildResponse(
                                                        requestId, input.action(), response, trace,
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
                        // Model nie odpowiedział — bez usage z Ollamy, więc zwalniamy całą
                        // rezerwację zamiast zgadywać zużycie (case file §7: "nigdy 0" dotyczy
                        // uciętego streamu z częściową odpowiedzią; tu nie wygenerowano nic).
                        return budgetGate.reconcile(caller.role(), budget, 0)
                                .map(usedAfter -> ResponseEntity.status(502)
                                        .body(GuardedChatResponse.block(
                                                requestId, "upstream-error", trace, budget.toUsage(usedAfter))));
                    });
        });
    }

    private static long actualTokensOf(OpenAiChatCompletionResponse response) {
        Usage usage = extractUsage(response);
        return (long) usage.promptTokens() + usage.completionTokens();
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
    private ResponseEntity<GuardedChatResponse> buildResponse(String requestId, Action inputAction,
            OpenAiChatCompletionResponse response, List<ControlTrace> trace, BudgetUsage budgetUsage) {
        ChatMessage reply = extractMessage(response);
        GuardChainResult output =
                guardChain.run(Stage.OUTPUT, new GuardContext(requestId, reply.content(), null, null));
        trace.addAll(output.trace());
        if (output.blocked()) {
            return ResponseEntity.status(403)
                    .body(GuardedChatResponse.block(requestId, output.blockedBy(), trace, budgetUsage));
        }

        var message = new ChatMessage(reply.role(), output.text());
        Usage usage = extractUsage(response);
        boolean redacted = inputAction == Action.REDACT || output.action() == Action.REDACT;
        return ResponseEntity.ok(redacted
                ? GuardedChatResponse.redact(requestId, message, usage, trace, budgetUsage)
                : GuardedChatResponse.allow(requestId, message, usage, trace, budgetUsage));
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
