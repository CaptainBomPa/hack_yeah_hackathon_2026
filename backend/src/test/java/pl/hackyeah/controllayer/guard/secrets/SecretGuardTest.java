package pl.hackyeah.controllayer.guard.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Verdict;

class SecretGuardTest {

    private final SecretGuard guard = new SecretGuard(SecretScannerTest.SCANNER);
    private final GuardSettings defaults = new GuardSettings(true, Map.of());

    @Test
    void allowsBenignText() {
        assertInstanceOf(Verdict.Allow.class, check(SecretScannerTest.BENIGN_PL, defaults));
    }

    @Test
    void redactsOnlyTheSecretAndKeepsRawValueOutOfDetail() {
        var verdict = assertInstanceOf(Verdict.Redact.class,
                check("mój token " + SecretScannerTest.GITHUB_PAT + " nie działa", defaults));
        assertEquals("mój token [REDACTED:github-pat] nie działa", verdict.newText());
        assertEquals("github-pat×1", verdict.detail());
        assertFalse(verdict.detail().contains(SecretScannerTest.GITHUB_PAT));
    }

    @Test
    void blocksPrivateKeysByDefault() {
        var verdict = assertInstanceOf(Verdict.Block.class, check(SecretScannerTest.PRIVATE_KEY, defaults));
        assertEquals("secret private-key×1", verdict.reason());
    }

    @Test
    void monitorRulesReportWithoutChangingText() {
        var monitor = new GuardSettings(true, Map.of("monitorRules", "github-pat"));
        var verdict = assertInstanceOf(Verdict.Allow.class, check("token " + SecretScannerTest.GITHUB_PAT, monitor));
        assertEquals("monitor github-pat×1", verdict.detail());
    }

    @Test
    void blockAndDisableListsComeFromParams() {
        var redactKeys = new GuardSettings(true, Map.of("blockRules", ""));
        var redacted = assertInstanceOf(Verdict.Redact.class, check(SecretScannerTest.PRIVATE_KEY, redactKeys));
        assertTrue(redacted.newText().contains("[REDACTED:private-key]"));

        var disabled = new GuardSettings(true, Map.of("disabledRules", "github-pat, jwt"));
        assertInstanceOf(Verdict.Allow.class, check("token " + SecretScannerTest.GITHUB_PAT, disabled));

        var blockAws = new GuardSettings(true, Map.of("blockRules", Map.of("0", "aws-access-token")));
        assertInstanceOf(Verdict.Block.class, check("id=" + SecretScannerTest.AWS_KEY, blockAws));
    }

    private Verdict check(String text, GuardSettings settings) {
        return guard.check(new GuardContext("req-1", text, null, null), settings);
    }
}
