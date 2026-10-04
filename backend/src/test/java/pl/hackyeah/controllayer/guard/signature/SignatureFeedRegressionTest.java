package pl.hackyeah.controllayer.guard.signature;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import pl.hackyeah.controllayer.guard.signature.FeedRegression.Check;

/**
 * Regresja feedu sygnatur — bramka przed akceptacją zmian w feedzie. Reguły: {@link FeedRegression}.
 * Domyślnie {@code config/signatures/active.yaml}; inny plik (np. propozycję z threatFeed):
 * {@code ./gradlew signatureRegression -Pfeed=<ścieżka>}.
 */
class SignatureFeedRegressionTest {

    private static final Path FEED = Path.of(System.getProperty("signatureFeed", "config/signatures/active.yaml"));
    private static final Path BENIGN = Path.of(System.getProperty("signatureBenign", "config/signatures/benign.yaml"));

    @TestFactory
    Stream<DynamicNode> signatureFeed() throws IOException {
        SignatureFeed feed = SignatureFeed.parse(Files.readString(FEED, StandardCharsets.UTF_8));
        List<Check> checks = FeedRegression.run(feed, FeedRegression.loadBenign(BENIGN));
        var groups = new LinkedHashMap<String, List<Check>>();
        for (Check check : checks) {
            groups.computeIfAbsent(Objects.requireNonNullElse(check.signatureId(), "benign corpus"),
                    key -> new ArrayList<>()).add(check);
        }
        return groups.entrySet().stream().map(group -> DynamicContainer.dynamicContainer(group.getKey(),
                group.getValue().stream().map(check -> dynamicTest(
                        check.kind().name().toLowerCase() + ": " + check.text(),
                        () -> assertTrue(check.passed(), check.message() + " for: " + check.text())))));
    }
}
