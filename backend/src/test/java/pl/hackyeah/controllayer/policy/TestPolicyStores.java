package pl.hackyeah.controllayer.policy;

import java.util.List;
import pl.hackyeah.controllayer.budget.BudgetLimitsProperties;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;

/**
 * Bridges {@code pl.hackyeah.controllayer.chat.bdd} (the BDD suite) to the real
 * {@link PolicyStore} — {@code PolicyStore.load()} is package-private, so seeding has to happen
 * from inside this package. Gives the BDD suite a real, seeded, hot-reloadable policy store
 * (same {@link PolicyValidator} and version-bumping logic as production) without Spring or a
 * database: {@link FakeRepositories} hold the versions/accounts in memory instead.
 */
public final class TestPolicyStores {

    private TestPolicyStores() {}

    public static PolicyStore seeded(List<Guard> guards, ModelCatalogProperties catalog,
            PolicyProperties seedPolicy, GuardProperties seedGuards, BudgetLimitsProperties seedLimits) {
        var store = new PolicyStore(FakeRepositories.policyVersions(), FakeRepositories.appUsers(),
                guards, catalog, seedPolicy, seedGuards, seedLimits);
        store.load();
        return store;
    }
}
