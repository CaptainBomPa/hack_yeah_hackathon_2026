package pl.hackyeah.controllayer.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.audit.AuditEntry;
import pl.hackyeah.controllayer.audit.AuditService;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.chat.ControlTrace;

/** Dashboard liczony z audytu: przyrosty liczników po dopisaniu znanych rekordów + dostęp tylko dla admina. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardIntegrationTest {

    private static final String PASSWORD = "haslo-testowe-123";

    @LocalServerPort
    private int port;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void countsActionsTokensControlsAndPerModelStatsFromTheAudit() {
        String model = "dash-model-" + UUID.randomUUID();
        DashboardView before = dashboardService.compute("1h");

        append("allow", null, model, 100, 10, 5, List.of(trace("model.allowlist", "allow")));
        append("redact", null, model, 300, 20, 7, List.of(trace("PII-001", "redact")));
        append("block", "model.allowlist", model, 0, null, null, List.of(trace("model.allowlist", "block")));

        DashboardView after = dashboardService.compute("1h");

        assertEquals(before.totals().requests() + 3, after.totals().requests());
        assertEquals(before.totals().byAction().get("block") + 1, after.totals().byAction().get("block"));
        assertEquals(before.totals().byAction().get("redact") + 1, after.totals().byAction().get("redact"));
        assertEquals(before.tokens().prompt() + 30, after.tokens().prompt());
        assertTrue(after.controls().stream().anyMatch(c -> c.policy().equals("PII-001") && c.action().equals("redact")),
                "redakcja bez blockedBy też ma trafić do top kontroli (z trace)");

        var modelStats = after.models().stream().filter(m -> m.model().equals(model)).findFirst().orElseThrow();
        assertEquals(3, modelStats.requests());
        assertEquals(1, modelStats.blocked());
        assertEquals(42, modelStats.tokens());

        long bucketTotal = after.timeline().stream()
                .mapToLong(b -> b.byAction().values().stream().mapToLong(Long::longValue).sum())
                .sum();
        assertEquals(after.totals().requests(), bucketTotal, "suma słupków = liczba żądań w oknie");
        assertEquals(12, after.timeline().size(), "okno 1h = 12 słupków po 5 min");
        assertFalse(after.truncated());
    }

    @Test
    void percentileIsNearestRank() {
        var sorted = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertEquals(50, DashboardService.percentile(sorted, 50));
        assertEquals(100, DashboardService.percentile(sorted, 95));
        assertEquals(10, DashboardService.percentile(List.of(10L), 95));
    }

    @Test
    void dashboardApiIsAdminOnly() {
        String admin = "admin-" + UUID.randomUUID();
        String chat = "chat-" + UUID.randomUUID();
        users.save(new AppUser(admin, passwordEncoder.encode(PASSWORD), "admin"));
        users.save(new AppUser(chat, passwordEncoder.encode(PASSWORD), "chat"));
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        client.get().uri("/api/dashboard?window=24h").header("Authorization", basic(chat))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/dashboard?window=24h").header("Authorization", basic(admin))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.window").isEqualTo("24h")
                .jsonPath("$.timeline.length()").isEqualTo(24)
                .jsonPath("$.budgets[?(@.role == 'chat')].cap").isEqualTo(20000);
    }

    private void append(String action, String blockedBy, String model, long latencyMs, Integer prompt,
            Integer completion, List<ControlTrace> trace) {
        auditService.append(new AuditEntry(UUID.randomUUID().toString(), Instant.now(), "dash-user", "chat",
                "dash-session", model, action, blockedBy, "block".equals(action) ? 403 : 200, latencyMs,
                prompt, completion, 1, trace, 1L));
    }

    private static ControlTrace trace(String policy, String action) {
        return new ControlTrace(policy, "deterministic", action, 1, null);
    }

    private static String basic(String login) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
