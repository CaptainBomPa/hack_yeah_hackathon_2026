package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.en.Given;

/** Sets up the caller (login + role) before a prompt is sent. */
public class UserSteps {

    private final BddWorld world;

    public UserSteps(BddWorld world) {
        this.world = world;
    }

    @Given("user {string} with role {string}")
    public void userWithRole(String login, String role) {
        world.loginAs(login, role);
    }
}
