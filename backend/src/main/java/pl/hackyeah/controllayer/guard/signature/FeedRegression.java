package pl.hackyeah.controllayer.guard.signature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed.Signature;

/**
 * Reguły regresji feedu sygnatur (docs/redteam-feed.md), wspólne dla testu JUnit
 * ({@code SignatureFeedRegressionTest}) i raportu {@code threatFeed}:
 * <ul>
 *   <li>każda sygnatura ma co najmniej jeden przypadek pozytywny i jeden negatywny,</li>
 *   <li>każdy pozytywny jest łapany przez swoją sygnaturę,</li>
 *   <li>żaden negatywny ani tekst z korpusu benign nie trafia w żadną włączoną sygnaturę.</li>
 * </ul>
 */
public final class FeedRegression {

    public enum Kind { STRUCTURE, POSITIVE, NEGATIVE, BENIGN }

    /** Wynik jednego sprawdzenia; {@code signatureId} jest null dla korpusu benign. */
    public record Check(String signatureId, Kind kind, String text, boolean passed, String message) {}

    private FeedRegression() {}

    public static List<Check> run(SignatureFeed feed, List<String> benign) {
        var checks = new ArrayList<Check>();
        for (Signature signature : feed.signatures()) {
            boolean complete = !signature.positive().isEmpty() && !signature.negative().isEmpty();
            checks.add(new Check(signature.id(), Kind.STRUCTURE, "has positive and negative cases", complete,
                    complete ? "" : "missing " + (signature.positive().isEmpty() ? "tests.positive" : "tests.negative")));
            for (String text : signature.positive()) {
                List<String> hits = hitIds(feed, text);
                boolean passed = hits.contains(signature.id());
                checks.add(new Check(signature.id(), Kind.POSITIVE, text, passed,
                        passed ? "" : "not matched by " + signature.id() + " (hits: " + hits + ")"));
            }
            for (String text : signature.negative()) {
                checks.add(noHit(feed, signature.id(), Kind.NEGATIVE, text));
            }
        }
        for (String text : benign) {
            checks.add(noHit(feed, null, Kind.BENIGN, text));
        }
        return checks;
    }

    public static List<String> loadBenign(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(file, StandardCharsets.UTF_8));
        if (root instanceof Map<?, ?> map && map.get("benign") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException(file + ": expected top-level 'benign' list");
    }

    private static Check noHit(SignatureFeed feed, String signatureId, Kind kind, String text) {
        List<String> hits = hitIds(feed, text);
        return new Check(signatureId, kind, text, hits.isEmpty(), hits.isEmpty() ? "" : "false positive " + hits);
    }

    private static List<String> hitIds(SignatureFeed feed, String text) {
        return SignatureFeedGuard.hits(feed, text, Set.of()).stream().map(Signature::id).toList();
    }
}
