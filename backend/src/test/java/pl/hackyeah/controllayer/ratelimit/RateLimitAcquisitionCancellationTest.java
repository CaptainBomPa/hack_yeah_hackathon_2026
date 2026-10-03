package pl.hackyeah.controllayer.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.hackyeah.controllayer.budget.BudgetGate;
import pl.hackyeah.controllayer.chat.ChatExecutionGate;
import pl.hackyeah.controllayer.chat.ChatMessage;
import pl.hackyeah.controllayer.policy.PolicyProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitAcquisitionCancellationTest {
    private final RateLimitStore store = mock(RateLimitStore.class);
    private final BudgetGate budget = mock(BudgetGate.class);
    private final CountDownLatch acquiring = new CountDownLatch(1);
    private final CountDownLatch deliver = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final UUID requestId = UUID.randomUUID();

    private RateLimitGate delayedGate() {
        when(store.acquireForLogin(eq("tester"), eq(requestId), any())).thenAnswer(invocation -> {
            acquiring.countDown();
            assertTrue(deliver.await(10, TimeUnit.SECONDS));
            return new RateLimitStore.Decision(true, null, 0, true);
        });
        doAnswer(invocation -> {
            released.countDown();
            return null;
        }).when(store).release(requestId);
        return new RateLimitGate(new PolicyProperties(Map.of()), store, new SimpleMeterRegistry());
    }

    @Test
    void cancellationDuringAdmissionReleasesTheLatePermitWithoutReservingBudget() throws Exception {
        var gate = new ChatExecutionGate(delayedGate(), budget);
        var request = gate.execute("tester", "chat", requestId.toString(), System.nanoTime(),
                List.of(new ChatMessage("user", "hello")), new ArrayList<>(),
                execution -> Mono.error(new AssertionError("Cancelled operation must not run"))).subscribe();
        try {
            assertTrue(acquiring.await(5, TimeUnit.SECONDS));
            request.dispose();
            deliver.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
            verify(store, times(1)).release(requestId);
            verifyNoInteractions(budget);
        } finally {
            request.dispose();
            deliver.countDown();
        }
    }

    @Test
    void admissionTimeoutReturnsWithoutWaitingForTheStoreAndReleasesItsLatePermit() throws Exception {
        var gate = delayedGate();
        try {
            StepVerifier.create(gate.acquire("tester", "chat", requestId.toString()))
                    .assertNext(permit -> {
                        assertFalse(permit.decision().allowed());
                        assertEquals("rate.store", permit.decision().reason());
                        assertEquals(1, deliver.getCount());
                    }).expectComplete().verify(Duration.ofSeconds(6));
            deliver.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
            verify(store, times(1)).release(requestId);
        } finally {
            deliver.countDown();
        }
    }
}
