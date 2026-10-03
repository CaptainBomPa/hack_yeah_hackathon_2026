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
}
