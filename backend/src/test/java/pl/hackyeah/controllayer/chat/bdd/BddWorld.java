package pl.hackyeah.controllayer.chat.bdd;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import pl.hackyeah.controllayer.guard.semantic.SemanticGuard;
import pl.hackyeah.controllayer.guard.semantic.SidecarClient;
import pl.hackyeah.controllayer.guard.semantic.SidecarProperties;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import pl.hackyeah.controllayer.policy.PolicyProperties;
import pl.hackyeah.controllayer.policy.PolicySource;

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
    private String defaultModelTag;
    private String currentLogin;
    private String currentRole;
    private Integer maxInputTokens;
    private Integer maxOutputTokens;

    private GuardedChatResponse lastResponse;
    private int lastHttpStatus;

    public BddWorld() {
        guardRules.put("PII-RECOGNIZERS", new GuardProperties.Rule(true, 100, Map.of()));
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

    private void send(String modelTag, String text, boolean authenticated) {
        var client = buildClient(authenticated);
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

    // ---- building the gateway ----

    private WebTestClient buildClient(boolean authenticated) {
        if (fakeModel == null) {
            // The scenario did not explicitly define a model/response — a sensible default stub.
            registerModelOnly("default-model");
            modelResponds("ok");
            defaultModelTag = defaultModelTag == null ? "default-model" : defaultModelTag;
        }

        var policyProperties = new PolicyProperties(rolePolicies);
        var catalogProperties = new ModelCatalogProperties(modelEntries, Duration.ofSeconds(5));
        var guardProperties = new GuardProperties(true, guardRules);
        var budgetLimits = new BudgetLimitsProperties(maxInputTokens, maxOutputTokens);

        var catalog = new ModelCatalog(catalogProperties);
        var policy = new ModelAccessPolicy(policyProperties);
        var budgetGate = new BudgetGate(policyProperties, budgetLimits, new BudgetService(budgetJdbcTemplate()));

        List<Guard> guards = new ArrayList<>();
        guards.add(new PiiRecognizerGuard(guardProperties, new DefaultResourceLoader()));
        if (guardRules.containsKey("SEM-001")) {
            String sidecarUrl = sidecarDown || fakeSidecar == null ? "http://localhost:1" : fakeSidecar.url();
            guards.add(new SemanticGuard(new SidecarClient(WebClient.builder(), new SidecarProperties(sidecarUrl))));
        }
        var guardChain = new GuardChain(guards, guardProperties);

        // The controller takes one policy snapshot per request and uses it for every check
        // (model allowlist, role access, guards, budget) — it must be built from the exact same
        // config objects as catalog/policy/guardChain/budgetGate above, or the snapshot the
        // controller actually uses would disagree with what we just configured.
        PolicySource policySource = PolicySource.fixed(
                PolicyDocument.fromConfig(policyProperties, catalogProperties, guardProperties, budgetLimits));

        AuditLog auditLog = audited::add;
        var controller = new ChatCompletionController(catalog, policy, budgetGate,
                new OllamaChatClient(WebClient.builder()), guardChain, auditLog,
                new AuditProperties(null, true, null), policySource);

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

    public void tearDown() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
        if (fakeSidecar != null) {
            fakeSidecar.stop();
        }
    }
}
