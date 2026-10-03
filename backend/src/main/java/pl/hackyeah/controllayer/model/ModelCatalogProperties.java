package pl.hackyeah.controllayer.model;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Katalog modeli dozwolonych do wywołania przez gateway (VISION.md §4, kontrola
 * "allowlista modeli"). Na razie trzyma się w application.yml; docelowo może przenieść się do
 * policy.yaml razem z resztą polityk (VISION.md §9, krok 1).
 */
@ConfigurationProperties(prefix = "control-layer")
public record ModelCatalogProperties(List<ModelEntry> models, Duration modelTimeout) {

    public ModelCatalogProperties {
        if (models == null) {
            models = List.of();
        }
        if (modelTimeout == null) {
            modelTimeout = Duration.ofSeconds(60);
        }
    }

    /**
     * @param tag     dokładna nazwa modelu, tak jak wysyła ją klient i tak jak jest zarejestrowana
     *                u providera (np. w Ollamie) — bez warstwy aliasów (ustalone świadomie).
     * @param baseUrl adres providera obsługującego ten model (dziś zawsze Ollama).
     * @param enabled czy model jest dopuszczony do użycia; wyłączony = traktowany jak nieznany.
     */
    public record ModelEntry(String tag, String baseUrl, boolean enabled) {}
}
