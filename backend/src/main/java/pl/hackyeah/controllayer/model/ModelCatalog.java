package pl.hackyeah.controllayer.model;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import pl.hackyeah.controllayer.policy.PolicySource;

/**
 * Egzekwuje allowlistę modeli (VISION.md §4). Katalog (tag → adres providera) to konfiguracja
 * wdrożenia w application.yml; to, czy model jest dopuszczony, decyduje aktywna polityka
 * (`models[].enabled`). Model niewymieniony w katalogu albo wyłączony jest traktowany identycznie
 * jak nieznany — gateway nie rozróżnia tych dwóch przypadków w odpowiedzi do klienta, żeby nie
 * ujawniać, które modele istnieją, ale są zablokowane.
 */
@Service
public class ModelCatalog {

    private final Map<String, ModelCatalogProperties.ModelEntry> byTag;
    private final java.time.Duration modelTimeout;
    private final PolicySource policySource;

    @Autowired
    public ModelCatalog(ModelCatalogProperties properties, PolicySource policySource) {
        this.byTag = properties.models().stream()
                .collect(Collectors.toMap(ModelCatalogProperties.ModelEntry::tag, Function.identity()));
        this.modelTimeout = properties.modelTimeout();
        this.policySource = policySource;
    }

    /** Dla testów: włączone są modele z `enabled: true` w katalogu. */
    public ModelCatalog(ModelCatalogProperties properties) {
        this(properties, PolicySource.fixed(PolicyDocument.fromConfig(null, properties, null, null)));
    }

    public Optional<ModelCatalogProperties.ModelEntry> resolveAllowed(String tag) {
        return resolveAllowed(policySource.current(), tag);
    }

    public Optional<ModelCatalogProperties.ModelEntry> resolveAllowed(ActivePolicy policy, String tag) {
        return Optional.ofNullable(byTag.get(tag)).filter(entry -> policy.document().modelEnabled(entry.tag()));
    }

    public java.time.Duration modelTimeout() {
        return modelTimeout;
    }
}
