package pl.hackyeah.controllayer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCatalogTest {

    private final ModelCatalog catalog = new ModelCatalog(new ModelCatalogProperties(
            List.of(
                    new ModelCatalogProperties.ModelEntry("allowed-model", "http://ollama:11434", true),
                    new ModelCatalogProperties.ModelEntry("disabled-model", "http://ollama:11434", false)),
            Duration.ofSeconds(30)));

    @Test
    void resolvesAnAllowedEnabledModel() {
        var resolved = catalog.resolveAllowed("allowed-model");
        assertTrue(resolved.isPresent());
        assertEquals("http://ollama:11434", resolved.get().baseUrl());
    }

    @Test
    void treatsADisabledModelAsNotResolvable() {
        assertTrue(catalog.resolveAllowed("disabled-model").isEmpty());
    }

    @Test
    void treatsAnUnknownModelAsNotResolvable() {
        assertTrue(catalog.resolveAllowed("never-configured").isEmpty());
    }
}
