package pl.hackyeah.controllayer.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Globalne limity dla BUDGET-001 (clamp wyjścia) i BUDGET-002 (max wejścia) z
 * docs/deterministic/14-token-budget-quotas.md §11. Limity per-rolę (dzienny cap) są w
 * `policy.yaml` ({@link pl.hackyeah.controllayer.policy.PolicyProperties.RolePolicy.Budget}) —
 * te tutaj są wspólne dla wszystkich ról, bo chronią Raspberry Pi, nie konkretnego użytkownika.
 */
@ConfigurationProperties(prefix = "control-layer.budget")
public record BudgetLimitsProperties(Integer maxInputTokens, Integer maxOutputTokens) {

    public BudgetLimitsProperties {
        if (maxInputTokens == null) {
            maxInputTokens = 4000;
        }
        if (maxOutputTokens == null) {
            maxOutputTokens = 1024;
        }
    }
}
