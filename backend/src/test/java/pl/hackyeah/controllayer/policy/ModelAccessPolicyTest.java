package pl.hackyeah.controllayer.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelAccessPolicyTest {

    private final ModelAccessPolicy policy = new ModelAccessPolicy(new PolicyProperties(Map.of(
            "admin", new PolicyProperties.RolePolicy(List.of("*")),
            "chat", new PolicyProperties.RolePolicy(List.of("model-a")))));

    @Test
    void wildcardRoleMayUseAnyModel() {
        assertTrue(policy.allowsModel("admin", "model-a"));
        assertTrue(policy.allowsModel("admin", "anything-else"));
    }

    @Test
    void roleMayUseOnlyListedModels() {
        assertTrue(policy.allowsModel("chat", "model-a"));
        assertFalse(policy.allowsModel("chat", "model-b"));
    }

    @Test
    void unknownRoleIsDenied() {
        assertFalse(policy.allowsModel("agent", "model-a"));
    }

    @Test
    void emptyPolicyDeniesEverything() {
        var empty = new ModelAccessPolicy(new PolicyProperties(null));
        assertFalse(empty.allowsModel("admin", "model-a"));
    }
}
