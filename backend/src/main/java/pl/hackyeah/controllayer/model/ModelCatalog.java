package pl.hackyeah.controllayer.model;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Egzekwuje allowlistę modeli (VISION.md §4). Model niewymieniony w katalogu albo wyłączony
 * jest traktowany identycznie jak nieznany — gateway nie rozróżnia tych dwóch przypadków
 * w odpowiedzi do klienta, żeby nie ujawniać, które modele istnieją, ale są zablokowane.
 */
@Service
public class ModelCatalog {

    private final Map<String, ModelCatalogProperties.ModelEntry> byTag;
    private final java.time.Duration modelTimeout;

    public ModelCatalog(ModelCatalogProperties properties) {
        this.byTag = properties.models().stream()
                .collect(Collectors.toMap(ModelCatalogProperties.ModelEntry::tag, Function.identity()));
        this.modelTimeout = properties.modelTimeout();
    }

    public Optional<ModelCatalogProperties.ModelEntry> resolveAllowed(String tag) {
        return Optional.ofNullable(byTag.get(tag)).filter(ModelCatalogProperties.ModelEntry::enabled);
    }

    public java.time.Duration modelTimeout() {
        return modelTimeout;
    }
}
