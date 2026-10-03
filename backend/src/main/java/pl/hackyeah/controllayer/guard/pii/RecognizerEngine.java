package pl.hackyeah.controllayer.guard.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import pl.hackyeah.controllayer.guard.pii.RecognizerPack.PatternDef;
import pl.hackyeah.controllayer.guard.pii.RecognizerPack.Recognizer;

/**
 * Analyzer w stylu Presidio, w całości deterministyczny i bez NLP:
 *
 * <ol>
 *   <li>każdy wzorzec recognizera daje kandydatów ze score wzorca;</li>
 *   <li>walidator: VALID → 1.0, INVALID → odrzuć, UNKNOWN → zostaw (jak {@code validate_result});</li>
 *   <li>słowo kontekstowe w oknie → {@code min(1, max(score + 0.35, 0.4))} (domyślne wartości
 *       {@code LemmaContextAwareEnhancer}); {@code require_context} bez kontekstu → odrzuć;</li>
 *   <li>{@code allow_list} i próg {@code threshold};</li>
 *   <li>konflikty: przy nakładaniu wygrywa wyższy score, potem dłuższy zakres.</li>
 * </ol>
 *
 * <p>Instancja jest niemutowalna i thread-safe.
 */
public final class RecognizerEngine {

    public record Options(double threshold, int contextPrefixWords, int contextSuffixWords,
                          double contextBoost, double minScoreWithContext) {
        public static Options defaults() {
            return new Options(0.5, 5, 3, 0.35, 0.4);
        }
    }

    private static final Comparator<PiiFinding> PRIORITY = Comparator
            .comparingDouble(PiiFinding::score).reversed()
            .thenComparing(Comparator.comparingInt(PiiFinding::length).reversed())
            .thenComparingInt(PiiFinding::start);

    private final List<Recognizer> recognizers;
    private final Options options;

    public RecognizerEngine(List<Recognizer> recognizers, Options options) {
        this.recognizers = List.copyOf(recognizers);
        this.options = options;
    }

    public List<Recognizer> recognizers() {
        return recognizers;
    }

    /** Trafienia po progu i rozwiązaniu konfliktów, posortowane po pozycji. */
    public List<PiiFinding> analyze(String text, Set<String> disabledIds) {
        var candidates = new ArrayList<PiiFinding>();
        for (Recognizer recognizer : recognizers) {
            if (disabledIds.contains(recognizer.id())) {
                continue;
            }
            for (PatternDef pattern : recognizer.patterns()) {
                var matcher = pattern.regex().matcher(text);
                while (matcher.find()) {
                    if (matcher.end() == matcher.start()) {
                        continue;
                    }
                    PiiFinding finding = score(recognizer, pattern, text, matcher.start(), matcher.end());
                    if (finding != null && finding.score() >= options.threshold()) {
                        candidates.add(finding);
                    }
                }
            }
        }
        return resolveConflicts(candidates);
    }

    private PiiFinding score(Recognizer recognizer, PatternDef pattern, String text, int start, int end) {
        String value = text.substring(start, end);
        double score = pattern.score();
        var why = new StringBuilder("pattern ").append(pattern.name()).append(' ').append(fmt(score));

        if (recognizer.validator() != null) {
            switch (Validators.validate(recognizer.validator(), value)) {
                case INVALID -> {
                    return null;
                }
                case VALID -> {
                    score = 1.0;
                    why.append(" → ").append(recognizer.validator()).append(":valid ").append(fmt(score));
                }
                case UNKNOWN -> why.append(" → ").append(recognizer.validator()).append(":unknown");
            }
        }

        var keyword = ContextMatcher.find(text, start, end, recognizer.context(),
                options.contextPrefixWords(), options.contextSuffixWords());
        if (keyword.isPresent()) {
            double boosted = Math.min(1.0, Math.max(score + options.contextBoost(), options.minScoreWithContext()));
            why.append(" → context '").append(keyword.get()).append("' ").append(fmt(boosted));
            score = boosted;
        } else if (recognizer.requireContext()) {
            return null;
        }

        if (!recognizer.allowList().isEmpty() && recognizer.allowList().contains(Validators.compact(value))) {
            return null;
        }
        return new PiiFinding(recognizer, start, end, score, why.toString());
    }

    private static List<PiiFinding> resolveConflicts(List<PiiFinding> candidates) {
        candidates.sort(PRIORITY);
        var accepted = new ArrayList<PiiFinding>();
        for (PiiFinding candidate : candidates) {
            if (accepted.stream().noneMatch(candidate::overlaps)) {
                accepted.add(candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(PiiFinding::start));
        return accepted;
    }

    private static String fmt(double score) {
        return String.format(Locale.ROOT, "%.2f", score);
    }
}
