package pl.hackyeah.controllayer.policy;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import pl.hackyeah.controllayer.ratelimit.RateLimitSettings;

/**
 * Polityka uprawnień z `policy.yaml` (CRITERIA §4.1: jedna konfiguracja). Klucz mapy to rola
 * konta (`app_user.role`), wartość to to, do czego ta rola ma dostęp.
 */
@ConfigurationProperties(prefix = "policy")
public record PolicyProperties(Map<String, RolePolicy> roles, RateLimitSettings rateLimit) {

    @ConstructorBinding
    public PolicyProperties {
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        rateLimit = rateLimit == null ? RateLimitSettings.defaults() : rateLimit;
        for (RolePolicy role : roles.values()) {
            rateLimit.forRole(role.rateLimit());
        }
    }

    public PolicyProperties(Map<String, RolePolicy> roles) {
        this(roles, null);
    }

    /**
     * `models` to tagi z katalogu modeli; `"*"` oznacza wszystkie modele z katalogu.
     * `budget` to dzienny limit tokenów tej roli (docs/deterministic/14-token-budget-quotas.md,
     * BUDGET-003); `null` = bez limitu (np. `admin`).
     */
    public record RolePolicy(List<String> models, Budget budget, RateLimitSettings.RoleOverride rateLimit) {

        @ConstructorBinding
        public RolePolicy {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public RolePolicy(List<String> models, Budget budget) {
            this(models, budget, null);
        }

        public record Budget(long dailyTokens) {}
    }
}
