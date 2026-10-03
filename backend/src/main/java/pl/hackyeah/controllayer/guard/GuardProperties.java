package pl.hackyeah.controllayer.guard;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja guardów z `control-layer.guards`. Guard bez wpisu w `rules` jest wyłączony;
 * wpis bez `enabled` jest włączony.
 */
@ConfigurationProperties(prefix = "control-layer.guards")
public record GuardProperties(Boolean enabled, Map<String, Rule> rules) {

    public GuardProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (rules == null) {
            rules = Map.of();
        }
    }

    /**
     * @param enabled czy guard jest aktywny (domyślnie true, gdy wpis istnieje).
     * @param order   kolejność w łańcuchu, mniejsza = wcześniej (domyślnie 100).
     * @param params  parametry specyficzne dla guarda (progi, listy).
     */
    public record Rule(Boolean enabled, Integer order, Map<String, Object> params) {

        public Rule {
            if (enabled == null) {
                enabled = true;
            }
            if (order == null) {
                order = 100;
            }
            if (params == null) {
                params = Map.of();
            }
        }
    }
}
