package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.util.List;

/** Drives SIG-FEED's hot-reloadable feed file — see signature_feed.feature. */
public class SignatureFeedSteps {

    private final BddWorld world;

    public SignatureFeedSteps(BddWorld world) {
        this.world = world;
    }

    @Given("a signature feed file containing a blocking signature for {string}")
    public void aSignatureFeedFileContainingABlockingSignatureFor(String phrase) {
        world.useCustomSignatureFeed(blockingFeed(phrase));
    }

    @Given("a signature feed file containing a monitor-only signature for {string}")
    public void aSignatureFeedFileContainingAMonitorOnlySignatureFor(String phrase) {
        world.useCustomSignatureFeed("""
                feed_version: "test-monitor"
                signatures:
                  - id: SIG-TEST-MONITOR
                    title: "Test monitor signature"
                    action: monitor
                    match:
                      type: contains
                      phrases: ["%s"]
                """.formatted(phrase));
    }

    @Given("a signature feed file with no signatures at all")
    public void aSignatureFeedFileWithNoSignaturesAtAll() {
        world.useCustomSignatureFeed("""
                feed_version: "test-empty"
                signatures: []
                """);
    }

    @Given("a signature feed file that is not valid YAML for a feed")
    public void aSignatureFeedFileThatIsNotValidYamlForAFeed() {
        world.useCustomSignatureFeed("""
                feed_version: "broken"
                signatures:
                  - id: SIG-TEST-BROKEN
                    title: "missing the required match block"
                    action: block
                """);
    }

    @When("the feed file is updated to add a blocking signature for {string}")
    public void theFeedFileIsUpdatedToAddABlockingSignatureFor(String phrase) {
        world.updateSignatureFeedFile(blockingFeed(phrase));
    }

    @When("the feed file is overwritten with content that is not valid YAML for a feed")
    public void theFeedFileIsOverwrittenWithContentThatIsNotValidYamlForAFeed() {
        world.updateSignatureFeedFile("""
                feed_version: "broken"
                signatures:
                  - id: SIG-TEST-BROKEN
                    title: "missing the required match block"
                    action: block
                """);
    }

    @When("the feed file is deleted")
    public void theFeedFileIsDeleted() {
        world.deleteSignatureFeedFile();
    }

    @When("the admin disables signature {string} via guard {string}")
    public void theAdminDisablesSignatureViaGuard(String signatureId, String guardId) {
        world.setGuardParamLive(guardId, "disabledSignatures", List.of(signatureId));
    }

    private static String blockingFeed(String phrase) {
        return """
                feed_version: "test-block"
                signatures:
                  - id: SIG-TEST-CANARY
                    title: "Test canary signature"
                    action: block
                    match:
                      type: contains
                      phrases: ["%s"]
                """.formatted(phrase);
    }
}
