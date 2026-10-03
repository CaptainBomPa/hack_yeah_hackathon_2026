package pl.hackyeah.controllayer.guard.pii;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Słowa kontekstowe w oknie N słów przed i M słów po trafieniu (odpowiednik
 * {@code LemmaContextAwareEnhancer} z Presidio, ale bez NLP). Zamiast lematyzacji porównujemy
 * prefiksy po normalizacji (małe litery, bez diakrytyków): {@code dowod} pasuje do „dowodu”,
 * „dowód”, „dowodem”, co wystarcza dla polskiej fleksji w typowych frazach.
 */
final class ContextMatcher {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Ile znaków wokół trafienia oglądamy przy szukaniu słów; ogranicza koszt dla długich tekstów. */
    private static final int MAX_WINDOW_CHARS = 200;

    private ContextMatcher() {}

    static String normalize(String word) {
        String lower = word.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    /** Pierwsze słowo kontekstowe z okna albo pusty wynik. */
    static Optional<String> find(String text, int start, int end, List<String> keywords, int prefixWords, int suffixWords) {
        if (keywords.isEmpty()) {
            return Optional.empty();
        }
        List<String> before = words(text.substring(Math.max(0, start - MAX_WINDOW_CHARS), start));
        List<String> after = words(text.substring(end, Math.min(text.length(), end + MAX_WINDOW_CHARS)));
        var window = new ArrayList<String>();
        window.addAll(before.subList(Math.max(0, before.size() - prefixWords), before.size()));
        window.addAll(after.subList(0, Math.min(suffixWords, after.size())));
        for (String word : window) {
            String normalized = normalize(word);
            for (String keyword : keywords) {
                if (normalized.startsWith(keyword)) {
                    return Optional.of(keyword);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> words(String fragment) {
        var out = new ArrayList<String>();
        var matcher = WORD.matcher(fragment);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }
}
