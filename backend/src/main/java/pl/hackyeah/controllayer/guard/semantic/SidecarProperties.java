package pl.hackyeah.controllayer.guard.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Adres sidecara semantycznego (`control-layer.sidecar.base-url`, nadpisywany przez SIDECAR_BASE_URL).
 * Progi, timeout i strategia awarii są parametrami guarda SEM-001 w `control-layer.guards.rules`.
 */
@ConfigurationProperties(prefix = "control-layer.sidecar")
public record SidecarProperties(String baseUrl) {

    public SidecarProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8001";
        }
    }
}
