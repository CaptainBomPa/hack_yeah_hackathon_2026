package pl.hackyeah.controllayer.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.guard.GuardChainResult;
import pl.hackyeah.controllayer.guard.Stage;

class GuardRejectionTest {
    private GuardChainResult blocked(String guard, String detail) {
        return new GuardChainResult(GuardChainResult.Action.BLOCK, "private input", guard,
                List.of(new ControlTrace(guard, "deterministic", "block", 1, detail)));
    }

    @Test void piiClassificationIsVisibleWithoutReflectingSensitiveExplanations() {
        var rejection = GuardRejection.from(blocked("PII-RECOGNIZERS",
                "PII-001/PL_PESEL×1 [context 'private-value']"), Stage.INPUT, false);
        assertEquals(400, rejection.status());
        assertEquals("policy.input-blocked", rejection.code());
        assertEquals(List.of("PII-001/PL_PESEL"), rejection.detections());
        assertTrue(rejection.message().contains("PL_PESEL"));
        assertFalse(rejection.toString().contains("private-value"));
        assertFalse(rejection.toString().contains("private input"));
    }

    @Test void semanticFailureIsUnavailableWhileDetectedAttackIsContentRejection() {
        var timeout = GuardRejection.from(blocked("SEM-001",
                "incomplete semantic check (classifier:timeout) (fail-closed)"), Stage.INPUT, false);
        assertEquals(503, timeout.status());
        assertEquals("check_timeout", timeout.reason());
        assertEquals("policy.check-unavailable", timeout.code());
        var attack = GuardRejection.from(blocked("SEM-001", "classifier score=0.9999 threshold=0.9000"), Stage.INPUT, false);
        assertEquals(400, attack.status());
        assertEquals("prompt_injection", attack.reason());
    }

    @Test void outputRedactionKeepsGuardIdentityWithoutExposingContent() {
        var result = new GuardChainResult(GuardChainResult.Action.REDACT, "private", null,
                List.of(new ControlTrace("SEC-GITLEAKS", "deterministic", "redact", 1, "private secret")));
        var rejection = GuardRejection.from(result, Stage.OUTPUT, true);
        assertEquals(400, rejection.status());
        assertEquals("policy.output-blocked", rejection.code());
        assertEquals("SEC-GITLEAKS", rejection.guard());
        assertEquals("stream_redaction_required", rejection.reason());
        assertFalse(rejection.toString().contains("private"));
    }
}
