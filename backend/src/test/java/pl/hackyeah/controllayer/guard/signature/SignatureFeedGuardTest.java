package pl.hackyeah.controllayer.guard.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Verdict;

class SignatureFeedGuardTest {

    private static final String FEED = """
            feed_version: "t1"
            signatures:
              - id: SIG-TEST-BLOCK
                action: block
                match: { type: contains, phrases: ["CANARY-BLOCK"] }
              - id: SIG-TEST-MONITOR
                action: monitor
                match: { type: regex, pattern: "CANARY-MON\\\\d" }
              - id: SIG-TEST-PKG
                action: block
                match:
                  type: package-version
                  names: [mcp-remote]
                  ranges: [[{introduced: "0.0.5"}, {fixed: "0.1.16"}]]
            """;

    @TempDir
    Path dir;

    private final SignatureFeedGuard guard = new SignatureFeedGuard();

    @Test
    void allowsTextWithoutHits() throws IOException {
        assertInstanceOf(Verdict.Allow.class, check(write(FEED), "Napisz jedno zdanie o Warszawie."));
    }

    @Test
    void blocksAndReportsOnlySignatureIdsAndFeedVersion() throws IOException {
        var verdict = assertInstanceOf(Verdict.Block.class, check(write(FEED), "echo CANARY-BLOCK"));
        assertEquals("signature SIG-TEST-BLOCK (feed t1)", verdict.reason());
    }

    @Test
    void monitorSignatureReportsWithoutBlocking() throws IOException {
        var verdict = assertInstanceOf(Verdict.Allow.class, check(write(FEED), "value CANARY-MON7"));
        assertEquals("monitor signature SIG-TEST-MONITOR (feed t1)", verdict.detail());
    }

    @Test
    void zeroWidthCharactersDoNotEvadeSignatures() throws IOException {
        assertInstanceOf(Verdict.Block.class, check(write(FEED), "CANARY-​BLOCK"));
    }

    @Test
    void disabledSignaturesFromPolicyAreSkipped() throws IOException {
        Path feed = write(FEED);
        var settings = new GuardSettings(true, Map.of("feed", feed.toString(), "checkIntervalMs", 0,
                "disabledSignatures", "SIG-TEST-BLOCK"));
        assertInstanceOf(Verdict.Allow.class, guard.check(ctx("echo CANARY-BLOCK"), settings));
    }

    @Test
    void packageVersionMatchesOnlyVulnerableVersions() throws IOException {
        Path feed = write(FEED);
        assertInstanceOf(Verdict.Block.class, check(feed, "npx mcp-remote@0.1.15 https://example.com/sse"));
        assertInstanceOf(Verdict.Block.class, check(feed, "{\"name\": \"mcp-remote\", \"version\": \"0.0.5\"}"));
        assertInstanceOf(Verdict.Allow.class, check(feed, "npx mcp-remote@0.1.16 https://example.com/sse"));
        assertInstanceOf(Verdict.Allow.class, check(feed, "npx mcp-remote@0.0.4"));
        assertInstanceOf(Verdict.Allow.class, check(feed, "npx my-mcp-remote@0.1.0"));
    }

    @Test
    void hotReloadPicksUpNewSignatureWithoutRestart() throws IOException {
        Path feed = write(FEED);
        assertInstanceOf(Verdict.Allow.class, check(feed, "NEW-ATTACK-PATTERN"));
        rewrite(feed, FEED.replace("CANARY-BLOCK", "NEW-ATTACK-PATTERN"));
        assertInstanceOf(Verdict.Block.class, check(feed, "NEW-ATTACK-PATTERN"));
    }

    @Test
    void invalidEditKeepsLastGoodVersion() throws IOException {
        Path feed = write(FEED);
        var loader = new SignatureFeedLoader(feed, 0);
        assertEquals("t1", loader.current().feedVersion());
        rewrite(feed, FEED.replace("CANARY-MON\\\\d", "([unclosed"));
        assertEquals("t1", loader.current().feedVersion());
        assertNotNull(loader.lastError());
        rewrite(feed, FEED.replace("\"t1\"", "\"t2\""));
        assertEquals("t2", loader.current().feedVersion());
        assertNull(loader.lastError());
    }

    @Test
    void invalidFeedWithoutPreviousVersionFailsClosed() throws IOException {
        Path feed = write("signatures: [{id: SIG-BAD, action: block, match: {type: regex, pattern: '(['}}]");
        assertThrows(IllegalStateException.class, () -> new SignatureFeedLoader(feed, 0).current());
    }

    @Test
    void missingFeedMeansNoSignatures() {
        assertTrue(new SignatureFeedLoader(dir.resolve("missing.yaml"), 0).current().signatures().isEmpty());
    }

    @Test
    void parserRejectsDuplicateIdsAndUnknownActions() {
        assertThrows(IllegalArgumentException.class, () -> SignatureFeed.parse("""
                signatures:
                  - { id: SIG-A, action: block, match: { type: contains, phrases: [x] } }
                  - { id: SIG-A, action: block, match: { type: contains, phrases: [y] } }
                """));
        assertThrows(IllegalArgumentException.class, () -> SignatureFeed.parse(
                "signatures: [{ id: SIG-A, action: allow, match: { type: contains, phrases: [x] } }]"));
    }

    @Test
    void versionRangeFollowsOsvSemantics() {
        var fixed = VersionRange.fromEvents(List.of(Map.of("introduced", "0"), Map.of("fixed", "0.1.34")));
        assertTrue(fixed.contains("0.1.33"));
        assertFalse(fixed.contains("0.1.34"));
        assertTrue(fixed.contains("0.1.9"));
        var lastAffected = VersionRange.fromEvents(List.of(Map.of("introduced", "1.0"), Map.of("last_affected", "1.2.3")));
        assertTrue(lastAffected.contains("1.2.3"));
        assertFalse(lastAffected.contains("1.2.4"));
        assertFalse(lastAffected.contains("0.9"));
    }

    private Verdict check(Path feed, String text) {
        var settings = new GuardSettings(true, Map.of("feed", feed.toString(), "checkIntervalMs", 0));
        return guard.check(ctx(text), settings);
    }

    private static GuardContext ctx(String text) {
        return new GuardContext("req-1", text, null, null);
    }

    private Path write(String content) throws IOException {
        Path feed = Files.createTempFile(dir, "feed", ".yaml");
        Files.writeString(feed, content, StandardCharsets.UTF_8);
        return feed;
    }

    /** Zapis z jawnie przesuniętą datą modyfikacji — niezależnie od rozdzielczości zegara systemu plików. */
    private static void rewrite(Path feed, String content) throws IOException {
        FileTime previous = Files.getLastModifiedTime(feed);
        Files.writeString(feed, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(feed, FileTime.from(previous.toInstant().plusSeconds(5)));
    }
}
