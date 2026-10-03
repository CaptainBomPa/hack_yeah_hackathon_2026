package pl.hackyeah.controllayer.policy;

import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import pl.hackyeah.controllayer.auth.AppUserRepository;

/**
 * Minimal in-memory stand-ins for the two Spring Data repositories {@link PolicyStore} depends
 * on, so the BDD suite can exercise the real {@code PolicyStore}/{@code PolicyValidator}
 * hot-reload logic (docs/policy-management-plan.md) without booting a Spring context or a real
 * database. Only the methods {@code PolicyStore} actually calls are implemented; anything else
 * throws — nothing else calls them in these scenarios.
 */
final class FakeRepositories {

    private FakeRepositories() {}

    static PolicyVersionRepository policyVersions() {
        var rows = new ConcurrentHashMap<Long, PolicyVersion>();
        return (PolicyVersionRepository) Proxy.newProxyInstance(
                PolicyVersionRepository.class.getClassLoader(),
                new Class<?>[] {PolicyVersionRepository.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "findTopByOrderByVersionDesc" -> rows.values().stream()
                                .max(Comparator.comparingLong(PolicyVersion::getVersion));
                        case "findAllByOrderByVersionDesc" -> rows.values().stream()
                                .sorted(Comparator.comparingLong(PolicyVersion::getVersion).reversed())
                                .toList();
                        case "findById" -> Optional.ofNullable(rows.get((Long) args[0]));
                        case "saveAndFlush", "save" -> {
                            var entity = (PolicyVersion) args[0];
                            rows.put(entity.getVersion(), entity);
                            yield entity;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "FakePolicyVersionRepository";
                        default -> throw new UnsupportedOperationException(
                                "not needed by PolicyStore in tests: " + method.getName());
                    };
                });
    }

    /** No accounts are ever tracked, so the "can't remove a role with accounts" rule never fires. */
    static AppUserRepository appUsers() {
        return (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(),
                new Class<?>[] {AppUserRepository.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "countByRole" -> List.of();
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "FakeAppUserRepository";
                        default -> throw new UnsupportedOperationException(
                                "not needed by PolicyStore in tests: " + method.getName());
                    };
                });
    }
}
