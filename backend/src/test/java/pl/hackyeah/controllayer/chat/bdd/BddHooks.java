package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.After;

/** Shuts down the HTTP fakes (model/sidecar) after each scenario, so no ports are left open. */
public class BddHooks {

    private final BddWorld world;

    public BddHooks(BddWorld world) {
        this.world = world;
    }

    @After
    public void tearDown() {
        world.tearDown();
    }
}
