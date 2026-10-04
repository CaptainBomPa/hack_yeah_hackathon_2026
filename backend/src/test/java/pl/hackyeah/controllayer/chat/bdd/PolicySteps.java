package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.Given;

/** Builds the policy (`policy.yaml`) and budget limits for the scenario. */
public class PolicySteps {

    private final BddWorld world;

    public PolicySteps(BddWorld world) {
        this.world = world;
    }

    @Given("policy {string} allows model {string}")
    public void policyAllowsModel(String role, String model) {
        world.allowRoleModel(role, model);
    }

    @Given("the catalog also has model {string}")
    public void theCatalogAlsoHasModel(String model) {
        world.registerModelOnly(model);
    }

    @Given("policy {string} allows any model")
    public void policyAllowsAnyModel(String role) {
        world.allowRoleAnyModel(role);
    }

    @Given("role {string} has a daily budget limit of {long} tokens")
    public void roleHasADailyBudgetLimitOfTokens(String role, long tokens) {
        world.setRoleDailyBudget(role, tokens);
    }

    @Given("the maximum input size is {int} tokens")
    public void theMaximumInputSizeIsTokens(int maxTokens) {
        world.setMaxInputTokens(maxTokens);
    }

    @Given("the maximum output size is {int} tokens")
    public void theMaximumOutputSizeIsTokens(int maxTokens) {
        world.setMaxOutputTokens(maxTokens);
    }
}
