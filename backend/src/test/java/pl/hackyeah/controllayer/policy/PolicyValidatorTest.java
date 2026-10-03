package pl.hackyeah.controllayer.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorTest {

    private static final PolicyValidator.Context CTX = new PolicyValidator.Context(
            Set.of("PII-RECOGNIZERS", "SEM-001"),
            Set.of("model-a", "model-b"),
            Set.of("PII-001", "PII-007"),
            Map.of("chat", 3L, "admin", 1L));

    private static PolicyDocument valid() {
        return new PolicyDocument(
                Map.of("admin", new PolicyDocument.RolePolicy(List.of("*"), null),
                        "chat", new PolicyDocument.RolePolicy(List.of("model-a"), 20_000L)),
                List.of(new PolicyDocument.ModelPolicy("model-a", true), new PolicyDocument.ModelPolicy("model-b", false)),
                Map.of("PII-RECOGNIZERS", new PolicyDocument.GuardPolicy(true, 100,
                                Map.of("threshold", 0.5, "blockRecognizers", List.of("PII-007"))),
                        "SEM-001", new PolicyDocument.GuardPolicy(false, 200,
                                Map.of("blockThreshold", 0.998, "timeoutMs", 4000, "failureMode", "closed"))),
                new PolicyDocument.Limits(4000, 1024));
    }

    private static List<String> paths(PolicyDocument doc) {
        return PolicyValidator.validate(doc, CTX).stream().map(PolicyValidator.Error::path).toList();
    }

    @Test
    void acceptsAValidPolicy() {
        assertEquals(List.of(), PolicyValidator.validate(valid(), CTX));
    }

    @Test
    void rejectsUnknownModelInARole() {
        var doc = valid();
        var roles = new java.util.HashMap<>(doc.roles());
        roles.put("chat", new PolicyDocument.RolePolicy(List.of("model-a", "gpt-9"), null));
        // Lista modeli roli jest sortowana przy normalizacji, więc "gpt-9" ląduje przed "model-a".
        assertTrue(paths(new PolicyDocument(roles, doc.models(), doc.guards(), doc.limits()))
                .contains("roles.chat.models[0]"));
    }

    @Test
    void refusesToRemoveARoleThatHasAccountsOrTheAdminRole() {
        var doc = valid();
        var errors = paths(new PolicyDocument(Map.of(), doc.models(), doc.guards(), doc.limits()));
        assertTrue(errors.contains("roles"), "admin is required");
        assertTrue(errors.contains("roles.chat"), "chat has 3 accounts");
    }

    @Test
    void checksGuardParameters() {
        var doc = valid();
        var guards = Map.of(
                "PII-RECOGNIZERS", new PolicyDocument.GuardPolicy(true, 100, Map.of(
                        "threshold", 1.5,
                        "blockRecognizers", List.of("PII-001"),
                        "monitorRecognizers", List.of("PII-001", "PII-999"))),
                "SEM-001", new PolicyDocument.GuardPolicy(true, 0, Map.of("failureMode", "maybe", "typo", 1)),
                "NOPE-1", new PolicyDocument.GuardPolicy(true, 100, Map.of()));
        var errors = paths(new PolicyDocument(doc.roles(), doc.models(), guards, doc.limits()));
        assertTrue(errors.contains("guards.PII-RECOGNIZERS.params.threshold"));
        assertTrue(errors.contains("guards.PII-RECOGNIZERS.params.monitorRecognizers"), "unknown + duplicate recognizer");
        assertTrue(errors.contains("guards.SEM-001.order"));
        assertTrue(errors.contains("guards.SEM-001.params.failureMode"));
        assertTrue(errors.contains("guards.SEM-001.params.typo"));
        assertTrue(errors.contains("guards.NOPE-1"));
    }

    @Test
    void rejectsModelsOutsideTheCatalogAndBadLimits() {
        var doc = valid();
        var errors = paths(new PolicyDocument(doc.roles(),
                List.of(new PolicyDocument.ModelPolicy("other", true)), doc.guards(),
                new PolicyDocument.Limits(0, 500_000)));
        assertTrue(errors.contains("models[0]"));
        assertTrue(errors.contains("limits.maxInputTokens"));
        assertTrue(errors.contains("limits.maxOutputTokens"));
    }

    @Test
    void normalizesYamlStyleParamsSoTheHashIsStable() {
        var fromYaml = new PolicyDocument.GuardPolicy(true, 100,
                Map.of("threshold", "0.5", "blockRecognizers", "PII-007, PII-001", "pack", "classpath:x.yaml"));
        assertEquals(0.5, fromYaml.params().get("threshold"));
        assertEquals(List.of("PII-001", "PII-007"), fromYaml.params().get("blockRecognizers"));
        assertTrue(!fromYaml.params().containsKey("pack"), "file location is deployment config, not policy");
        assertEquals(PolicyJson.hash(valid()), PolicyJson.hash(PolicyJson.fromJson(PolicyJson.toJson(valid()))));
        assertEquals(PolicyJson.hash(valid()), PolicyJson.hash(PolicyJson.fromYaml(PolicyJson.toYaml(valid()))));
    }

    @Test
    void yamlImportRefusesUnsafeTags() {
        var error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> PolicyJson.fromYaml("policy: !!java.net.URL [\"http://evil\"]"));
        assertTrue(error.getMessage() != null);
    }
}
