package pl.hackyeah.controllayer.threatfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.hackyeah.controllayer.guard.signature.FeedRegression;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed;
import pl.hackyeah.controllayer.guard.signature.SignatureFeedGuard;

/** Na prawdziwych odpowiedziach OSV zapisanych w src/test/resources/threatfeed/ (tryb offline). */
class ThreatFeedToolTest {

    private static final Path FIXTURES = Path.of("src/test/resources/threatfeed");
    private static final WatchedPackage MCP_REMOTE = new WatchedPackage("npm", "mcp-remote", List.of());
    private static final WatchedPackage OLLAMA =
            new WatchedPackage("Go", "github.com/ollama/ollama", List.of("ollama/ollama"));

    private final OsvSource offline = new OsvSource(FIXTURES, true);

    @Test
    void parsesOsvAdvisoryAndClassifiesCommandInjectionAsPayload() throws Exception {
        List<Advisory> advisories = Advisory.fromOsv(offline.vulns(MCP_REMOTE), MCP_REMOTE);
        assertEquals(1, advisories.size());
        Advisory advisory = advisories.getFirst();
        assertEquals("GHSA-6xpm-ggf7-wc3p", advisory.id());
        assertEquals("CVE-2025-6514", advisory.cve());
        assertEquals("CRITICAL", advisory.severity());
        assertEquals(List.of("CWE-78"), advisory.cwes());
        assertTrue(advisory.ranges().getFirst().contains("0.1.15"));
        assertFalse(advisory.ranges().getFirst().contains("0.1.16"));
        assertEquals(new Triage.Result("command-injection", Triage.View.PAYLOAD), Triage.classify(advisory.cwes()));
    }

    @Test
    void deduplicatesAliasedRecordsPreferringGhsa() throws Exception {
        List<Advisory> advisories = Advisory.fromOsv(offline.vulns(OLLAMA), OLLAMA);
        assertFalse(advisories.isEmpty());
        assertTrue(advisories.stream().allMatch(a -> a.id().startsWith("GHSA-")),
                "GO-* duplicates of GHSA records should be dropped");
        assertTrue(advisories.stream().anyMatch(a -> a.allIds().contains("CVE-2024-37032")));
    }

    @Test
    void componentFlawsAreNotPayloadVisible() {
        assertEquals(Triage.View.COMPONENT_ONLY, Triage.classify(List.of("CWE-400")).view());
        assertEquals("unclassified", Triage.classify(List.of()).threatClass());
    }

    @Test
    void generatedPackageSignatureIsValidAndItsTestsPass() throws Exception {
        for (WatchedPackage pkg : List.of(MCP_REMOTE, OLLAMA)) {
            List<Advisory> advisories = Advisory.fromOsv(offline.vulns(pkg), pkg);
            String yaml = "signatures:\n" + ThreatFeedTool.packageSignature(pkg, advisories, advisories);
            SignatureFeed feed = SignatureFeed.parse(yaml);
            var signature = feed.signatures().getFirst();
            assertEquals(pkg.signatureId(), signature.id());
            assertFalse(signature.positive().isEmpty(), pkg + " positive");
            assertFalse(signature.negative().isEmpty(), pkg + " negative");
            assertTrue(FeedRegression.run(feed, List.of()).stream().allMatch(FeedRegression.Check::passed), pkg.toString());
        }
    }

    @Test
    void generatedSignatureBlocksMcpServerInfoFromHandshake() throws Exception {
        List<Advisory> advisories = Advisory.fromOsv(offline.vulns(MCP_REMOTE), MCP_REMOTE);
        SignatureFeed feed = SignatureFeed.parse("signatures:\n" + ThreatFeedTool.packageSignature(MCP_REMOTE, advisories, advisories));
        String handshake = "{\"serverInfo\":{\"name\":\"mcp-remote\",\"version\":\"0.1.10\"}}";
        assertEquals(1, SignatureFeedGuard.hits(feed, handshake, Set.of()).size());
    }

    @Test
    void reportFlagsProposalThatBreaksRegressionAndKeepsGoodOneReady() {
        SignatureFeed active = SignatureFeed.parse("""
                signatures:
                  - { id: SIG-OLD, action: block, match: { type: contains, phrases: [CANARY-OLD] },
                      tests: { positive: [CANARY-OLD], negative: [hello] } }
                """);
        SignatureFeed proposed = SignatureFeed.parse("""
                signatures:
                  - { id: SIG-GOOD, action: block, match: { type: contains, phrases: [CANARY-NEW] },
                      tests: { positive: [CANARY-NEW], negative: [hello] } }
                  - { id: SIG-BROAD, action: block, match: { type: contains, phrases: [pickle] },
                      tests: { positive: [pickle file], negative: [json file] } }
                """);
        var regression = ThreatFeedTool.regress(active, proposed,
                List.of("Explain how pickle differs from JSON for saving data."));

        assertTrue(regression.activeFailures().isEmpty());
        assertTrue(regression.failuresOf("SIG-GOOD").isEmpty());
        assertEquals(1, regression.failuresOf("SIG-BROAD").size());
        String summary = ThreatFeedTool.summary(regression, java.util.Map.of("SIG-GOOD", "", "SIG-BROAD", ""));
        assertTrue(summary.contains("`SIG-GOOD`: gotowa"));
        assertTrue(summary.contains("`SIG-BROAD`: **NIE wklejaj**"));
    }

    @Test
    void bumpPatch() {
        assertEquals("0.20.3", ThreatFeedTool.bumpPatch("0.20.2"));
        assertEquals("2.1", ThreatFeedTool.bumpPatch("2.0"));
    }
}
