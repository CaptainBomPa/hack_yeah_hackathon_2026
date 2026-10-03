package pl.hackyeah.controllayer.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sprawdza politykę przed aktywacją — błędna wersja nigdy nie staje się aktywna (VISION.md §4).
 * Czysta funkcja: cały potrzebny stan (znane guardy, modele, recognizery, role z kontami) przychodzi
 * w {@link Context}, więc walidator da się testować bez Springa.
 */
public final class PolicyValidator {

    public static final String ADMIN_ROLE = "admin";
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,49}");
    private static final List<String> RECOGNIZER_LISTS = List.of("disabledRecognizers", "blockRecognizers", "monitorRecognizers");

    /** Znane parametry guardów; guard spoza tej mapy przyjmuje dowolne parametry. */
    private static final Map<String, Set<String>> KNOWN_PARAMS = Map.of(
            "PII-RECOGNIZERS", Set.of("threshold", "contextPrefixWords", "contextSuffixWords",
                    "disabledRecognizers", "blockRecognizers", "monitorRecognizers"),
            "SEM-001", Set.of("blockThreshold", "timeoutMs", "failureMode"));

    private PolicyValidator() {
    }

    /**
     * @param guardIds      id beanów {@code Guard} — tylko takie guardy można skonfigurować
     * @param catalogModels tagi z katalogu modeli (application.yml)
     * @param recognizerIds id recognizerów PII z paczki
     * @param roleAccounts  rola → liczba kont w {@code app_user}; takiej roli nie wolno usunąć
     */
    public record Context(Set<String> guardIds, Set<String> catalogModels, Set<String> recognizerIds,
            Map<String, Long> roleAccounts) {}

    public record Error(String path, String message) {}

    public static List<Error> validate(PolicyDocument doc, Context ctx) {
        var errors = new ArrayList<Error>();
        validateRoles(doc, ctx, errors);
        validateModels(doc, ctx, errors);
        validateGuards(doc, ctx, errors);
        validateLimits(doc, errors);
        return errors;
    }

    private static void validateRoles(PolicyDocument doc, Context ctx, List<Error> errors) {
        if (!doc.roles().containsKey(ADMIN_ROLE)) {
            errors.add(new Error("roles", "role 'admin' is required — without it nobody can manage the gateway"));
        }
        ctx.roleAccounts().forEach((role, count) -> {
            if (count > 0 && !doc.roles().containsKey(role)) {
                errors.add(new Error("roles." + role,
                        "role '" + role + "' is used by " + count + " account(s) and cannot be removed"));
            }
        });
        doc.roles().forEach((name, role) -> {
            String path = "roles." + name;
            try {
                doc.rateLimit().forRole(role.rateLimit());
            } catch (IllegalArgumentException error) {
                errors.add(new Error(path + ".rateLimit", error.getMessage()));
            }
            if (!ROLE_NAME.matcher(name).matches()) {
                errors.add(new Error(path, "role name must be lowercase letters, digits, '-' or '_' (max 50)"));
            }
            for (int i = 0; i < role.models().size(); i++) {
                String model = role.models().get(i);
                if (!PolicyDocument.ANY_MODEL.equals(model) && !ctx.catalogModels().contains(model)) {
                    errors.add(new Error(path + ".models[" + i + "]", "unknown model '" + model + "'"));
                }
            }
            if (role.dailyTokens() != null && role.dailyTokens() < 1) {
                errors.add(new Error(path + ".dailyTokens", "daily token budget must be at least 1 (empty = unlimited)"));
            }
        });
    }

    private static void validateModels(PolicyDocument doc, Context ctx, List<Error> errors) {
        var seen = new HashMap<String, Integer>();
        for (int i = 0; i < doc.models().size(); i++) {
            String tag = doc.models().get(i).tag();
            String path = "models[" + i + "]";
            if (tag == null || !ctx.catalogModels().contains(tag)) {
                errors.add(new Error(path, "unknown model '" + tag + "' — models are defined by the deployment catalog"));
            } else if (seen.put(tag, i) != null) {
                errors.add(new Error(path, "model '" + tag + "' is listed twice"));
            }
        }
    }

    private static void validateGuards(PolicyDocument doc, Context ctx, List<Error> errors) {
        doc.guards().forEach((id, guard) -> {
            String path = "guards." + id;
            if (!ctx.guardIds().contains(id)) {
                errors.add(new Error(path, "unknown guard '" + id + "'"));
                return;
            }
            if (guard.order() < 1 || guard.order() > 10_000) {
                errors.add(new Error(path + ".order", "order must be between 1 and 10000"));
            }
            Set<String> known = KNOWN_PARAMS.get(id);
            guard.params().forEach((name, value) -> {
                String paramPath = path + ".params." + name;
                if (known != null && !known.contains(name)) {
                    errors.add(new Error(paramPath, "unknown parameter for " + id));
                    return;
                }
                validateParam(name, value, paramPath, ctx, errors);
            });
            var seenIn = new HashMap<String, String>();
            for (String list : RECOGNIZER_LISTS) {
                if (guard.params().get(list) instanceof List<?> ids) {
                    for (Object recognizer : ids) {
                        String previous = seenIn.put(String.valueOf(recognizer), list);
                        if (previous != null) {
                            errors.add(new Error(path + ".params." + list,
                                    "recognizer " + recognizer + " is also in " + previous));
                        }
                    }
                }
            }
        });
    }

    private static void validateParam(String name, Object value, String path, Context ctx, List<Error> errors) {
        switch (name) {
            case "threshold", "blockThreshold" -> {
                if (!(value instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1) {
                    errors.add(new Error(path, "must be a number between 0 and 1"));
                }
            }
            case "timeoutMs" -> {
                if (!(value instanceof Number n) || n.longValue() < 100 || n.longValue() > 60_000) {
                    errors.add(new Error(path, "must be between 100 and 60000 ms"));
                }
            }
            case "contextPrefixWords", "contextSuffixWords" -> {
                if (!(value instanceof Number n) || n.intValue() < 0 || n.intValue() > 20) {
                    errors.add(new Error(path, "must be between 0 and 20"));
                }
            }
            case "failureMode" -> {
                if (!"open".equals(value) && !"closed".equals(value)) {
                    errors.add(new Error(path, "must be 'open' or 'closed'"));
                }
            }
            case "disabledRecognizers", "blockRecognizers", "monitorRecognizers" -> {
                if (!(value instanceof List<?> ids)) {
                    errors.add(new Error(path, "must be a list of recognizer ids"));
                    return;
                }
                for (Object id : ids) {
                    if (!ctx.recognizerIds().contains(String.valueOf(id))) {
                        errors.add(new Error(path, "unknown recognizer '" + id + "'"));
                    }
                }
            }
            default -> {
                // parametr guarda spoza KNOWN_PARAMS — guard sam interpretuje
            }
        }
    }

    private static void validateLimits(PolicyDocument doc, List<Error> errors) {
        if (doc.limits().maxInputTokens() < 1 || doc.limits().maxInputTokens() > 1_000_000) {
            errors.add(new Error("limits.maxInputTokens", "must be between 1 and 1000000"));
        }
        if (doc.limits().maxOutputTokens() < 1 || doc.limits().maxOutputTokens() > 100_000) {
            errors.add(new Error("limits.maxOutputTokens", "must be between 1 and 100000"));
        }
    }
}
