package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pl.hackyeah.controllayer.auth.AppUser;
import pl.hackyeah.controllayer.auth.AppUserRepository;
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.budget.BudgetGate.BudgetCheck;
import pl.hackyeah.controllayer.chat.ChatExecutionGate;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.chat.GuardedChatResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Delay delivery after a real H2 reservation, so cancellation hits the acquisition window. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:rate-budget-cancel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000",
                "policy.rate-limit.pipeline-timeout=1s", "policy.roles.chat.budget.daily-tokens=20000"})
@ActiveProfiles("rate-test")
@Import(RateLimitTestDatabase.class)
class RateLimitBudgetCancellationIntegrationTest {
    @Autowired ChatExecutionGate executionGate;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean BudgetGate budgetGate;

    private final CountDownLatch reserved = new CountDownLatch(1);
    private final CountDownLatch settled = new CountDownLatch(1);
    private final Sinks.One<Void> deliverReservation = Sinks.one();
    private final AtomicInteger operationCalls = new AtomicInteger();
    private AppUser user;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM budget_counter");
        jdbc.update("DELETE FROM rate_limit_lease");
        jdbc.update("DELETE FROM rate_limit_bucket");
        user = users.save(new AppUser("cancel-" + UUID.randomUUID(), passwords.encode("test-password"), "chat"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Mono<BudgetCheck> realCheck = (Mono<BudgetCheck>) invocation.callRealMethod();
            return realCheck.flatMap(check -> {
                reserved.countDown();
                return deliverReservation.asMono().thenReturn(check);
            });
        }).when(budgetGate).check(any(pl.hackyeah.controllayer.policy.ActivePolicy.class), eq("chat"), anyList());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Mono<Long> reconciliation = (Mono<Long>) invocation.callRealMethod();
            return reconciliation.doOnSuccess(used -> settled.countDown());
        }).when(budgetGate).reconcile(eq("chat"), any(BudgetCheck.class), anyLong());
    }

    @Test
    void cancellationBeforeReservationIsDeliveredReleasesItExactlyOnce() throws Exception {
        var request = request().subscribe();
        try {
            awaitReservation();
            request.dispose();
            deliverReservation.tryEmitEmpty();
            assertBudgetReleased();
        } finally {
            request.dispose();
            deliverReservation.tryEmitEmpty();
        }
    }

    @Test
    void timeoutBeforeReservationIsDeliveredReleasesItExactlyOnce() throws Exception {
        var request = request().toFuture();
        try {
            awaitReservation();
            var response = request.get(5, TimeUnit.SECONDS);
            assertEquals(503, response.getStatusCode().value());
            assertEquals("rate.pipeline-timeout", response.getBody().blockedBy());
            deliverReservation.tryEmitEmpty();
            assertBudgetReleased();
        } finally {
            request.cancel(true);
            deliverReservation.tryEmitEmpty();
        }
    }

    @Test
    void completedRequestChargesActualUsageOnlyOnce() throws Exception {
        var request = executionGate.execute(user.getLogin(), "chat", UUID.randomUUID().toString(), System.nanoTime(),
                List.of(new ChatMessage("user", "hello")), new ArrayList<>(), execution -> {
                    execution.upstreamStarted();
                    execution.upstreamFinished(7);
                    return execution.reconcile(7).map(used -> ResponseEntity.<GuardedChatResponse>ok().build());
                }).toFuture();
        try {
            awaitReservation();
            deliverReservation.tryEmitEmpty();
            assertEquals(200, request.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(0L, jdbc.queryForObject("SELECT reserved FROM budget_counter WHERE subject = 'role:chat'", Long.class));
            assertEquals(7L, jdbc.queryForObject("SELECT used_tokens FROM budget_counter WHERE subject = 'role:chat'", Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
            verify(budgetGate, times(1)).reconcile(eq("chat"), any(BudgetCheck.class), eq(7L));
        } finally {
            request.cancel(true);
            deliverReservation.tryEmitEmpty();
        }
    }

    @Test
    void acquisitionFailureIsPreservedAndDoesNotStartTheOperation() {
        var failure = new IllegalStateException("Budget store unavailable");
        doReturn(Mono.error(failure)).when(budgetGate).check(any(pl.hackyeah.controllayer.policy.ActivePolicy.class), eq("chat"), anyList());
        StepVerifier.create(request()).expectErrorMatches(error -> error == failure)
                .verify(java.time.Duration.ofSeconds(5));
        assertEquals(0, operationCalls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_lease", Integer.class));
        verify(budgetGate, never()).reconcile(anyString(), any(BudgetCheck.class), anyLong());
    }

    private Mono<ResponseEntity<GuardedChatResponse>> request() {
        return executionGate.execute(user.getLogin(), "chat", UUID.randomUUID().toString(), System.nanoTime(),
                List.of(new ChatMessage("user", "hello")), new ArrayList<>(), execution -> {
                    operationCalls.incrementAndGet();
                    return Mono.error(new AssertionError("Cancelled request must not reach the model"));
                });
    }

    private void awaitReservation() throws InterruptedException {
        assertTrue(reserved.await(5, TimeUnit.SECONDS), "Real budget reservation must be persisted");
        assertTrue(jdbc.queryForObject("SELECT reserved FROM budget_counter WHERE subject = 'role:chat'", Long.class) > 0);
    }

    private void assertBudgetReleased() throws InterruptedException {
        assertTrue(settled.await(5, TimeUnit.SECONDS), "Late reservation must be reconciled after cancellation");
        assertEquals(0L, jdbc.queryForObject("SELECT reserved FROM budget_counter WHERE subject = 'role:chat'", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT used_tokens FROM budget_counter WHERE subject = 'role:chat'", Long.class));
        assertEquals(0, operationCalls.get());
        verify(budgetGate, times(1)).reconcile(eq("chat"), any(BudgetCheck.class), eq(0L));
    }
}
