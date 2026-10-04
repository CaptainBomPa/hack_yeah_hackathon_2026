package pl.hackyeah.controllayer.chat.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Then;

/** The expected outcome: action, HTTP status, response content, what landed in the audit log. */
public class AssertionSteps {

    private final BddWorld world;

    public AssertionSteps(BddWorld world) {
        this.world = world;
    }

    @Then("the response action is {string}")
    public void theResponseActionIs(String expected) {
        assertNotNull(world.lastResponse(), "no response from the gateway");
        assertEquals(expected, world.lastResponse().action());
    }

    @Then("it is blocked by {string}")
    public void itIsBlockedBy(String policyId) {
        assertEquals(policyId, world.lastResponse().blockedBy());
    }

    @Then("the HTTP status is {int}")
    public void theHttpStatusIs(int status) {
        assertEquals(status, world.lastHttpStatus());
    }

    @Then("the response does not contain {string}")
    public void theResponseDoesNotContain(String fragment) {
        var message = world.lastResponse().message();
        String content = message == null ? "" : message.content();
        assertFalse(content.contains(fragment), "response should not contain: " + fragment);
    }

    @Then("the response contains {string}")
    public void theResponseContains(String fragment) {
        var message = world.lastResponse().message();
        String content = message == null ? "" : message.content();
        assertTrue(content.contains(fragment), "response should contain: " + fragment);
    }

    @Then("the audit log contains exactly {int} entry/entries")
    public void theAuditLogContainsExactlyEntries(int count) {
        assertEquals(count, world.audited().size());
    }
}
