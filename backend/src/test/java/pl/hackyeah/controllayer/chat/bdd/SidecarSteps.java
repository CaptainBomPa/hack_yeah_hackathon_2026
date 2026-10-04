package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.Given;

/** Controls the fake semantic sidecar (SEM-001). */
public class SidecarSteps {

    private final BddWorld world;

    public SidecarSteps(BddWorld world) {
        this.world = world;
    }

    @Given("the semantic sidecar will score this prompt {double}")
    public void theSemanticSidecarWillScoreThisPrompt(double score) {
        world.sidecarScores(score);
    }

    @Given("the semantic sidecar is unavailable")
    public void theSemanticSidecarIsUnavailable() {
        world.sidecarIsUnavailable();
    }

    @Given("the semantic sidecar reports scores {double} and {double} for this prompt")
    public void theSemanticSidecarReportsScoresForThisPrompt(double first, double second) {
        world.sidecarReportsScores(first, second);
    }

    @Given("the semantic sidecar returns no results")
    public void theSemanticSidecarReturnsNoResults() {
        world.sidecarReturnsNoResults();
    }

    @Given("the semantic sidecar returns an incomplete check")
    public void theSemanticSidecarReturnsAnIncompleteCheck() {
        world.sidecarReturnsIncompleteCheck();
    }

    @Given("the semantic sidecar responds slower than its timeout")
    public void theSemanticSidecarRespondsSlowerThanItsTimeout() {
        world.setSemanticTimeoutMs(300);
        world.sidecarRespondsSlowly(1500);
    }

    @Given("the semantic block threshold is {double}")
    public void theSemanticBlockThresholdIs(double threshold) {
        world.setSemanticBlockThreshold(threshold);
    }

    @Given("the semantic failure mode is {string}")
    public void theSemanticFailureModeIs(String mode) {
        world.setSemanticFailureMode(mode);
    }
}
