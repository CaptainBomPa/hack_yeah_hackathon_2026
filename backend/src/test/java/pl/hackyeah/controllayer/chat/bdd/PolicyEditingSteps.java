package pl.hackyeah.controllayer.chat.bdd;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;

/**
 * Edits the live policy mid-scenario through the real {@code PolicyStore} — the same hot-reload
 * path the admin API uses — so a scenario can show the same prompt being allowed, redacted or
 * blocked differently as the policy changes underneath it, with no gateway restart.
 */
public class PolicyEditingSteps {

    private final BddWorld world;

    public PolicyEditingSteps(BddWorld world) {
        this.world = world;
    }

    @When("the admin removes model {string} from role {string}")
    public void theAdminRemovesModelFromRole(String model, String role) {
        world.removeModelFromRole(role, model);
    }

    @When("the admin grants model {string} to role {string}")
    public void theAdminGrantsModelToRole(String model, String role) {
        world.grantModelToRole(role, model);
    }

    @When("the admin disables model {string}")
    public void theAdminDisablesModel(String model) {
        world.setModelEnabledLive(model, false);
    }

    @When("the admin enables model {string}")
    public void theAdminEnablesModel(String model) {
        world.setModelEnabledLive(model, true);
    }

    @When("the admin disables guard {string}")
    public void theAdminDisablesGuard(String guardId) {
        world.setGuardEnabledLive(guardId, false);
    }

    @When("the admin enables guard {string}")
    public void theAdminEnablesGuard(String guardId) {
        world.setGuardEnabledLive(guardId, true);
    }

    @When("the admin sets guard {string} parameter {string} to {double}")
    public void theAdminSetsGuardParameterToADouble(String guardId, String param, double value) {
        world.setGuardParamLive(guardId, param, value);
    }

    @When("the admin sets guard {string} parameter {string} to {int} ms")
    public void theAdminSetsGuardParameterToMilliseconds(String guardId, String param, int value) {
        world.setGuardParamLive(guardId, param, value);
    }

    @When("the admin sets guard {string} parameter {string} to {string}")
    public void theAdminSetsGuardParameterToAString(String guardId, String param, String value) {
        world.setGuardParamLive(guardId, param, value);
    }

    @When("the admin adds recognizer {string} to guard {string}'s {string} list")
    public void theAdminAddsRecognizerToGuardsList(String recognizerId, String guardId, String paramName) {
        world.setGuardParamLive(guardId, paramName, List.of(recognizerId));
    }

    @When("the admin changes role {string}'s daily budget to {long} tokens")
    public void theAdminChangesRolesDailyBudgetToTokens(String role, long dailyTokens) {
        world.setRoleDailyBudgetLive(role, dailyTokens);
    }

    @When("the admin lowers the maximum input size to {int} tokens")
    public void theAdminLowersTheMaximumInputSizeToTokens(int maxTokens) {
        world.setMaxInputTokensLive(maxTokens);
    }

    @When("the admin tries to remove the admin role entirely")
    public void theAdminTriesToRemoveTheAdminRoleEntirely() {
        world.attemptInvalidPolicyEdit();
    }

    @When("the user sends the same prompt again")
    public void theUserSendsTheSamePromptAgain() {
        world.resendLastPrompt();
    }

    @Then("the policy edit is rejected")
    public void thePolicyEditIsRejected() {
        assertTrue(world.lastEditWasRejected(), "expected the invalid policy edit to be rejected");
    }
}
