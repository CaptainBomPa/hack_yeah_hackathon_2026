package pl.hackyeah.controllayer.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Wdrożenie na bazę z polityką sprzed dodania roli (tak było z rolą `codex` na Raspberry Pi):
 * policy.yaml nie wczytuje się ponownie, więc brakującą rolę dopisuje {@link PolicyStore#ensureRoles}.
 * Rola `extra` istnieje tylko na potrzeby testu i nie ma kont, więc da się ją usunąć z aktywnej polityki.
 */
@SpringBootTest(properties = {
        "policy.roles.extra.models[0]=gpt-5.5",
        "policy.roles.extra.budget.dailyTokens=500",
        "spring.datasource.url=jdbc:h2:mem:policy-role-sync;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
class PolicyRoleSyncTest {

    @Autowired
    private PolicyStore store;

    @Test
    void aRoleMissingInTheDatabasePolicyIsAddedFromPolicyYamlWithoutTouchingTheRest() {
        // "Stara" polityka w bazie: bez roli extra, bez jej modelu, z własnym ustawieniem admina.
        PolicyDocument seeded = store.current().document();
        var roles = new TreeMap<>(seeded.roles());
        roles.remove("extra");
        var chat = roles.get("chat");
        roles.put("chat", new PolicyDocument.RolePolicy(chat.models(), 777L, chat.rateLimit()));
        var models = seeded.models().stream().filter(m -> !m.tag().equals("gpt-5.5")).toList();
        store.apply(new PolicyDocument(roles, models, seeded.guards(), seeded.limits(), seeded.rateLimit()),
                null, "admin", "ui", "old policy without extra");
        long before = store.current().version();
        assertFalse(store.current().document().roles().containsKey("extra"));

        store.ensureRoles(Set.of("extra", "chat"));

        var active = store.current();
        assertEquals(before + 1, active.version());
        assertEquals(List.of("gpt-5.5"), active.document().roles().get("extra").models());
        assertEquals(500L, active.document().roles().get("extra").dailyTokens());
        assertTrue(active.document().models().contains(new PolicyDocument.ModelPolicy("gpt-5.5", true)));
        assertEquals(777L, active.document().roles().get("chat").dailyTokens(), "edycja admina zostaje");
        assertEquals("system", store.history().getFirst().getAuthor());

        // Nic nie brakuje = brak nowej wersji.
        store.ensureRoles(Set.of("extra", "chat"));
        assertEquals(before + 1, store.current().version());
    }

    @Test
    void aRoleUnknownAlsoInPolicyYamlStillFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> store.ensureRoles(Set.of("no-such-role")));
    }
}
