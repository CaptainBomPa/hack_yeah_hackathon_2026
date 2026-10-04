package pl.hackyeah.controllayer.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.chat.ControlTrace;

/**
 * Audit log na prawdziwej bazie (H2, profil local): łańcuch HMAC, wykrywanie manipulacji,
 * sanitizacja pól niezaufanych i API `/api/audit/**` za rolą ADMIN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditIntegrationTest {

    private static final String PASSWORD = "haslo-testowe-123";

    @LocalServerPort
    private int port;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

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
    void chainIsValidAndDetectsTampering() {
        String first = append("allow", null, "sess-a");
        append("block", "model.allowlist", "sess-a");
        var initial = auditService.verify();
        assertTrue(initial.valid(), () -> "łańcuch powinien być poprawny: " + initial);

        long seq = auditService.find(first).orElseThrow().getSeq();
        jdbc.update("UPDATE audit_event SET action = 'block' WHERE seq = ?", seq);
        try {
            var result = auditService.verify();
            assertFalse(result.valid());
            assertEquals(seq, result.brokenAtSeq());
        } finally {
            jdbc.update("UPDATE audit_event SET action = 'allow' WHERE seq = ?", seq);
        }
        assertTrue(auditService.verify().valid());
    }

    @Test
    void untrustedFieldsAreSanitized() {
        String requestId = append("allow", null, "x\r\n2026-10-03 action=ALLOW\u001B[31m‮");
        String sessionId = auditService.find(requestId).orElseThrow().getSessionId();
        assertFalse(sessionId.contains("\n") || sessionId.contains("\r") || sessionId.contains("\u001B")
                || sessionId.contains("‮"), "znaki sterujące muszą zostać zneutralizowane: " + sessionId);
    }

    @Test
    void auditApiRequiresAdmin() {
        client.get().uri("/api/audit/events").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/audit/events").header("Authorization", basic(chatLogin))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adminListsFiltersAndExports() {
        String sessionId = "sess-" + UUID.randomUUID();
        String requestId = append("block", "=cmd|' /C calc'!A0", sessionId);

        client.get().uri(uri -> uri.path("/api/audit/events").queryParam("sessionId", sessionId).build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].requestId").isEqualTo(requestId)
                .jsonPath("$.items[0].trace[0].policy").isEqualTo("model.allowlist");

        client.get().uri("/api/audit/events/" + requestId)
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.sessionId").isEqualTo(sessionId);

        byte[] csv = client.get()
                .uri(uri -> uri.path("/api/audit/export").queryParam("format", "csv")
                        .queryParam("sessionId", sessionId).build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Disposition", "attachment;.*\\.csv.*")
                .expectBody().returnResult().getResponseBody();
        assertNotNull(csv);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"'=cmd|' /C calc'!A0\""), "komórka zaczynająca się od = musi dostać prefiks ': " + text);

        client.get().uri("/api/audit/verify")
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(true);
    }

    @Test
    void filtersByAnyOfSeveralValuesAndBySessionFragment() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String allowed = append("allow", null, "Sess-" + marker + "-a");
        String redacted = append("redact", null, "sess-" + marker + "-b");
        append("block", "model.allowlist", "sess-" + marker + "-c");

        client.get().uri(uri -> uri.path("/api/audit/events")
                        .queryParam("sessionId", marker.toUpperCase())
                        .queryParam("action", "allow")
                        .queryParam("action", "redact")
                        .build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].requestId").isEqualTo(redacted)
                .jsonPath("$.items[1].requestId").isEqualTo(allowed);

        // % z wejścia to zwykły znak, nie wildcard LIKE.
        client.get().uri(uri -> uri.path("/api/audit/events").queryParam("sessionId", "%").build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(0);

        client.get().uri("/api/audit/facets")
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.actions[?(@ == 'redact')]").exists()
                .jsonPath("$.models[?(@ == 'test-model')]").exists()
                .jsonPath("$.blockedBy[?(@ == 'model.allowlist')]").exists()
                .jsonPath("$.principals[?(@ == 'tester')]").exists();
    }

    @Test
    void reasonMatchesBothTheBlockingAndTheRedactingControl() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String blocked = append("block", "SEC-GITLEAKS", "sess-" + marker);
        String redacted = UUID.randomUUID().toString();
        auditService.append(new AuditEntry(redacted, Instant.now(), "tester", "chat", "sess-" + marker, "test-model",
                "redact", null, 200, 12, 3, 2, 3, List.of(
                        new ControlTrace("input.history", "deterministic", "redact", 0, "1 cleaned"),
                        new ControlTrace("PII-RECOGNIZERS", "deterministic", "redact", 1, "PII-001/PL_PESEL×1"),
                        new ControlTrace("SEC-GITLEAKS", "deterministic", "allow", 1, null)), 1L));
        // allow z redakcją tylko w historii — to nie jest powód decyzji.
        append("allow", null, "sess-" + marker);

        client.get().uri(uri -> uri.path("/api/audit/events").queryParam("sessionId", marker)
                        .queryParam("reason", "PII-RECOGNIZERS").build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].requestId").isEqualTo(redacted);

        // SEC-GITLEAKS zablokował jedno żądanie; w redact był tylko "allow" — nie pasuje.
        client.get().uri(uri -> uri.path("/api/audit/events").queryParam("sessionId", marker)
                        .queryParam("reason", "SEC-GITLEAKS").build())
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].requestId").isEqualTo(blocked);

        client.get().uri("/api/audit/facets")
                .header("Authorization", basic(adminLogin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reasons[?(@ == 'PII-RECOGNIZERS')]").exists()
                .jsonPath("$.reasons[?(@ == 'SEC-GITLEAKS')]").exists()
                .jsonPath("$.reasons[?(@ == 'input.history')]").doesNotExist();
    }

    private String append(String action, String blockedBy, String sessionId) {
        String requestId = UUID.randomUUID().toString();
        auditService.append(new AuditEntry(requestId, Instant.now(), "tester", "chat", sessionId, "test-model",
                action, blockedBy, "block".equals(action) ? 403 : 200, 12, 3, 2, 1,
                List.of(new ControlTrace("model.allowlist", "deterministic", action, 1, null)), 1L));
        return requestId;
    }

    private static String basic(String login) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
