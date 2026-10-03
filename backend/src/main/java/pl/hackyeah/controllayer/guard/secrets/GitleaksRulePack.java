package pl.hackyeah.controllayer.guard.secrets;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Ładuje paczkę reguł w formacie Gitleaks ({@code gitleaks.toml}) i tłumaczy ją na {@link SecretRule}.
 *
 * <p>Pomijane są reguły bez {@code regex} oraz reguły ograniczone do ścieżek plików ({@code path}),
 * bo w ruchu do LLM nie ma nazwy pliku, a bez niej Gitleaks też by ich nie zgłosił. Reguła, której
 * regex nie da się skompilować, jest pomijana i raportowana w {@link Result#skipped()}.
 */
public final class GitleaksRulePack {

    /** Wiodący prefiks nazwy zmiennej w regułach kontekstowych Gitleaks; to on dominuje koszt skanu. */
    static final String CONTEXT_PREFIX = "[\\w.-]{0,50}?";
    /** Operator przypisania w tym samym szablonie; pozwala na tani precheck przed regexem. */
    static final String CONTEXT_OPERATOR = "(?:=|>|:{1,3}=|\\|\\||:|=>|\\?=|,)";

    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(?:,\\d*)?}");
    private static final Pattern GO_NAMED_GROUP = Pattern.compile("\\(\\?P<([^>]+)>");
    private static final Pattern POSIX_CLASS = Pattern.compile("\\[:(alnum|alpha|digit|lower|upper|space|punct|xdigit|word):]");
    /** Kwantyfikatory bez górnej granicy (poza escapowanymi {@code \*}, {@code \+}). */
    private static final Pattern UNBOUNDED = Pattern.compile("(?<!\\\\)[*+]|\\{\\d+,}");

    public record Result(List<SecretRule> rules, Allowlist globalAllowlist, List<String> skipped) {}

    private GitleaksRulePack() {}

    public static Result load(InputStream toml) throws IOException {
        Map<String, Object> root = new TomlMapper().readValue(toml, new TypeReference<Map<String, Object>>() {});
        var skipped = new ArrayList<String>();
        var rules = new ArrayList<SecretRule>();
        for (Map<String, Object> raw : listOfMaps(root.get("rules"))) {
            String id = (String) raw.get("id");
            String regex = (String) raw.get("regex");
            if (regex == null) {
                skipped.add(id + " (brak regex)");
                continue;
            }
            if (raw.get("path") != null) {
                skipped.add(id + " (reguła ścieżki pliku)");
                continue;
            }
            try {
                rules.add(toRule(id, raw, regex, skipped));
            } catch (PatternSyntaxException error) {
                skipped.add(id + " (regex: " + error.getDescription() + ")");
            }
        }
        Allowlist global = root.get("allowlist") instanceof Map<?, ?> map
                ? toAllowlist("global", castMap(map), skipped)
                : new Allowlist(Allowlist.Target.SECRET, List.of(), List.of(), false, false);
        return new Result(List.copyOf(rules), global, List.copyOf(skipped));
    }

    private static SecretRule toRule(String id, Map<String, Object> raw, String goRegex, List<String> skipped) {
        String javaRegex = translateGoRegex(goRegex);
        Pattern original = Pattern.compile(javaRegex);
        boolean contextual = isContextual(javaRegex);
        Pattern scanPattern = contextual ? Pattern.compile(stripContextPrefix(javaRegex)) : original;
        int secretGroup = raw.get("secretGroup") instanceof Number number ? number.intValue() : 0;
        boolean wholeMatch = secretGroup == 0 && javaRegex.contains("(?<") && hasNamedGroup(javaRegex);
        double entropy = raw.get("entropy") instanceof Number number ? number.doubleValue() : 0;
        List<String> keywords = strings(raw.get("keywords")).stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .toList();
        var allowlists = new ArrayList<Allowlist>();
        for (Map<String, Object> allowlist : listOfMaps(raw.get("allowlists"))) {
            allowlists.add(toAllowlist(id, allowlist, skipped));
        }
        if (raw.get("allowlist") instanceof Map<?, ?> legacy) {
            allowlists.add(toAllowlist(id, castMap(legacy), skipped));
        }
        return new SecretRule(id, (String) raw.get("description"), scanPattern, original, secretGroup, wholeMatch,
                entropy, keywords, contextual, !UNBOUNDED.matcher(javaRegex).find(), allowlists);
    }

    private static Allowlist toAllowlist(String owner, Map<String, Object> raw, List<String> skipped) {
        Allowlist.Target target = switch (String.valueOf(raw.getOrDefault("regexTarget", "secret"))) {
            case "match" -> Allowlist.Target.MATCH;
            case "line" -> Allowlist.Target.LINE;
            default -> Allowlist.Target.SECRET;
        };
        boolean and = "AND".equalsIgnoreCase(String.valueOf(raw.getOrDefault("condition", "OR")));
        boolean hasPathCriteria = !strings(raw.get("paths")).isEmpty() || !strings(raw.get("commits")).isEmpty();
        var regexes = new ArrayList<Pattern>();
        for (String regex : strings(raw.get("regexes"))) {
            try {
                regexes.add(Pattern.compile(translateGoRegex(regex)));
            } catch (PatternSyntaxException error) {
                // Pominięcie wyjątku z allowlisty jest bezpieczne: detekcja staje się ostrzejsza, nie słabsza.
                skipped.add(owner + " allowlist regex (" + error.getDescription() + ")");
            }
        }
        // OR + same ścieżki: nic do sprawdzenia w prompcie. AND + ścieżki: warunek ścieżki nigdy nie jest spełniony.
        boolean neverMatches = hasPathCriteria && (and || (regexes.isEmpty() && strings(raw.get("stopwords")).isEmpty()));
        return new Allowlist(target, regexes, strings(raw.get("stopwords")), and, neverMatches);
    }

    /** Różnice składni Go RE2 → java.util.regex występujące w paczce Gitleaks. */
    static String translateGoRegex(String goRegex) {
        Matcher named = GO_NAMED_GROUP.matcher(goRegex);
        String result = named.replaceAll(match ->
                Matcher.quoteReplacement("(?<" + match.group(1).replaceAll("[^A-Za-z0-9]", "") + ">"));
        Matcher posix = POSIX_CLASS.matcher(result);
        result = posix.replaceAll(match -> Matcher.quoteReplacement("\\p{" + posixName(match.group(1)) + "}"));
        return escapeLiteralBraces(result);
    }

    /**
     * Go traktuje {@code {} niebędący kwantyfikatorem (np. {@code ${VAR}}, {@code {{x}}}) jako literał,
     * Java zgłasza "Illegal repetition". Escapujemy takie klamry poza klasami znaków.
     */
    static String escapeLiteralBraces(String regex) {
        var out = new StringBuilder(regex.length() + 8);
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char ch = regex.charAt(i);
            if (ch == '\\' && i + 1 < regex.length()) {
                out.append(ch).append(regex.charAt(++i));
                continue;
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false;
                }
                out.append(ch);
                continue;
            }
            if (ch == '[') {
                inClass = true;
                out.append(ch);
                // ']' tuż po '[' lub '[^' jest literałem, nie końcem klasy
                if (i + 1 < regex.length() && regex.charAt(i + 1) == '^') {
                    out.append(regex.charAt(++i));
                }
                if (i + 1 < regex.length() && regex.charAt(i + 1) == ']') {
                    out.append("\\]");
                    i++;
                }
                continue;
            }
            if (ch == '{' && !QUANTIFIER.matcher(regex).region(i, regex.length()).lookingAt()) {
                out.append("\\{");
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String posixName(String go) {
        return switch (go) {
            case "alnum" -> "Alnum";
            case "alpha" -> "Alpha";
            case "digit" -> "Digit";
            case "lower" -> "Lower";
            case "upper" -> "Upper";
            case "space" -> "Space";
            case "punct" -> "Punct";
            case "xdigit" -> "XDigit";
            default -> "Alnum";
        };
    }

    static boolean isContextual(String regex) {
        return (regex.startsWith(CONTEXT_PREFIX) || regex.startsWith("(?i)" + CONTEXT_PREFIX))
                && regex.contains(CONTEXT_OPERATOR);
    }

    private static String stripContextPrefix(String regex) {
        int at = regex.indexOf(CONTEXT_PREFIX);
        return regex.substring(0, at) + regex.substring(at + CONTEXT_PREFIX.length());
    }

    private static boolean hasNamedGroup(String regex) {
        return Pattern.compile("\\(\\?<[A-Za-z]").matcher(regex).find();
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> castMap((Map<?, ?>) item)).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
