package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.chat.ChatCompletionRequest;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.chat.GuardedChatResponse;
import pl.hackyeah.controllayer.policy.PolicyProperties;

/** Real server, Spring Security, database transactions and audit. Only the LLM is a HTTP stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("rate-test")
@Import(RateLimitTestDatabase.class)
class RateLimitIntegrationTest {
    private static final String PASSWORD = "integration-password";
    private static final AtomicInteger calls = new AtomicInteger();
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch finish;
    private static final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer upstream = createUpstream();

    private static HttpServer createUpstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                var started = entered;
                var end = finish;
                if (started != null) started.countDown();
                if (end != null) {
                    try {
                        if (!end.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test upstream not released");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                var bytes = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @DynamicPropertySource
    static void upstreamAddress(DynamicPropertyRegistry properties) {
        properties.add("control-layer.models[0].tag", () -> "test-model");
        properties.add("control-layer.models[0].enabled", () -> "true");
        properties.add("control-layer.models[0].base-url", () -> "http://localhost:" + upstream.getAddress().getPort());
    }

    @AfterAll
    static void closeUpstream() { upstream.stop(0); executor.shutdownNow(); }

    @LocalServerPort int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimitStore store;
    @Autowired PolicyProperties policy;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private WebTestClient client;
    private AppUser user;

    @BeforeEach
    void setup() {
        entered = null;
        finish = null;
        calls.set(0);
        jdbc.update("DELETE FROM rate_limit_lease");
        jdbc.update("DELETE FROM rate_limit_bucket");
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15)).build();
        user = account("chat");
    }

    private AppUser account(String role) {
        return users.save(new AppUser("test-" + UUID.randomUUID(), passwords.encode(PASSWORD), role));
    }

    private WebTestClient.ResponseSpec post(AppUser account) {
        return client.post().uri("/v1/chat/completions")
                .headers(headers -> headers.setBasicAuth(account.getLogin(), PASSWORD))
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hello"))))
                .exchange();
    }

    @Test
    void policyReloadChangesAdmissionWhileExistingSnapshotKeepsItsLimits() {
        var policies = context.getBean(pl.hackyeah.controllayer.policy.PolicyStore.class);
        var gate = context.getBean(RateLimitGate.class);
        var original = policies.current();
        var doc = original.document();
        var disabled = new RateLimitSettings("off", null, null, null, null, null);
        try {
            var changed = new pl.hackyeah.controllayer.policy.PolicyDocument(
                    doc.roles(), doc.models(), doc.guards(), doc.limits(), disabled);
            policies.apply(changed, original.version(), "test", "test", "disable admission");
            for (int i = 0; i < 3; i++) {
                post(user).expectStatus().isOk().expectBody()
                        .jsonPath("$.policyVersion").isEqualTo(policies.current().version());
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_bucket", Integer.class));
            var permit = gate.acquire(original, user.getLogin(), "chat", UUID.randomUUID().toString()).block();
            assertEquals("block", permit.limits().mode());
            assertEquals(2, permit.limits().burstCapacity());
            gate.release(permit).block();
        } finally {
            policies.apply(doc, policies.current().version(), "test", "test", "restore admission");
        }
    }

    @Test
    void newAccountHasConfiguredBurstAndAuditedDenialWithoutCallingModel() {
        assertEquals("block", policy.rateLimit().mode());
        post(user).expectStatus().isOk();
        post(user).expectStatus().isOk();
        var denied = post(user).expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After").expectBody(GuardedChatResponse.class).returnResult();
        var body = denied.getResponseBody();
        assertEquals("rate.requests", body.blockedBy());
        assertEquals("block", body.action());
        assertTrue(Integer.parseInt(denied.getResponseHeaders().getFirst("Retry-After")) >= 1);
        assertEquals(2, calls.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE request_id = ? AND http_status = 429",
                Integer.class, body.requestId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
    }

    @Test
    void basicAndSessionShareAccountBucketButDifferentAccountsDoNot() {
        var login = client.post().uri("/api/auth/login")
                .bodyValue(Map.of("login", user.getLogin(), "password", PASSWORD)).exchange()
                .expectStatus().isOk().returnResult(Void.class);
        String session = login.getResponseCookies().getFirst("SESSION").getValue();
        post(user).expectStatus().isOk();
        client.post().uri("/v1/chat/completions").cookie("SESSION", session)
                .bodyValue(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hi"))))
                .exchange().expectStatus().isOk();
        post(user).expectStatus().isEqualTo(429).expectBody().jsonPath("$.blockedBy").isEqualTo("rate.requests");
        post(account("chat")).expectStatus().isOk();
        assertEquals(3, calls.get());
    }

    @Test
    void adminIsAlsoLimitedAndRoleOverridesInheritProtection() {
        var admin = account("admin");
        post(admin).expectStatus().isOk();
        post(admin).expectStatus().isOk();
        post(admin).expectStatus().isEqualTo(429);
        var agent = policy.rateLimit().forRole(policy.roles().get("agent").rateLimit());
        assertEquals(30, agent.requestsPerMinute());
        assertEquals(2, agent.burstCapacity());
        assertEquals(1, agent.maxConcurrentPerUser());
        assertEquals("block", agent.mode());
    }

    @Test
    void concurrentRequestsAreRejectedAndSlotIsReleasedAfterCompletion() throws Exception {
        entered = new CountDownLatch(1);
        finish = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> post(user).expectStatus().isOk());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "First request must reach upstream");
            post(user).expectStatus().isEqualTo(429).expectBody()
                    .jsonPath("$.blockedBy").isEqualTo("rate.concurrent.user");
            var other = account("chat");
            post(other).expectStatus().isEqualTo(429).expectBody()
                    .jsonPath("$.blockedBy").isEqualTo("rate.concurrent.global");
            assertEquals(1, calls.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
            assertEquals(1.0, jdbc.queryForObject("SELECT tokens FROM rate_limit_bucket WHERE user_id = ?", Double.class, user.getId()));
            finish.countDown();
            first.get(10, TimeUnit.SECONDS);
            post(other).expectStatus().isOk();
        } finally {
            finish.countDown();
            first.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredLeaseIsIgnoredWithoutScheduledCleanupAndOldReleaseCannotDeleteNewLease() {
        var stale = UUID.randomUUID();
        assertTrue(store.acquireForLogin(user.getLogin(), stale, policy.rateLimit()).allowed());
        jdbc.update("UPDATE rate_limit_lease SET expires_at = ? WHERE request_id = ?",
                java.sql.Timestamp.from(java.time.Instant.EPOCH), stale);
        var fresh = UUID.randomUUID();
        assertTrue(store.acquireForLogin(user.getLogin(), fresh, policy.rateLimit()).allowed());
        store.release(stale);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease WHERE request_id = ?", Integer.class, fresh));
        store.release(fresh);
        store.release(fresh);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
    }

    @Test
    void refillIsCappedAtBurstAndMaintenancePreservesActiveLease() {
        UUID first = UUID.randomUUID();
        assertTrue(store.acquireForLogin(user.getLogin(), first, policy.rateLimit()).allowed());
        assertEquals(0, store.purgeExpired());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
        store.release(first);
        jdbc.update("UPDATE rate_limit_bucket SET tokens = 0, updated_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)), user.getId());
        UUID next = UUID.randomUUID();
        assertTrue(store.acquireForLogin(user.getLogin(), next, policy.rateLimit()).allowed());
        assertEquals(1.0, jdbc.queryForObject("SELECT tokens FROM rate_limit_bucket WHERE user_id = ?", Double.class, user.getId()));
        store.release(next);
        UUID last = UUID.randomUUID();
        assertTrue(store.acquireForLogin(user.getLogin(), last, policy.rateLimit()).allowed());
        store.release(last);
        assertEquals("rate.requests", store.acquireForLogin(user.getLogin(), UUID.randomUUID(), policy.rateLimit()).reason());
    }

    @Test
    void twoIndependentStoresAtomicallyAdmitOnlyOneGlobalRequest() throws Exception {
        var otherStore = new RateLimitStore(users, context.getBean(RateLimitLockRepository.class),
                context.getBean(RateLimitBucketRepository.class), context.getBean(RateLimitLeaseRepository.class),
                context.getBean(RateLimitDatabaseSupport.class));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var other = account("chat");
        java.util.function.BiFunction<RateLimitStore, String, Boolean> attempt = (instance, login) -> {
            ready.countDown();
            try { assertTrue(go.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
            return transaction.execute(status -> instance.acquireForLogin(login, UUID.randomUUID(), policy.rateLimit()).allowed());
        };
        var first = CompletableFuture.supplyAsync(() -> attempt.apply(store, user.getLogin()));
        var second = CompletableFuture.supplyAsync(() -> attempt.apply(otherStore, other.getLogin()));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
    }

    @Test
    void unavailableStoreFailsClosedBeforeCallingModel() {
        jdbc.update("DELETE FROM rate_limit_lock");
        try {
            post(user).expectStatus().isEqualTo(503).expectHeader().valueEquals("Retry-After", "1")
                    .expectBody().jsonPath("$.blockedBy").isEqualTo("rate.store");
            assertEquals(0, calls.get());
        } finally { jdbc.update("INSERT INTO rate_limit_lock (id) VALUES (1)"); }
    }
}
