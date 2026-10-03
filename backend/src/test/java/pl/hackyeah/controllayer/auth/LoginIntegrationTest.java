package pl.hackyeah.controllayer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Logowanie przeglądarki sesją (`/api/auth/**`) obok HTTP Basic dla maszyn. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIntegrationTest {

    private static final String PASSWORD = "haslo-testowe-123";

    @LocalServerPort
    private int port;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;
    private String adminLogin;
    private String chatLogin;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        adminLogin = "admin-" + UUID.randomUUID();
        chatLogin = "chat-" + UUID.randomUUID();
        users.save(new AppUser(adminLogin, passwordEncoder.encode(PASSWORD), "admin"));
        users.save(new AppUser(chatLogin, passwordEncoder.encode(PASSWORD), "chat"));
    }

    @Test
    void loginCreatesASessionUsableForTheApiAndLogoutEndsIt() {
        String session = login(adminLogin, PASSWORD);

        client.get().uri("/api/auth/me").cookie("SESSION", session)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.login").isEqualTo(adminLogin)
                .jsonPath("$.role").isEqualTo("admin");
        client.get().uri("/api/audit/verify").cookie("SESSION", session)
                .exchange().expectStatus().isOk();

        client.post().uri("/api/auth/logout").cookie("SESSION", session)
                .exchange().expectStatus().isNoContent();
        client.get().uri("/api/auth/me").cookie("SESSION", session)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void chatRoleCanSeeItselfButNotTheAdminApi() {
        String session = login(chatLogin, PASSWORD);
        client.get().uri("/api/auth/me").cookie("SESSION", session)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("chat");
        client.get().uri("/api/audit/events").cookie("SESSION", session)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void wrongPasswordIsRejectedWithoutABrowserPopup() {
        client.post().uri("/api/auth/login")
                .bodyValue(Map.of("login", adminLogin, "password", "zle-haslo"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_credentials");

        var result = client.get().uri("/api/auth/me").exchange()
                .expectStatus().isUnauthorized()
                .returnResult(Void.class);
        assertNull(result.getResponseHeaders().getFirst("WWW-Authenticate"),
                "401 nie może wywoływać natywnego okienka logowania przeglądarki");
    }

    @Test
    void tooManyFailedAttemptsAreThrottledEvenWithTheRightPassword() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            client.post().uri("/api/auth/login")
                    .bodyValue(Map.of("login", chatLogin, "password", "zle-haslo-" + i))
                    .exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/auth/login")
                .bodyValue(Map.of("login", chatLogin, "password", PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void basicAuthStillWorksWithoutCreatingASession() {
        var result = client.get().uri("/api/auth/me")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((adminLogin + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Void.class);
        assertNull(result.getResponseCookies().getFirst("SESSION"), "Basic Auth ma być bezstanowy");
    }

    private String login(String login, String password) {
        var result = client.post().uri("/api/auth/login")
                .bodyValue(Map.of("login", login, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Void.class);
        ResponseCookie cookie = result.getResponseCookies().getFirst("SESSION");
        assertNotNull(cookie, "logowanie powinno ustawić ciasteczko sesji");
        assertTrue(cookie.isHttpOnly(), "ciasteczko sesji musi być HttpOnly");
        assertEquals("Lax", cookie.getSameSite(), "wyłączenie CSRF opiera się na SameSite=Lax");
        return cookie.getValue();
    }
}
