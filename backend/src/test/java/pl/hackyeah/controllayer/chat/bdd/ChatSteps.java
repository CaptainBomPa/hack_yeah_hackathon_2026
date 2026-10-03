package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.When;

/** The action of the scenario: a user sends a prompt to the gateway. */
public class ChatSteps {

    private final BddWorld world;

    public ChatSteps(BddWorld world) {
        this.world = world;
    }

    @When("the user sends the prompt {string}")
    public void theUserSendsThePrompt(String text) {
        world.sendPrompt(text);
    }

    @When("the user sends the prompt {string} to model {string}")
    public void theUserSendsThePromptToModel(String text, String model) {
        world.sendPromptWithModel(model, text);
    }

    @When("an unauthenticated user sends the prompt {string}")
    public void anUnauthenticatedUserSendsThePrompt(String text) {
        world.sendPromptUnauthenticated(text);
    }

    @When("the user sends a very long prompt")
    public void theUserSendsAVeryLongPrompt() {
        world.sendPrompt("a".repeat(1000));
    }
}
