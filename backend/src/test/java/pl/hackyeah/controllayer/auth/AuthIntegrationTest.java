package pl.hackyeah.controllayer.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import pl.hackyeah.controllayer.chat.ChatMessage;

/**
 * Pełny łańcuch HTTP: Basic Auth na kontach z bazy, polityka roli i ochrona `/api/**`. Model
 * nie jest uruchomiony, więc udane przejście kontroli kończy się 502 (błąd upstreamu), a nie 401/403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    private static final String PASSWORD = "haslo-testowe-123";
    private static final String ALLOWED_MODEL = "qwen2.5:1.5b-instruct-q4_K_M";
    private static final String MODEL_OUTSIDE_AGENT_POLICY = "qwen2.5:0.5b";

    @LocalServerPort
    private int port;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;
    private String chatLogin;
    private String agentLogin;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        chatLogin = "chat-" + UUID.randomUUID();
        agentLogin = "agent-" + UUID.randomUUID();
        users.save(new AppUser(chatLogin, passwordEncoder.encode(PASSWORD), "chat"));
        users.save(new AppUser(agentLogin, passwordEncoder.encode(PASSWORD), "agent"));
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void chatWithoutCredentialsIsRejectedBeforeTheModel() {
        client.post().uri("/v1/chat/completions")
                .bodyValue(request(ALLOWED_MODEL))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void wrongPasswordIsRejected() {
        client.post().uri("/v1/chat/completions")
                .header("Authorization", basic(chatLogin, "zle-haslo"))
                .bodyValue(request(ALLOWED_MODEL))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void chatRoleReachesTheModelOnAnAllowedModel() {
        client.post().uri("/v1/chat/completions")
                .header("Authorization", basic(chatLogin, PASSWORD))
                .bodyValue(request(ALLOWED_MODEL))
                .exchange()
                .expectStatus().value(status -> assertTrue(status != 401 && status != 403,
                        "uwierzytelnienie i polityka powinny przepuścić żądanie, dostałem " + status));
    }

    @Test
    void agentRoleIsBlockedByPolicyForAModelOutsideItsList() {
        client.post().uri("/v1/chat/completions")
                .header("Authorization", basic(agentLogin, PASSWORD))
                .bodyValue(request(MODEL_OUTSIDE_AGENT_POLICY))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.blockedBy").isEqualTo("policy.model-access");
    }

    @Test
    void seededDemoAccountsLogIn() {
        client.post().uri("/v1/chat/completions")
                .header("Authorization", basic("agent-runner", "agent-runner-123"))
                .bodyValue(request(ALLOWED_MODEL))
                .exchange()
                .expectStatus().value(status -> assertTrue(status != 401 && status != 403,
                        "konto startowe agent-runner powinno przejść uwierzytelnienie, dostałem " + status));
        client.get().uri("/api/anything")
                .header("Authorization", basic("admin", "admin"))
                .exchange()
                .expectStatus().value(status -> assertTrue(status != 401 && status != 403,
                        "konto startowe admin powinno mieć rolę admin, dostałem " + status));
    }

    @Test
    void chatRoleCannotUseTheAdminApi() {
        client.get().uri("/api/anything")
                .header("Authorization", basic(chatLogin, PASSWORD))
                .exchange()
                .expectStatus().isForbidden();
    }

    private static ChatCompletionRequest request(String model) {
        return new ChatCompletionRequest(model, List.of(new ChatMessage("user", "hej")));
    }

    private static String basic(String login, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((login + ":" + password).getBytes());
    }
}
