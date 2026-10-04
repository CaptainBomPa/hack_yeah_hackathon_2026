package pl.hackyeah.controllayer.chat.bdd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import pl.hackyeah.controllayer.chat.ChatCompletionController;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.chat.GuardedChatResponse;
import pl.hackyeah.controllayer.chat.upstream.OllamaChatClient;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardChain;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.guard.pii.PiiRecognizerGuard;
import pl.hackyeah.controllayer.guard.secrets.SecretGuard;
import pl.hackyeah.controllayer.guard.semantic.SemanticGuard;
import pl.hackyeah.controllayer.guard.signature.SignatureFeedGuard;
import pl.hackyeah.controllayer.guard.semantic.SidecarClient;
import pl.hackyeah.controllayer.guard.semantic.SidecarProperties;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import pl.hackyeah.controllayer.policy.PolicyProperties;
import pl.hackyeah.controllayer.policy.PolicyStore;
import pl.hackyeah.controllayer.policy.TestPolicyStores;

/**
 * Shared context for a single Cucumber scenario — a fresh instance per scenario (PicoContainer,
 * see {@code backend/README.md} "Self-testing suite"). Builds a REAL {@link ChatCompletionController}
 * with a real {@link GuardChain}/{@link BudgetGate}; only the model and the semantic sidecar are
 * HTTP fakes — the same pattern as {@code ChatCompletionControllerTest}/{@code
 * SemanticGuardControllerTest}, just driven by Given/When/Then steps instead of test code.
 * Deliberately built without {@code controllerAdvice}: no scenario exercises malformed-JSON
 * validation, and the exception-handling class is package-private in
 * {@code pl.hackyeah.controllayer.chat}.
 */
public class BddWorld {

    private final List<ModelCatalogProperties.ModelEntry> modelEntries = new ArrayList<>();
    private final Map<String, PolicyProperties.RolePolicy> rolePolicies = new HashMap<>();
    private final Map<String, GuardProperties.Rule> guardRules = new HashMap<>();
    private final List<AuditEntry> audited = new ArrayList<>();

    private FakeHttpService fakeModel;
    private FakeHttpService fakeSidecar;
    private boolean sidecarDown;
    private String realSidecarUrl;
    private String defaultModelTag;
    private String currentLogin;
    private String currentRole;
    private Integer maxInputTokens;
    private Integer maxOutputTokens;

    private GuardedChatResponse lastResponse;
    private int lastHttpStatus;
    private boolean lastEditWasRejected;
    private String lastModelTag;
    private String lastPromptText;

    private ChatCompletionController controller;
    private PolicyStore policyStore;

    public BddWorld() {
        // First in the chain (order 40), matching application.yml — it must see the text before
        // any other guard redacts/rewrites it.
        guardRules.put("SIG-FEED", new GuardProperties.Rule(true, 40, Map.of()));
        guardRules.put("PII-RECOGNIZERS", new GuardProperties.Rule(true, 100, Map.of()));
        guardRules.put("SEC-GITLEAKS", new GuardProperties.Rule(true, 150, Map.of()));
        // PolicyValidator requires an "admin" role to exist in every policy document, including
        // the seed the store boots with — see PolicyValidator.ADMIN_ROLE.
        rolePolicies.put("admin", new PolicyProperties.RolePolicy(List.of(PolicyDocument.ANY_MODEL), null));
    }

    // ---- Given: building the scenario ----

    public void allowRoleModel(String role, String modelTag) {
        registerModelOnly(modelTag);
        var existing = rolePolicies.get(role);
        var models = new ArrayList<>(existing == null ? List.<String>of() : existing.models());
        models.add(modelTag);
        rolePolicies.put(role, new PolicyProperties.RolePolicy(models, existing == null ? null : existing.budget()));
        defaultModelTag = modelTag;
    }

    /** Grants {@code role} every catalog model via {@code PolicyDocument.ANY_MODEL} ("*"), without picking one. */
    public void allowRoleAnyModel(String role) {
        var existing = rolePolicies.get(role);
        rolePolicies.put(role,
                new PolicyProperties.RolePolicy(List.of(PolicyDocument.ANY_MODEL), existing == null ? null : existing.budget()));
    }

    public void registerModelOnly(String modelTag) {
        ensureFakeModel();
        if (modelEntries.stream().noneMatch(e -> e.tag().equals(modelTag))) {
            modelEntries.add(new ModelCatalogProperties.ModelEntry(modelTag, fakeModel.url(), true));
        }
    }

    public void setRoleDailyBudget(String role, long dailyTokens) {
        var existing = rolePolicies.get(role);
        var models = existing == null ? List.<String>of() : existing.models();
        rolePolicies.put(role,
                new PolicyProperties.RolePolicy(models, new PolicyProperties.RolePolicy.Budget(dailyTokens)));
    }

    public void setMaxInputTokens(int max) {
        this.maxInputTokens = max;
    }

    public void setMaxOutputTokens(int max) {
        this.maxOutputTokens = max;
    }

    public void loginAs(String login, String role) {
        this.currentLogin = login;
        this.currentRole = role;
    }

    public void modelResponds(String content) {
        ensureFakeModel();
        fakeModel.respond(body -> new FakeHttpService.Response(200, openAiResponseJson(content)));
    }

    public void modelIsUnavailable() {
        ensureFakeModel();
        fakeModel.stop();
    }

    public void sidecarScores(double score) {
        enableSemanticGuard();
        ensureFakeSidecar();
        fakeSidecar.respond(body -> new FakeHttpService.Response(200, sidecarResponseJson(score)));
    }

    public void sidecarIsUnavailable() {
        enableSemanticGuard();
        sidecarDown = true;
    }

    /** Two detectors instead of one — SEM-001 blocks on the MAX score, not the first or the average. */
    public void sidecarReportsScores(double first, double second) {
        enableSemanticGuard();
        ensureFakeSidecar();
        fakeSidecar.respond(body -> new FakeHttpService.Response(200, sidecarMultiScoreResponseJson(first, second)));
    }

    /** No detector covered this checkpoint at all — distinct from a low/zero score (SemanticGuard.check). */
    public void sidecarReturnsNoResults() {
        enableSemanticGuard();
        ensureFakeSidecar();
        fakeSidecar.respond(body -> new FakeHttpService.Response(200,
                "{\"checkpoint\":\"P1\",\"complete\":true,\"missing_checks\":[],\"results\":[]}"));
    }

    /** complete=false with no usable result — e.g. a detector timed out sidecar-side. */
    public void sidecarReturnsIncompleteCheck() {
        enableSemanticGuard();
        ensureFakeSidecar();
        fakeSidecar.respond(body -> new FakeHttpService.Response(200,
                "{\"checkpoint\":\"P1\",\"complete\":false,"
                        + "\"missing_checks\":[{\"check\":\"P1\",\"reason\":\"timeout\"}],\"results\":[]}"));
    }

    /** Forces a client-side timeout: the fake sleeps longer than SEM-001's configured timeoutMs. */
    public void sidecarRespondsSlowly(long delayMillis) {
        enableSemanticGuard();
        ensureFakeSidecar();
        fakeSidecar.delayResponsesBy(delayMillis);
        fakeSidecar.respond(body -> new FakeHttpService.Response(200, sidecarResponseJson(0.0)));
    }

    public void setSemanticBlockThreshold(double threshold) {
        enableSemanticGuard();
        guardRules.merge("SEM-001", new GuardProperties.Rule(true, 200, Map.of("blockThreshold", threshold)),
                (existing, added) -> withParam(existing, "blockThreshold", threshold));
    }

    public void setSemanticFailureMode(String mode) {
        enableSemanticGuard();
        guardRules.compute("SEM-001", (id, existing) -> withParam(existing, "failureMode", mode));
    }

    public void setSemanticTimeoutMs(int timeoutMs) {
        enableSemanticGuard();
        guardRules.compute("SEM-001", (id, existing) -> withParam(existing, "timeoutMs", timeoutMs));
    }

    // ---- SIG-FEED: a real file on disk, so hot-reload/fail-closed/missing-file behaviour can be
    // exercised for real — SignatureFeedLoader reads an actual Path, not a classpath resource.

    private Path signatureFeedPath;
    private long signatureFeedMtimeOffsetMs;

    /** Points SIG-FEED at a throwaway file instead of the real config/signatures/active.yaml. */
    public void useCustomSignatureFeed(String yaml) {
        try {
            signatureFeedPath = Files.createTempFile("bdd-signature-feed", ".yaml");
            Files.writeString(signatureFeedPath, yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // checkIntervalMs=0: every current() call re-stats the file, so a test never has to sleep
        // past a throttle window to observe a change — see SignatureFeedLoader#current().
        guardRules.put("SIG-FEED", new GuardProperties.Rule(true, 40,
                Map.of("feed", signatureFeedPath.toString(), "checkIntervalMs", 0)));
    }

    /** Overwrites the custom feed file with new content and forces a strictly later mtime. */
    public void updateSignatureFeedFile(String yaml) {
        try {
            Files.writeString(signatureFeedPath, yaml);
            signatureFeedMtimeOffsetMs += 1000;
            Files.setLastModifiedTime(signatureFeedPath,
                    FileTime.fromMillis(System.currentTimeMillis() + signatureFeedMtimeOffsetMs));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteSignatureFeedFile() {
        try {
            Files.deleteIfExists(signatureFeedPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Merges one param into an existing (pre-controller) guard rule without disturbing the rest. */
    private static GuardProperties.Rule withParam(GuardProperties.Rule existing, String name, Object value) {
        var params = new LinkedHashMap<>(existing.params());
        params.put(name, value);
        return new GuardProperties.Rule(existing.enabled(), existing.order(), params);
    }

    /**
     * Points SEM-001 at a real, already-running semantic-sidecar process instead of the HTTP
     * fake — the only place this suite calls the actual Horizon classifier. Timeout and threshold
     * mirror the real deployment defaults (application.yml), since a cold-loaded model answering a
     * short prompt is slower than the instant fake but still well under a second once warmed up.
     */
    public void useRealSemanticSidecar(String baseUrl) {
        guardRules.put("SEM-001", new GuardProperties.Rule(true, 200,
                Map.of("blockThreshold", 0.9, "timeoutMs", 4000, "failureMode", "closed")));
        this.realSidecarUrl = baseUrl;
    }

    // ---- When: the action ----

    public void sendPrompt(String text) {
        send(defaultModelTag, text, true);
    }

    public void sendPromptWithModel(String modelTag, String text) {
        send(modelTag, text, true);
    }

    public void sendPromptUnauthenticated(String text) {
        send(defaultModelTag, text, false);
    }

    /** Resends the exact same prompt and model as the previous send — used to show a policy edit take effect. */
    public void resendLastPrompt() {
        send(lastModelTag, lastPromptText, true);
    }

    private void send(String modelTag, String text, boolean authenticated) {
        ensureController();
        lastModelTag = modelTag;
        lastPromptText = text;
        var client = webTestClientFor(authenticated);
        var result = client.post()
                .uri("/v1/chat/completions")
                .bodyValue(new ChatCompletionRequest(
                        modelTag == null ? "unspecified-model" : modelTag, List.of(new ChatMessage("user", text))))
                .exchange()
                .expectBody(GuardedChatResponse.class)
                .returnResult();
        lastHttpStatus = result.getStatus().value();
        lastResponse = result.getResponseBody();
    }

    // ---- Then: reading the outcome ----

    public GuardedChatResponse lastResponse() {
        return lastResponse;
    }

    public int lastHttpStatus() {
        return lastHttpStatus;
    }

    public List<AuditEntry> audited() {
        return audited;
    }

    public boolean lastEditWasRejected() {
        return lastEditWasRejected;
    }

    // ---- When: editing the live policy mid-scenario ----

    public void removeModelFromRole(String role, String modelTag) {
        ensureController();
        editPolicy(doc -> {
            var roles = new LinkedHashMap<>(doc.roles());
            var existing = roles.get(role);
            var models = existing.models().stream().filter(m -> !m.equals(modelTag)).toList();
            roles.put(role, new PolicyDocument.RolePolicy(models, existing.dailyTokens()));
            return new PolicyDocument(roles, doc.models(), doc.guards(), doc.limits(), doc.rateLimit());
        });
    }

    public void grantModelToRole(String role, String modelTag) {
        ensureController();
        editPolicy(doc -> {
            var roles = new LinkedHashMap<>(doc.roles());
            var existing = roles.getOrDefault(role, new PolicyDocument.RolePolicy(List.of(), null));
            var models = new ArrayList<>(existing.models());
            models.add(modelTag);
            roles.put(role, new PolicyDocument.RolePolicy(models, existing.dailyTokens()));
            return new PolicyDocument(roles, doc.models(), doc.guards(), doc.limits(), doc.rateLimit());
        });
    }

    public void setModelEnabledLive(String modelTag, boolean enabled) {
        ensureController();
        editPolicy(doc -> {
            var models = doc.models().stream()
                    .map(m -> m.tag().equals(modelTag) ? new PolicyDocument.ModelPolicy(modelTag, enabled) : m)
                    .toList();
            return new PolicyDocument(doc.roles(), models, doc.guards(), doc.limits(), doc.rateLimit());
        });
    }

    public void setGuardEnabledLive(String guardId, boolean enabled) {
        ensureController();
        editPolicy(doc -> {
            var guards = new LinkedHashMap<>(doc.guards());
            var existing = guards.get(guardId);
            guards.put(guardId, new PolicyDocument.GuardPolicy(enabled, existing.order(), existing.params()));
            return new PolicyDocument(doc.roles(), doc.models(), guards, doc.limits(), doc.rateLimit());
        });
    }

    /** Changes WHEN a guard runs relative to the others (lower runs first) — not a {@code params} entry. */
    public void setGuardOrderLive(String guardId, int order) {
        ensureController();
        editPolicy(doc -> {
            var guards = new LinkedHashMap<>(doc.guards());
            var existing = guards.get(guardId);
            guards.put(guardId, new PolicyDocument.GuardPolicy(existing.enabled(), order, existing.params()));
            return new PolicyDocument(doc.roles(), doc.models(), guards, doc.limits(), doc.rateLimit());
        });
    }

    public void setGuardParamLive(String guardId, String paramName, Object value) {
        ensureController();
        editPolicy(doc -> {
            var guards = new LinkedHashMap<>(doc.guards());
            var existing = guards.get(guardId);
            var params = new LinkedHashMap<>(existing.params());
            params.put(paramName, value);
            guards.put(guardId, new PolicyDocument.GuardPolicy(existing.enabled(), existing.order(), params));
            return new PolicyDocument(doc.roles(), doc.models(), guards, doc.limits(), doc.rateLimit());
        });
    }

    public void setRoleDailyBudgetLive(String role, Long dailyTokens) {
        ensureController();
        editPolicy(doc -> {
            var roles = new LinkedHashMap<>(doc.roles());
            var existing = roles.getOrDefault(role, new PolicyDocument.RolePolicy(List.of(), null));
            roles.put(role, new PolicyDocument.RolePolicy(existing.models(), dailyTokens));
            return new PolicyDocument(roles, doc.models(), doc.guards(), doc.limits(), doc.rateLimit());
        });
    }

    public void setMaxInputTokensLive(int max) {
        ensureController();
        editPolicy(doc -> new PolicyDocument(doc.roles(), doc.models(), doc.guards(),
                new PolicyDocument.Limits(max, doc.limits().maxOutputTokens()), doc.rateLimit()));
    }

    public void attemptInvalidPolicyEdit() {
        attemptPolicyEdit(doc -> new PolicyDocument(Map.of(), doc.models(), doc.guards(), doc.limits(), doc.rateLimit()));
    }

    /** General-purpose: builds any (valid or invalid) document and records whether it was rejected. */
    public void attemptPolicyEdit(UnaryOperator<PolicyDocument> mutator) {
        ensureController();
        lastEditWasRejected = false;
        try {
            editPolicy(mutator);
        } catch (PolicyStore.ValidationException e) {
            lastEditWasRejected = true;
        }
    }

    public PolicyDocument currentPolicyDocument() {
        ensureController();
        return policyStore.current().document();
    }

    private void editPolicy(UnaryOperator<PolicyDocument> mutator) {
        var updated = mutator.apply(policyStore.current().document());
        policyStore.apply(updated, null, "admin-test", "test", null);
    }

    // ---- building the gateway ----

    private void ensureController() {
        if (controller != null) {
            return;
        }
        if (fakeModel == null) {
            // The scenario did not explicitly define a model/response — a sensible default stub.
            registerModelOnly("default-model");
            modelResponds("ok");
            defaultModelTag = defaultModelTag == null ? "default-model" : defaultModelTag;
        }

        var policyProperties = new PolicyProperties(rolePolicies, new pl.hackyeah.controllayer.ratelimit.RateLimitSettings(
                "off", null, null, null, null, null));
        var catalogProperties = new ModelCatalogProperties(modelEntries, Duration.ofSeconds(5));
        var guardProperties = new GuardProperties(true, guardRules);
        var budgetLimits = new BudgetLimitsProperties(maxInputTokens, maxOutputTokens);

        var catalog = new ModelCatalog(catalogProperties);
        var policy = new ModelAccessPolicy(policyProperties);
        var budgetGate = new BudgetGate(policyProperties, budgetLimits, new BudgetService(budgetJdbcTemplate()));

        List<Guard> guards = new ArrayList<>();
        guards.add(new PiiRecognizerGuard(guardProperties, new DefaultResourceLoader()));
        guards.add(new SecretGuard());
        guards.add(new SignatureFeedGuard());
        if (guardRules.containsKey("SEM-001")) {
            String sidecarUrl = realSidecarUrl != null ? realSidecarUrl
                    : sidecarDown || fakeSidecar == null ? "http://localhost:1" : fakeSidecar.url();
            guards.add(new SemanticGuard(new SidecarClient(WebClient.builder(), new SidecarProperties(sidecarUrl))));
        }
        var guardChain = new GuardChain(guards, guardProperties);

        // A real, hot-reloadable PolicyStore (same validation/version-bumping logic as
        // production) backed by in-memory fakes — lets scenarios edit the live policy mid-flight
        // and see the very next request obey the new rules, exactly like the real admin API does.
        policyStore = TestPolicyStores.seeded(guards, catalogProperties, policyProperties, guardProperties,
                budgetLimits);

        AuditLog auditLog = audited::add;
        var executionGate = new pl.hackyeah.controllayer.chat.ChatExecutionGate(
                new pl.hackyeah.controllayer.ratelimit.RateLimitGate(
                        new PolicyProperties(rolePolicies, new pl.hackyeah.controllayer.ratelimit.RateLimitSettings(
                                "off", null, null, null, null, null)), null,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), budgetGate);
        controller = new ChatCompletionController(catalog, policy,
                new OllamaChatClient(WebClient.builder()), guardChain, auditLog,
                new AuditProperties(null, true, null), executionGate, policyStore);
    }

    private WebTestClient webTestClientFor(boolean authenticated) {
        var builder = WebTestClient.bindToController(controller);
        if (authenticated) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    currentLogin == null ? "tester" : currentLogin, "n/a",
                    List.of(new SimpleGrantedAuthority(
                            "ROLE_" + (currentRole == null ? "chat" : currentRole).toUpperCase(Locale.ROOT))));
            WebFilter authFilter = (exchange, next) -> next.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            builder = builder.webFilter(authFilter);
        }
        return builder.build();
    }

    private void enableSemanticGuard() {
        guardRules.putIfAbsent("SEM-001", new GuardProperties.Rule(true, 200,
                Map.of("blockThreshold", 0.998, "timeoutMs", 1500, "failureMode", "closed")));
    }

    private JdbcTemplate budgetJdbcTemplate;

    /**
     * An H2 database in PostgreSQL-compatibility mode, isolated per scenario — exactly the
     * pattern from {@code BudgetServiceTest}, because BudgetService runs atomic SQL UPDATEs that
     * cannot be mocked generically (that is the logic under test, not an implementation detail
     * to hide).
     */
    private JdbcTemplate budgetJdbcTemplate() {
        if (budgetJdbcTemplate == null) {
            var dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:bdd-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            budgetJdbcTemplate = new JdbcTemplate(dataSource);
            budgetJdbcTemplate.execute("""
                    CREATE TABLE budget_counter (
                        subject      VARCHAR(200) NOT NULL,
                        period_kind  VARCHAR(10)  NOT NULL,
                        period_start DATE         NOT NULL,
                        used_tokens  BIGINT       NOT NULL DEFAULT 0,
                        reserved     BIGINT       NOT NULL DEFAULT 0,
                        PRIMARY KEY (subject, period_kind, period_start)
                    )
                    """);
        }
        return budgetJdbcTemplate;
    }

    private void ensureFakeModel() {
        if (fakeModel == null) {
            fakeModel = new FakeHttpService("/v1/chat/completions");
        }
    }

    private void ensureFakeSidecar() {
        if (fakeSidecar == null) {
            fakeSidecar = new FakeHttpService("/classify");
        }
    }

    private static String openAiResponseJson(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }

    private static String sidecarResponseJson(double score) {
        return "{\"checkpoint\":\"P1\",\"complete\":true,\"missing_checks\":[],\"results\":["
                + "{\"detector\":\"injection_classifier_protectai\",\"status\":\"ok\",\"score\":" + score
                + ",\"raw_score\":" + score + "}]}";
    }

    private static String sidecarMultiScoreResponseJson(double first, double second) {
        return "{\"checkpoint\":\"P1\",\"complete\":true,\"missing_checks\":[],\"results\":["
                + "{\"detector\":\"detector-a\",\"status\":\"ok\",\"score\":" + first + ",\"raw_score\":" + first + "},"
                + "{\"detector\":\"detector-b\",\"status\":\"ok\",\"score\":" + second + ",\"raw_score\":" + second + "}]}";
    }

    public void tearDown() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
        if (fakeSidecar != null) {
            fakeSidecar.stop();
        }
        if (signatureFeedPath != null) {
            try {
                Files.deleteIfExists(signatureFeedPath);
            } catch (IOException ignored) {
                // best-effort cleanup of a scenario-scoped temp file
            }
        }
    }
}
