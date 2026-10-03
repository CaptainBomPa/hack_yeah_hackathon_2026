package pl.hackyeah.controllayer.integration;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.budget.BudgetGate.BudgetCheck;
import pl.hackyeah.controllayer.audit.AuditEntry;
import pl.hackyeah.controllayer.audit.AuditLog;
import pl.hackyeah.controllayer.audit.AuditProperties;
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.guard.ConversationGuard;
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardChainResult;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
import pl.hackyeah.controllayer.policy.PolicySource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.node.ObjectNode;

/** Native Responses protocol; buffered SSE is released only after OUTPUT checks and audit. */
@Service
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
public class ResponsesService {
    private final ResponsesUpstream upstream;
    private final IntegrationProperties integration;
    private final PolicySource policies;
    private final ModelCatalog catalog;
    private final ModelAccessPolicy access;
    private final BudgetGate budgets;
    private final GuardChain guards;
    private final ConversationGuard conversation;
    private final AuditLog audit;
    private final AuditProperties auditProperties;

    public ResponsesService(ResponsesUpstream upstream, IntegrationProperties integration, PolicySource policies,
            ModelCatalog catalog, ModelAccessPolicy access, BudgetGate budgets, GuardChain guards,
            AuditLog audit, AuditProperties auditProperties) {
        this.upstream = upstream;
        this.integration = integration;
        this.policies = policies;
        this.catalog = catalog;
        this.access = access;
        this.budgets = budgets;
        this.guards = guards;
        this.conversation = new ConversationGuard(guards);
        this.audit = audit;
        this.auditProperties = auditProperties;
    }

    Mono<ResponseEntity<byte[]>> respond(String body, String session, CodexHeaders credentials,
            ResponsesOperation operation) {
        var state = new RequestState(UUID.randomUUID().toString(), Instant.now(), policies.current(), session);
        return ReactiveSecurityContextHolder.getContext().flatMap(context -> {
            var authentication = context.getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) return Mono.empty();
            state.principal = authentication.getName();
            state.role = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .map(authority -> authority.substring(5).toLowerCase(Locale.ROOT)).findFirst().orElse("");
            return execute(body, state, credentials, operation);
        }).switchIfEmpty(Mono.fromSupplier(() -> error(state, 401, "auth.required")))
                .flatMap(response -> audited(state, response));
    }

    private Mono<ResponseEntity<byte[]>> execute(String body, RequestState state, CodexHeaders credentials,
            ResponsesOperation operation) {
        final ObjectNode request;
        try {
            request = validatedRequest(body, state);
            authorizeProvider(state);
            if (!credentials.hasSubscriptionCredentials()) throw new Denied(401, "integration.chatgpt-auth-required");
            if (operation == ResponsesOperation.COMPACT && request.path("stream").asBoolean(false)) {
                throw new Denied(400, "request.invalid");
            }
        } catch (Denied denied) {
            return Mono.just(error(state, denied.status, denied.code));
        }
        // Include tool schemas and protocol overhead in the conservative input estimate.
        return budgets.check(state.policy, state.role, List.of(new ChatMessage("user", body)))
                .flatMap(budget -> executeWithBudget(request, state, budget, credentials, operation))
                .onErrorResume(exception -> Mono.just(error(state, 503, "integration.pipeline-unavailable")));
    }

    private ObjectNode validatedRequest(String body, RequestState state) {
        final ObjectNode request;
        try {
            request = ResponsesPayload.parse(body);
        } catch (RuntimeException exception) {
            throw new Denied(400, "request.invalid");
        }
        state.model = request.path("model").asText();
        if (state.model.isBlank() || !request.has("input")) throw new Denied(400, "request.invalid");
        if (!ResponsesRequestValidator.supportedRequest(request)) {
            throw new Denied(400, "integration.unsupported-input");
        }
        return request;
    }

    private void authorizeProvider(RequestState state) {
        var model = catalog.resolveAllowed(state.policy, state.model);
        if (model.isEmpty() || !access.allowsModel(state.policy, state.role, state.model)) {
            throw new Denied(403, "policy.model-access");
        }
        // Web models remain in the catalog; Responses uses only the configured provider.
        if (!model.orElseThrow().baseUrl().equals(integration.codexBaseUrl())) {
            throw new Denied(403, "integration.provider");
        }
        state.trace.add(new ControlTrace("policy.model-access", "deterministic", "allow", 0, null));
    }

    private Mono<ResponseEntity<byte[]>> executeWithBudget(ObjectNode request, RequestState state, BudgetCheck budget,
            CodexHeaders credentials, ResponsesOperation operation) {
        state.trace.add(budget.toTrace(state.elapsed()));
        if (!budget.allowed()) return Mono.just(error(state, budget.httpStatus(), budget.blockedBy()));
        // ChatGPT's Codex backend rejects API-only max_output_tokens. Preserve the native request.
        boolean stream = request.path("stream").asBoolean(false);
        return checkedInput(request, state)
                .flatMap(input -> upstream.complete(input, credentials, operation))
                .flatMap(reply -> checkedOutput(reply, stream, state, budget))
                .flatMap(response -> budgets.reconcile(state.role, budget, usedTokens(state)).thenReturn(response))
                .onErrorResume(exception -> reconcileFailure(state, budget, exception));
    }

    /**
     * INPUT przez {@link ConversationGuard}: Codex wysyła całą historię (także prompt, który już
     * zablokowaliśmy), więc o akcji decydują tylko elementy bieżące ({@link ResponsesPayload#visitInput}),
     * a historia jest czyszczona (redakcja / placeholder) z wynikami z cache.
     */
    private Mono<ObjectNode> checkedInput(ObjectNode request, RequestState state) {
        return Mono.fromCallable(() -> {
            var items = new ArrayList<ConversationGuard.Item>();
            ResponsesPayload.visitInput(request, (text, current) -> {
                items.add(new ConversationGuard.Item(text, current));
                return text;
            });
            var result = conversation.check(state.policy, state.id, items);
            state.trace.addAll(result.trace());
            if (result.blocked()) {
                rejectContent(state, new GuardChainResult(GuardChainResult.Action.BLOCK, null, result.blockedBy(),
                        result.trace()), Stage.INPUT, false);
            }
            if (result.action() == GuardChainResult.Action.REDACT) state.action = "redact";
            var texts = result.texts().iterator();
            ResponsesPayload.visitInput(request, (text, current) -> texts.next());
            return request;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<byte[]>> checkedOutput(ResponsesUpstream.Reply reply, boolean stream,
            RequestState state, BudgetCheck budget) {
        return Mono.fromCallable(() -> {
            String raw = reply.body();
            ObjectNode response = stream ? ResponsesPayload.terminalResponse(raw) : ResponsesPayload.parse(raw);
            recordUsage(response, state);
            if (state.outputTokens > budget.maxOutputTokens()) throw new Denied(429, "budget.output_limit");
            if (stream) {
                for (String text : ResponsesPayload.streamTexts(raw, response)) {
                    checkedText(state, Stage.OUTPUT, text, true);
                }
            } else {
                ResponsesPayload.transform(response, text -> checkedText(state, Stage.OUTPUT, text, false));
            }
            return ResponseEntity.ok().headers(reply.headers())
                    .contentType(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                    .body((stream ? raw : ResponsesPayload.encode(response)).getBytes(StandardCharsets.UTF_8));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void recordUsage(ObjectNode response, RequestState state) {
        var usage = response.path("usage");
        if (!validTokenCount(usage.path("input_tokens")) || !validTokenCount(usage.path("output_tokens"))) {
            throw new Denied(502, "upstream.missing-usage");
        }
        state.inputTokens = usage.path("input_tokens").asInt();
        state.outputTokens = usage.path("output_tokens").asInt();
    }

    private static boolean validTokenCount(tools.jackson.databind.JsonNode tokens) {
        return tokens.isIntegralNumber() && tokens.canConvertToInt() && tokens.asInt() >= 0;
    }

    private static long usedTokens(RequestState state) {
        return (long) state.inputTokens + state.outputTokens;
    }

    private Mono<ResponseEntity<byte[]>> reconcileFailure(RequestState state, BudgetCheck budget, Throwable exception) {
        // Unknown usage keeps the reservation; partial generation must not be counted as free.
        long charged = state.inputTokens == null ? budget.reservedTokens() : usedTokens(state);
        if (exception instanceof Denied denied && denied.stage == Stage.INPUT) charged = 0;
        if (exception instanceof ResponsesUpstream.Failure failure) {
            int status = failure.status;
            String code = status == 401 ? "upstream.authentication" : status == 429 ? "upstream.rate-limit" : "upstream.rejected";
            var response = error(state, status, code);
            var forwarded = ResponseEntity.status(status).headers(response.getHeaders()).headers(failure.headers)
                    .body(response.getBody());
            return budgets.reconcile(state.role, budget, status >= 400 && status < 500 ? 0 : charged).thenReturn(forwarded);
        }
        var denied = exception instanceof Denied known ? known : new Denied(502, "upstream.unavailable");
        return budgets.reconcile(state.role, budget, charged).thenReturn(error(state, denied.status, denied.code));
    }

    private String checkedText(RequestState state, Stage stage, String text, boolean stream) {
        var result = guards.run(state.policy, stage, new GuardContext(state.id, text, null, null));
        state.trace.addAll(result.trace());
        if (result.blocked()) rejectContent(state, result, stage, false);
        if (result.action() == GuardChainResult.Action.REDACT) {
            // Rewriting individual SSE deltas can leak values split across frames. Block the buffered stream instead.
            if (stream) rejectContent(state, result, stage, true);
            state.action = "redact";
        }
        return result.text();
    }

    private void rejectContent(RequestState state, GuardChainResult result, Stage stage, boolean streamRedaction) {
        state.rejection = GuardRejection.from(result, stage, streamRedaction);
        throw new Denied(state.rejection.status(), state.rejection.code(), stage);
    }

    private Mono<ResponseEntity<byte[]>> audited(RequestState state, ResponseEntity<byte[]> response) {
        var entry = new AuditEntry(state.id, state.started, state.principal, state.role, state.session,
                state.model, state.action, state.blockedBy, response.getStatusCode().value(), state.elapsed(),
                state.inputTokens, state.outputTokens, 1, state.trace, state.policy.version());
        return Mono.fromRunnable(() -> audit.append(entry)).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(stamped(state, response))
                .onErrorResume(exception -> Mono.just(auditProperties.failClosed()
                        ? stamped(state, error(state, 503, "audit.write")) : stamped(state, response)));
    }

    private ResponseEntity<byte[]> stamped(RequestState state, ResponseEntity<byte[]> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .headers(headers -> {
                    headers.set("X-Request-Id", state.id);
                    headers.set("X-Control-Layer-Action", state.action);
                    headers.set("X-Policy-Version", String.valueOf(state.policy.version()));
                }).body(response.getBody());
    }

    private ResponseEntity<byte[]> error(RequestState state, int status, String code) {
        if (state.rejection != null && !state.rejection.code().equals(code)) state.rejection = null;
        state.action = "block";
        state.blockedBy = state.rejection == null ? code : state.rejection.guard();
        state.trace.add(new ControlTrace(code, "deterministic", "block", state.elapsed(), "Integration request rejected"));
        // Never reflect upstream errors, prompt fragments or exception messages.
        var body = ResponsesPayload.parse("{}");
        var error = body.putObject("error").put("message", state.rejection == null
                        ? rejectionMessage(code) : state.rejection.message())
                .put("type", "control_layer_error").put("code", code)
                .put("request_id", state.id).put("policy_version", state.policy.version())
                .put("retryable", status == 503 || status == 429);
        if (state.rejection != null) {
            error.put("stage", state.rejection.stage()).put("guard", state.rejection.guard())
                    .put("reason", state.rejection.reason());
            var detections = error.putArray("detections");
            state.rejection.detections().forEach(detections::add);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ResponsesPayload.encode(body).getBytes(StandardCharsets.UTF_8));
    }

    private static String rejectionMessage(String code) {
        return switch (code) {
            case "policy.model-access", "integration.provider" -> "Account is not allowed to use the requested model or provider";
            case "auth.required" -> "Gateway authentication required";
            case "integration.chatgpt-auth-required" -> "ChatGPT subscription authentication required";
            case "budget.daily_cap" -> "Daily token budget exhausted";
            case "budget.input_limit" -> "Request exceeds the input token limit";
            case "budget.output_limit" -> "Generated response exceeds the output token limit";
            case "integration.unsupported-input" -> "Unsupported input or tool format";
            case "request.invalid" -> "Invalid Responses request";
            default -> "Request could not be completed by Control Layer (" + code + ")";
        };
    }

    private static final class Denied extends RuntimeException {
        final int status;
        final String code;
        final Stage stage;
        Denied(int status, String code) {
            this(status, code, null);
        }
        Denied(int status, String code, Stage stage) {
            super("Integration request rejected");
            this.status = status;
            this.code = code;
            this.stage = stage;
        }
    }

    private static final class RequestState {
        final String id;
        final Instant started;
        final ActivePolicy policy;
        final String session;
        final List<ControlTrace> trace = new ArrayList<>();
        String principal;
        String role;
        String model;
        String action = "allow";
        String blockedBy;
        GuardRejection rejection;
        Integer inputTokens;
        Integer outputTokens;
        RequestState(String id, Instant started, ActivePolicy policy, String session) {
            this.id = id;
            this.started = started;
            this.policy = policy;
            this.session = session;
        }
        long elapsed() {
            return java.time.Duration.between(started, Instant.now()).toMillis();
        }
    }
}
