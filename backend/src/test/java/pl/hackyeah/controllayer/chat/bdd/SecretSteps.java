package pl.hackyeah.controllayer.chat.bdd;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Builds prompts/model answers containing fake secrets (SEC-GITLEAKS) from {@link SecretFixtures}. */
public class SecretSteps {

    private final BddWorld world;

    public SecretSteps(BddWorld world) {
        this.world = world;
    }

    @When("the user sends a prompt containing a fake {string}")
    public void theUserSendsAPromptContainingAFake(String secretLabel) {
        world.sendPrompt("Here you go: " + SecretFixtures.byLabel(secretLabel) + " — please use it.");
    }

    @Given("the model responds with text containing a fake {string}")
    public void theModelRespondsWithTextContainingAFake(String secretLabel) {
        world.modelResponds("Sure, here it is: " + SecretFixtures.byLabel(secretLabel));
    }

    @Given("the model responds with a PESEL and a fake {string}")
    public void theModelRespondsWithAPeselAndAFake(String secretLabel) {
        world.modelResponds("Your PESEL is 44051401359 and your key is " + SecretFixtures.byLabel(secretLabel));
    }

    @Given("the model responds with text containing a fake {string} and a fake {string}")
    public void theModelRespondsWithTextContainingAFakeAndAFake(String firstLabel, String secondLabel) {
        world.modelResponds("Here: " + SecretFixtures.byLabel(firstLabel) + " and also " + SecretFixtures.byLabel(secondLabel));
    }

    @When("the user sends a prompt containing a card number and a fake {string}")
    public void theUserSendsAPromptContainingACardNumberAndAFake(String secretLabel) {
        world.sendPrompt("Card 4111 1111 1111 1111 and key " + SecretFixtures.byLabel(secretLabel));
    }

    @Then("the response does not contain a fake {string}")
    public void theResponseDoesNotContainAFake(String secretLabel) {
        var message = world.lastResponse().message();
        String content = message == null ? "" : message.content();
        assertFalse(content.contains(SecretFixtures.byLabel(secretLabel)),
                "response should not contain the fake " + secretLabel);
    }

    @When("the user sends a benign prompt about secrets")
    public void theUserSendsABenignPromptAboutSecrets() {
        world.sendPrompt(SecretFixtures.BENIGN_EN);
    }

    @When("the user sends a benign prompt that merely mentions secret variable names")
    public void theUserSendsABenignPromptThatMerelyMentionsSecretVariableNames() {
        world.sendPrompt(SecretFixtures.BENIGN_CODE_LIKE);
    }
}
