package pl.hackyeah.controllayer.guard.secrets;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Allowlista w semantyce Gitleaks: trafienie jest dozwolone, gdy pasuje regex (na sekrecie,
 * całym dopasowaniu albo linii) lub sekret zawiera stopword. {@code condition = AND} wymaga
 * spełnienia wszystkich niepustych kryteriów. Kryteria ścieżek/commitów nie mają odpowiednika
 * w ruchu do LLM, więc nigdy nie są spełnione ({@code neverMatches}).
 */
public final class Allowlist {

    public enum Target {
        SECRET,
        MATCH,
        LINE
    }

    private final Target target;
    private final List<Pattern> regexes;
    private final List<String> stopwords;
    private final boolean and;
    private final boolean neverMatches;
    /** Stopwords ASCII w jednym automacie (paczka ma ich >1400 dla generic-api-key); reszta przez contains. */
    private final KeywordIndex asciiStopwords;
    private final List<String> otherStopwords;

    public Allowlist(Target target, List<Pattern> regexes, List<String> stopwords, boolean and, boolean neverMatches) {
        this.target = target;
        this.regexes = List.copyOf(regexes);
        this.stopwords = stopwords.stream().map(word -> word.toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).toList();
        this.and = and;
        this.neverMatches = neverMatches;
        var ascii = this.stopwords.stream().filter(Allowlist::isAscii).toList();
        this.asciiStopwords = ascii.isEmpty() ? null : new KeywordIndex(List.of(ascii));
        this.otherStopwords = this.stopwords.stream().filter(w -> !isAscii(w)).toList();
    }

    public Target target() {
        return target;
    }

    public List<Pattern> regexes() {
        return regexes;
    }

    public List<String> stopwords() {
        return stopwords;
    }

    /** Czy allowlista zwalnia trafienie. */
    boolean allows(String secret, String match, String line) {
        if (neverMatches || (regexes.isEmpty() && stopwords.isEmpty())) {
            return false;
        }
        String regexTarget = switch (target) {
            case SECRET -> secret;
            case MATCH -> match;
            case LINE -> line;
        };
        boolean hasRegex = !regexes.isEmpty();
        boolean hasStopwords = !stopwords.isEmpty();
        boolean regexHit = hasRegex && regexes.stream().anyMatch(regex -> regex.matcher(regexTarget).find());
        boolean stopwordHit = hasStopwords && containsStopword(secret);
        if (and) {
            return (!hasRegex || regexHit) && (!hasStopwords || stopwordHit);
        }
        return regexHit || stopwordHit;
    }

    private boolean containsStopword(String secret) {
        if (asciiStopwords != null) {
            int state = 0;
            for (int i = 0; i < secret.length(); i++) {
                state = asciiStopwords.step(state, secret.charAt(i));
                if (asciiStopwords.outputs(state).length > 0) {
                    return true;
                }
            }
        }
        if (!otherStopwords.isEmpty()) {
            String lower = secret.toLowerCase(Locale.ROOT);
            for (String stopword : otherStopwords) {
                if (lower.contains(stopword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(ch -> ch < 128);
    }
}
