package pl.hackyeah.controllayer.model;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Katalog modeli dozwolonych do wywołania przez gateway (VISION.md §4, kontrola
 * "allowlista modeli"): modele Ollamy z application.yml + modele Codex CLI z codex-models.yml
 * (wszystkie z adresem upstreamu Codexa). `models()` zwraca już połączoną listę.
 */
@ConfigurationProperties(prefix = "control-layer")
public record ModelCatalogProperties(List<ModelEntry> models, Duration modelTimeout, CodexModels codexModels) {

    @ConstructorBinding
    public ModelCatalogProperties {
        var codex = codexModels == null || codexModels.tags() == null ? Stream.<ModelEntry>empty()
                : codexModels.tags().stream().map(tag -> new ModelEntry(tag, codexModels.baseUrl(), true));
        models = Stream.concat(models == null ? Stream.empty() : models.stream(), codex)
                .filter(m -> m.tag() != null && !m.tag().isBlank())
                .toList();
        if (modelTimeout == null) {
            modelTimeout = Duration.ofSeconds(60);
        }
    }

    public ModelCatalogProperties(List<ModelEntry> models, Duration modelTimeout) {
        this(models, modelTimeout, null);
    }

    /**
     * @param tag     dokładna nazwa modelu, tak jak wysyła ją klient i tak jak jest zarejestrowana
     *                u providera (np. w Ollamie) — bez warstwy aliasów (ustalone świadomie).
     * @param baseUrl adres providera obsługującego ten model (Ollama albo upstream Codexa).
     * @param enabled czy model jest dopuszczony do użycia; wyłączony = traktowany jak nieznany.
     */
    public record ModelEntry(String tag, String baseUrl, boolean enabled) {}

    /** Modele Codex CLI (`control-layer.codex-models` w codex-models.yml): jeden upstream, lista slugów. */
    public record CodexModels(String baseUrl, List<String> tags) {}
}
