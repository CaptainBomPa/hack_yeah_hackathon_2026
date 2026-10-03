package pl.hackyeah.controllayer.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.audit.AuditService;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import pl.hackyeah.controllayer.chat.ChatMessage;

/**
 * Polityka w bazie: zapis z API zmienia NASTĘPNE żądanie bez restartu, błędna wersja nie staje się
 * aktywna, równoległa edycja daje 409. Każdy test przywraca politykę, z którą zaczynał.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyIntegrationTest {

    private static final String PASSWORD = "haslo-testowe-123";
    private static final String MODEL = "qwen2.5:0.5b";

    @LocalServerPort
    private int port;

    @Autowired
    private PolicyStore store;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ModelAccessPolicy modelAccessPolicy;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;
    private String admin;
    private String chat;
    private PolicyDocument original;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        admin = "admin-" + UUID.randomUUID();
        chat = "chat-" + UUID.randomUUID();
        users.save(new AppUser(admin, passwordEncoder.encode(PASSWORD), "admin"));
        users.save(new AppUser(chat, passwordEncoder.encode(PASSWORD), "chat"));
        original = store.current().document();
    }

    @AfterEach
    void restoreOriginalPolicy() {
        store.apply(original, null, "test", "restore", "test cleanup");
    }

    @Test
    void savingAPolicyChangesTheNextRequestWithoutARestart() {
        // Przed zmianą rola chat może wołać MODEL (bez wołania samego modelu — Ollama w testach bywa wolna).
        assertTrue(modelAccessPolicy.allowsModel("chat", MODEL), "chat role should have access before the change");

        long baseVersion = store.current().version();
        var roles = new HashMap<>(original.roles());
        roles.put("chat", new PolicyDocument.RolePolicy(List.of(), 20_000L));
        var changed = new PolicyDocument(roles, original.models(), original.guards(), original.limits());

        client.put().uri("/api/policy").header("Authorization", basic(admin))
                .bodyValue(Map.of("baseVersion", baseVersion, "document", changed, "comment", "chat loses models"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo(baseVersion + 1)
                .jsonPath("$.author").isEqualTo(admin)
                .jsonPath("$.comment").isEqualTo("chat loses models");

        // Po zmianie: to samo żądanie jest blokowane przez politykę, a odpowiedź i audyt mają nową wersję.
        var response = client.post().uri("/v1/chat/completions").header("Authorization", basic(chat))
                .bodyValue(new ChatCompletionRequest(MODEL, List.of(new ChatMessage("user", "hej"))))
                .exchange()
                .expectStatus().isEqualTo(403)
                .expectBody()
                .jsonPath("$.blockedBy").isEqualTo("policy.model-access")
                .jsonPath("$.policyVersion").isEqualTo(baseVersion + 1)
                .returnResult();
        String requestId = com.jayway.jsonpath.JsonPath.read(
                new String(response.getResponseBody(), StandardCharsets.UTF_8), "$.requestId");
        assertEquals(baseVersion + 1, auditService.find(requestId).orElseThrow().getPolicyVersion());
    }

    @Test
    void invalidPolicyIsRejectedAndTheActiveVersionStays() {
        long baseVersion = store.current().version();
        var broken = new PolicyDocument(Map.of(), original.models(), original.guards(), original.limits());

        client.put().uri("/api/policy").header("Authorization", basic(admin))
                .bodyValue(Map.of("baseVersion", baseVersion, "document", broken))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_policy")
                .jsonPath("$.error.errors[?(@.path == 'roles')]").exists();

        assertEquals(baseVersion, store.current().version());
    }

    @Test
    void concurrentEditIsAConflictNotASilentOverwrite() {
        long baseVersion = store.current().version();
        var limits = new PolicyDocument.Limits(3000, 512);
        var first = new PolicyDocument(original.roles(), original.models(), original.guards(), limits);
        store.apply(first, baseVersion, "someone-else", "ui", null);

        client.put().uri("/api/policy").header("Authorization", basic(admin))
                .bodyValue(Map.of("baseVersion", baseVersion, "document", original))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error.currentVersion").isEqualTo(baseVersion + 1);
    }

    @Test
    void historyRestoreAndYamlRoundTrip() {
        long baseVersion = store.current().version();
        var limits = new PolicyDocument.Limits(2000, 256);
        store.apply(new PolicyDocument(original.roles(), original.models(), original.guards(), limits),
                baseVersion, admin, "ui", "smaller limits");

        client.get().uri("/api/policy/versions").header("Authorization", basic(admin))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].comment").isEqualTo("smaller limits");

        client.post().uri("/api/policy/versions/" + baseVersion + "/restore").header("Authorization", basic(admin))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("restore")
                .jsonPath("$.document.limits.maxOutputTokens").isEqualTo(original.limits().maxOutputTokens());

        byte[] yaml = client.get().uri("/api/policy/export").header("Authorization", basic(admin))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String text = new String(yaml, StandardCharsets.UTF_8).replace("maxOutputTokens: " + original.limits().maxOutputTokens(),
                "maxOutputTokens: 777");
        client.post().uri("/api/policy/import").header("Authorization", basic(admin))
                .bodyValue(Map.of("yaml", text, "comment", "from file"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("import")
                .jsonPath("$.document.limits.maxOutputTokens").isEqualTo(777);
    }

    @Test
    void policyApiIsAdminOnly() {
        client.get().uri("/api/policy").header("Authorization", basic(chat))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/policy").header("Authorization", basic(admin))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.catalog.models[?(@.tag == '" + MODEL + "')]").exists()
                .jsonPath("$.catalog.piiRecognizers[?(@.id == 'PII-001')]").exists()
                .jsonPath("$.catalog.roleAccounts.chat").exists();
    }

    private static String basic(String login) {
        return "Basic " + Base64.getEncoder().encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
