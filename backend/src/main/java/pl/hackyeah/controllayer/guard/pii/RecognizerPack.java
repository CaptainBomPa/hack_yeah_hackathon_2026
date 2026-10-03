package pl.hackyeah.controllayer.guard.pii;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Paczka recognizerów PII w formacie YAML zgodnym z Presidio ({@code recognizers:} z polami
 * {@code name}, {@code supported_entity}, {@code patterns[name, regex, score]}, {@code context},
 * {@code deny_list}). Rozszerzenia AI Control Layer: {@code id} (polityka w trace), {@code validator}
 * (nazwa z {@link Validators}), {@code require_context}, {@code allow_list}, {@code action}
 * ({@code redact | block | monitor}) i {@code operator} (operatory anonymizera Presidio).
 *
 * <p>Ładowanie jest fail-fast: nieznany walidator, zły regex, score spoza [0,1] albo zduplikowane
 * {@code id} przerywają start aplikacji.
 */
public record RecognizerPack(List<Recognizer> recognizers) {

    public enum Action { REDACT, BLOCK, MONITOR }

    public record PatternDef(String name, Pattern regex, double score) {}

    /** Operator anonymizera; nazwy pól jak w Presidio ({@code new_value}, {@code masking_char}...). */
    public record Operator(String type, String newValue, char maskingChar, int charsToMask, boolean fromEnd) {
        static Operator replace() {
            return new Operator("replace", null, '*', 0, false);
        }
    }

    public record Recognizer(
            String id,
            String name,
            String entity,
            List<PatternDef> patterns,
            List<String> context,
            String validator,
            boolean requireContext,
            List<String> allowList,
            Action action,
            Operator operator) {}

    public RecognizerPack {
        recognizers = List.copyOf(recognizers);
    }

    public static RecognizerPack load(InputStream yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> map) || !(map.get("recognizers") instanceof List<?> list)) {
            throw new IllegalArgumentException("Recognizer pack must have a top-level 'recognizers' list");
        }
        var ids = new HashSet<String>();
        var recognizers = new ArrayList<Recognizer>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Recognizer entry must be a map");
            }
            Recognizer recognizer = toRecognizer(raw);
            if (!ids.add(recognizer.id())) {
                throw new IllegalArgumentException("Duplicate recognizer id: " + recognizer.id());
            }
            recognizers.add(recognizer);
        }
        return new RecognizerPack(recognizers);
    }

    private static Recognizer toRecognizer(Map<?, ?> raw) {
        String entity = requiredString(raw, "supported_entity");
        String id = raw.get("id") instanceof String s ? s : entity;
        String name = raw.get("name") instanceof String s ? s : id;

        var patterns = new ArrayList<PatternDef>();
        if (raw.get("patterns") instanceof List<?> rawPatterns) {
            for (Object p : rawPatterns) {
                if (!(p instanceof Map<?, ?> pattern)) {
                    throw new IllegalArgumentException(id + ": pattern must be a map");
                }
                double score = number(pattern.get("score"), id + ": pattern score");
                if (score < 0 || score > 1) {
                    throw new IllegalArgumentException(id + ": pattern score must be in [0,1]");
                }
                String patternName = pattern.get("name") instanceof String s ? s : id;
                patterns.add(new PatternDef(patternName, Pattern.compile(requiredString(pattern, "regex")), score));
            }
        }
        // deny_list Presidio: dokładne słowa (bez rozróżniania wielkości liter), score 1.0.
        List<String> denyList = strings(raw.get("deny_list"));
        if (!denyList.isEmpty()) {
            String alternatives = denyList.stream().map(Pattern::quote).collect(Collectors.joining("|"));
            patterns.add(new PatternDef("deny_list",
                    Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 1.0));
        }
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException(id + ": recognizer needs 'patterns' or 'deny_list'");
        }

        String validator = raw.get("validator") instanceof String s ? s : null;
        if (validator != null && !Validators.exists(validator)) {
            throw new IllegalArgumentException(id + ": unknown validator '" + validator + "'");
        }
        List<String> context = strings(raw.get("context")).stream().map(ContextMatcher::normalize).toList();
        boolean requireContext = Boolean.TRUE.equals(raw.get("require_context"));
        if (requireContext && context.isEmpty()) {
            throw new IllegalArgumentException(id + ": require_context needs a non-empty 'context'");
        }
        List<String> allowList = strings(raw.get("allow_list")).stream().map(Validators::compact).toList();
        Action action = raw.get("action") instanceof String s ? parseAction(id, s) : Action.REDACT;
        Operator operator = raw.get("operator") instanceof Map<?, ?> op ? toOperator(id, op) : Operator.replace();
        return new Recognizer(id, name, entity, List.copyOf(patterns), context, validator, requireContext,
                allowList, action, operator);
    }

    static Action parseAction(String id, String value) {
        try {
            return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": unknown action '" + value + "' (redact|block|monitor)");
        }
    }

    private static Operator toOperator(String id, Map<?, ?> raw) {
        String type = raw.get("type") instanceof String s ? s.toLowerCase(Locale.ROOT) : "replace";
        return switch (type) {
            case "replace" -> new Operator(type, raw.get("new_value") instanceof String s ? s : null, '*', 0, false);
            case "redact" -> new Operator(type, null, '*', 0, false);
            case "mask" -> {
                String maskingChar = raw.get("masking_char") instanceof String s && s.length() == 1 ? s : "*";
                int charsToMask = (int) number(raw.get("chars_to_mask"), id + ": chars_to_mask");
                yield new Operator(type, null, maskingChar.charAt(0), charsToMask,
                        Boolean.TRUE.equals(raw.get("from_end")));
            }
            default -> throw new IllegalArgumentException(id + ": unknown operator '" + type + "' (replace|mask|redact)");
        };
    }

    private static String requiredString(Map<?, ?> raw, String key) {
        if (raw.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException("Missing '" + key + "' in recognizer pack entry");
    }

    private static double number(Object value, String what) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(what + " must be a number");
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
