package pl.hackyeah.controllayer.policy;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Polityka uprawnień z `policy.yaml` (CRITERIA §4.1: jedna konfiguracja). Klucz mapy to rola
 * konta (`app_user.role`), wartość to to, do czego ta rola ma dostęp.
 */
@ConfigurationProperties(prefix = "policy")
public record PolicyProperties(Map<String, RolePolicy> roles) {

    public PolicyProperties {
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    /**
     * `models` to tagi z katalogu modeli; `"*"` oznacza wszystkie modele z katalogu.
     * `budget` to dzienny limit tokenów tej roli (docs/deterministic/14-token-budget-quotas.md,
     * BUDGET-003); `null` = bez limitu (np. `admin`).
     */
    public record RolePolicy(List<String> models, Budget budget) {

        public RolePolicy {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public record Budget(long dailyTokens) {}
    }
}
