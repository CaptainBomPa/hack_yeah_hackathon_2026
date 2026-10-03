package pl.hackyeah.controllayer.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import pl.hackyeah.controllayer.budget.BudgetLimitsProperties;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.model.ModelCatalogProperties;

/**
 * Cała polityka gatewaya jako jeden dokument (CRITERIA §4.1: jedna konfiguracja kontroli, progów,
 * dozwolonych modeli i budżetów). Źródłem prawdy jest tabela `policy_version` (docs/policy-management-plan.md);
 * pliki `policy.yaml` / `application.yml` służą tylko do zbudowania wersji 1 przy pierwszym starcie.
 *
 * Konstruktor normalizuje dokument (posortowane mapy i listy, liczby zamiast napisów z YAML),
 * dzięki czemu ten sam stan zawsze ma ten sam JSON i ten sam hash.
 *
 * @param roles  rola konta (`app_user.role`) → dostęp; brak roli = odmowa
 * @param models allowlista: które tagi z katalogu modeli (application.yml) są włączone
 * @param guards id guarda → włączony, kolejność, parametry; guard bez wpisu jest wyłączony
 * @param limits limity jednego żądania (tokeny wejścia i wyjścia), wspólne dla wszystkich ról
 */
public record PolicyDocument(
        Map<String, RolePolicy> roles,
        List<ModelPolicy> models,
        Map<String, GuardPolicy> guards,
        Limits limits) {

    public static final String ANY_MODEL = "*";

    /** Parametry guardów, które są listami id (np. recognizerów PII) — w YAML bywają napisem z przecinkami. */
    private static final Set<String> LIST_PARAMS = Set.of("disabledRecognizers", "blockRecognizers", "monitorRecognizers");

    /** Parametry, które są infrastrukturą (gdzie leży plik), a nie decyzją bezpieczeństwa — nie trafiają do polityki. */
    private static final Set<String> INFRA_PARAMS = Set.of("pack");

    public PolicyDocument {
        var sortedRoles = new TreeMap<String, RolePolicy>();
        if (roles != null) {
            roles.forEach((name, role) -> sortedRoles.put(name, role == null ? new RolePolicy(null, null) : role));
        }
        roles = sortedRoles;
        models = models == null ? List.of()
                : models.stream().sorted(Comparator.comparing(ModelPolicy::tag)).toList();
        var sortedGuards = new TreeMap<String, GuardPolicy>();
        if (guards != null) {
            guards.forEach((id, guard) -> sortedGuards.put(id, guard == null ? new GuardPolicy(false, 100, null) : guard));
        }
        guards = sortedGuards;
        limits = limits == null ? new Limits(null, null) : limits;
    }

    /**
     * @param models      tagi modeli albo {@value #ANY_MODEL} = wszystkie włączone modele
     * @param dailyTokens dzienny limit tokenów roli; {@code null} = bez limitu
     */
    public record RolePolicy(List<String> models, Long dailyTokens) {
        public RolePolicy {
            models = models == null ? List.of() : models.stream().distinct().sorted().toList();
        }
    }

    public record ModelPolicy(String tag, boolean enabled) {}

    public record GuardPolicy(boolean enabled, int order, Map<String, Object> params) {
        public GuardPolicy {
            params = normalizeParams(params);
        }
    }

    public record Limits(Integer maxInputTokens, Integer maxOutputTokens) {
        public Limits {
            if (maxInputTokens == null) {
                maxInputTokens = 4000;
            }
            if (maxOutputTokens == null) {
                maxOutputTokens = 1024;
            }
        }
    }

    public boolean modelEnabled(String tag) {
        return models.stream().anyMatch(m -> m.tag().equals(tag) && m.enabled());
    }

    /**
     * Wersja 1 polityki z dotychczasowej konfiguracji plikowej. Argumenty mogą być null — wtedy ta
     * część dokumentu jest pusta (przydatne w testach, które konfigurują tylko jeden aspekt).
     */
    public static PolicyDocument fromConfig(PolicyProperties policy, ModelCatalogProperties catalog,
            GuardProperties guardProperties, BudgetLimitsProperties limits) {
        var roles = new LinkedHashMap<String, RolePolicy>();
        if (policy != null) {
            policy.roles().forEach((name, role) -> roles.put(name,
                    new RolePolicy(role.models(), role.budget() == null ? null : role.budget().dailyTokens())));
        }
        List<ModelPolicy> models = catalog == null ? List.of()
                : catalog.models().stream().map(m -> new ModelPolicy(m.tag(), m.enabled())).toList();
        var guards = new LinkedHashMap<String, GuardPolicy>();
        if (guardProperties != null) {
            guardProperties.rules().forEach((id, rule) -> guards.put(id,
                    new GuardPolicy(guardProperties.enabled() && rule.enabled(), rule.order(), rule.params())));
        }
        return new PolicyDocument(roles, models, guards,
                limits == null ? null : new Limits(limits.maxInputTokens(), limits.maxOutputTokens()));
    }

    /**
     * Parametry z YAML/Springa przychodzą jako napisy ("0.5", "true") i listy jako mapy indeksów
     * ({0: a, 1: b}); w polityce trzymamy prawdziwe liczby, booleany i listy.
     */
    static Map<String, Object> normalizeParams(Map<String, Object> params) {
        var normalized = new TreeMap<String, Object>();
        if (params == null) {
            return normalized;
        }
        params.forEach((key, value) -> {
            if (INFRA_PARAMS.contains(key) || value == null) {
                return;
            }
            normalized.put(key, LIST_PARAMS.contains(key) ? toList(value) : normalizeScalar(value));
        });
        return normalized;
    }

    private static Object normalizeScalar(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            return Long.parseLong(trimmed);
        }
        if (trimmed.matches("-?\\d*\\.\\d+")) {
            return Double.parseDouble(trimmed);
        }
        return text;
    }

    private static List<String> toList(Object value) {
        Collection<?> items = switch (value) {
            case Collection<?> c -> c;
            case Map<?, ?> indexed -> indexed.values();
            case String s -> Arrays.asList(s.split(","));
            default -> List.of(value);
        };
        var list = new ArrayList<String>();
        for (Object item : items) {
            String id = String.valueOf(item).trim();
            if (!id.isEmpty() && !list.contains(id)) {
                list.add(id);
            }
        }
        list.sort(Comparator.naturalOrder());
        return list;
    }
}
