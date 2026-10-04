package pl.hackyeah.controllayer.guard.signature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Feed sygnatur historycznych ataków (SIG-FEED, docs/redteam-feed.md). Plik YAML:
 *
 * <pre>
 * feed_version: "2026-10-04.1"
 * signatures:
 *   - id: SIG-PKG-NPM-MCP-REMOTE
 *     title: mcp-remote OS command injection
 *     class: vulnerable-component
 *     refs: [GHSA-6xpm-ggf7-wc3p, CVE-2025-6514]
 *     action: block                      # block | monitor
 *     enabled: true                      # opcjonalne, domyślnie true
 *     match:
 *       type: package-version            # regex | contains | package-version
 *       names: [mcp-remote]
 *       ranges: [[{introduced: "0.0.5"}, {fixed: "0.1.16"}]]
 *     tests:
 *       positive: ["npx mcp-remote@0.0.5 https://example.com/sse"]
 *       negative: ["npx mcp-remote@0.1.16 https://example.com/sse"]
 * </pre>
 *
 * <p>Parsowanie jest fail-fast: błąd w dowolnej sygnaturze odrzuca cały plik, żeby loader mógł
 * zostawić ostatnią poprawną wersję. Brak testów nie blokuje ładowania w runtime; wymusza je
 * regresja ({@code SignatureFeedRegressionTest}).
 */
public record SignatureFeed(String feedVersion, List<Signature> signatures) {

    private static final Pattern ID = Pattern.compile("SIG-[A-Z0-9][A-Z0-9-]*");

    public enum Action { BLOCK, MONITOR }

    public record Signature(
            String id,
            String title,
            String threatClass,
            List<String> refs,
            Action action,
            boolean enabled,
            SignatureMatcher matcher,
            List<String> positive,
            List<String> negative) {}

    public SignatureFeed {
        signatures = List.copyOf(signatures);
    }

    public static SignatureFeed empty() {
        return new SignatureFeed("none", List.of());
    }

    public static SignatureFeed parse(String yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (root == null) {
            return empty();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("feed must be a YAML map with 'signatures'");
        }
        String feedVersion = map.get("feed_version") == null ? "unversioned" : String.valueOf(map.get("feed_version"));
        Object rawSignatures = map.get("signatures");
        if (rawSignatures == null) {
            return new SignatureFeed(feedVersion, List.of());
        }
        if (!(rawSignatures instanceof List<?> list)) {
            throw new IllegalArgumentException("'signatures' must be a list");
        }
        var ids = new HashSet<String>();
        var signatures = new ArrayList<Signature>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("signature must be a map: " + raw);
            }
            Signature signature = toSignature(entry);
            if (!ids.add(signature.id())) {
                throw new IllegalArgumentException("duplicate signature id: " + signature.id());
            }
            signatures.add(signature);
        }
        return new SignatureFeed(feedVersion, signatures);
    }

    private static Signature toSignature(Map<?, ?> raw) {
        String id = requiredString(raw, "id", "?");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("signature id must match SIG-[A-Z0-9-]+: " + id);
        }
        try {
            Action action = switch (requiredString(raw, "action", id).toLowerCase(Locale.ROOT)) {
                case "block" -> Action.BLOCK;
                case "monitor" -> Action.MONITOR;
                default -> throw new IllegalArgumentException("action must be block or monitor");
            };
            boolean enabled = !(raw.get("enabled") instanceof Boolean flag) || flag;
            if (!(raw.get("match") instanceof Map<?, ?> match)) {
                throw new IllegalArgumentException("missing 'match'");
            }
            Map<?, ?> tests = raw.get("tests") instanceof Map<?, ?> t ? t : Map.of();
            return new Signature(id,
                    raw.get("title") == null ? id : String.valueOf(raw.get("title")),
                    raw.get("class") == null ? "unclassified" : String.valueOf(raw.get("class")),
                    strings(raw.get("refs")),
                    action,
                    enabled,
                    toMatcher(match),
                    strings(tests.get("positive")),
                    strings(tests.get("negative")));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(id + ": " + error.getMessage(), error);
        }
    }

    private static SignatureMatcher toMatcher(Map<?, ?> match) {
        String type = requiredString(match, "type", "match");
        return switch (type) {
            case "regex" -> {
                try {
                    yield new SignatureMatcher.Regex(Pattern.compile(requiredString(match, "pattern", "match")));
                } catch (PatternSyntaxException error) {
                    throw new IllegalArgumentException("invalid regex: " + error.getDescription());
                }
            }
            case "contains" -> {
                List<String> phrases = strings(match.get("phrases"));
                if (phrases.isEmpty() || phrases.stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException("contains needs non-empty 'phrases'");
                }
                yield new SignatureMatcher.Contains(phrases);
            }
            case "package-version" -> {
                if (!(match.get("ranges") instanceof List<?> rawRanges) || rawRanges.isEmpty()) {
                    throw new IllegalArgumentException("package-version needs 'ranges'");
                }
                var ranges = new ArrayList<VersionRange>();
                for (Object rawRange : rawRanges) {
                    if (!(rawRange instanceof List<?> events)) {
                        throw new IllegalArgumentException("each range must be a list of events");
                    }
                    ranges.add(VersionRange.fromEvents(events));
                }
                yield new SignatureMatcher.PackageVersion(strings(match.get("names")), ranges);
            }
            default -> throw new IllegalArgumentException("unknown match type: " + type);
        };
    }

    private static String requiredString(Map<?, ?> map, String key, String owner) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(owner + ": missing '" + key + "'");
        }
        return String.valueOf(value).trim();
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text) {
            return List.of(text);
        }
        return List.of();
    }
}
