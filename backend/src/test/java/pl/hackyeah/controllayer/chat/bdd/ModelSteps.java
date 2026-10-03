package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.Given;

/** Controls the fake protected model (Ollama) — this is where we "mock" the LLM's answer. */
public class ModelSteps {

    private final BddWorld world;

    public ModelSteps(BddWorld world) {
        this.world = world;
    }

    @Given("the model responds with {string}")
    public void theModelRespondsWith(String content) {
        world.modelResponds(content);
    }

    @Given("the model is unavailable")
    public void theModelIsUnavailable() {
        world.modelIsUnavailable();
    }
}
