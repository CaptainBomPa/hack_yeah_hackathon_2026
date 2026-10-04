package pl.hackyeah.controllayer.guard.signature;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sposób dopasowania sygnatury do tekstu. Tekst jest wcześniej znormalizowany przez
 * {@link #normalize(String)} (NFKC, bez znaków niewidocznych), więc sygnatury nie obchodzi się
 * wstawieniem zero-width space ani pełnoszerokich znaków.
 */
public sealed interface SignatureMatcher {

    boolean matches(String normalizedText);

    /** Wyrażenie regularne (java.util.regex); wielkość liter steruje autor, np. {@code (?i)}. */
    record Regex(Pattern pattern) implements SignatureMatcher {
        @Override
        public boolean matches(String normalizedText) {
            return pattern.matcher(normalizedText).find();
        }
    }

    /** Którakolwiek z fraz, bez rozróżniania wielkości liter. */
    record Contains(List<String> phrases) implements SignatureMatcher {
        public Contains {
            phrases = phrases.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        }

        @Override
        public boolean matches(String normalizedText) {
            String lower = normalizedText.toLowerCase(Locale.ROOT);
            return phrases.stream().anyMatch(lower::contains);
        }
    }

    /**
     * Podatny komponent: wzmianka o paczce w wersji z zakresu OSV, np. {@code npx mcp-remote@0.1.15},
     * {@code pip install langchain==0.0.300}, {@code ollama/ollama:0.1.30}, {@code ollama version is 0.1.30}
     * albo {@code "name":"mcp-remote","version":"0.1.15"} (serverInfo z handshake'u MCP).
     */
    record PackageVersion(List<String> names, List<VersionRange> ranges, List<Pattern> mentions)
            implements SignatureMatcher {

        private static final String VERSION = "v?(\\d+(?:\\.\\d+){0,3})";
        private static final String SEPARATOR = "\\s*(?:@|===?|:|\\s+version\\s+(?:is\\s+)?|\\s+(?=v\\d))\\s*";

        public PackageVersion(List<String> names, List<VersionRange> ranges) {
            this(List.copyOf(names), List.copyOf(ranges), compile(names));
        }

        private static List<Pattern> compile(List<String> names) {
            if (names.isEmpty()) {
                throw new IllegalArgumentException("package-version needs at least one package name");
            }
            return names.stream().flatMap(name -> {
                String quoted = Pattern.quote(name);
                return Stream.of(
                        Pattern.compile("(?i)(?<![\\w./@-])" + quoted + SEPARATOR + VERSION),
                        Pattern.compile("(?i)\"name\"\\s*:\\s*\"" + quoted + "\"\\s*,\\s*\"version\"\\s*:\\s*\"" + VERSION + "\""));
            }).toList();
        }

        @Override
        public boolean matches(String normalizedText) {
            for (Pattern mention : mentions) {
                Matcher m = mention.matcher(normalizedText);
                while (m.find()) {
                    String version = m.group(1);
                    if (ranges.stream().anyMatch(range -> range.contains(version))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** NFKC + usunięcie znaków formatujących (zero-width, bidi, Unicode Tags). */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        var out = new StringBuilder(nfkc.length());
        nfkc.codePoints()
                .filter(cp -> Character.getType(cp) != Character.FORMAT)
                .forEach(out::appendCodePoint);
        return out.toString();
    }
}
