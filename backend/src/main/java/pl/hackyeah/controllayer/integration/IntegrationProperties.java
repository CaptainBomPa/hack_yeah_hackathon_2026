package pl.hackyeah.controllayer.integration;

import java.time.Duration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Integrations run side by side: web playground ({@code /v1/chat/completions}) and Codex CLI
 * ({@code /v1/responses}). Each can be switched off independently of the local/prod persistence profile.
 */
@ConfigurationProperties("control-layer.integration")
public record IntegrationProperties(@DefaultValue("true") boolean webEnabled, @DefaultValue("true") boolean codexEnabled,
        String codexBaseUrl, Duration timeout, int maxResponseBytes) {
    public static final String WEB_ENABLED = "control-layer.integration.web-enabled";
    public static final String CODEX_ENABLED = "control-layer.integration.codex-enabled";

    public IntegrationProperties {
        codexBaseUrl = codexBaseUrl == null ? "https://chatgpt.com/backend-api/codex" : codexBaseUrl;
        codexBaseUrl = codexBaseUrl.replaceAll("/+$", "");
        var uri = URI.create(codexBaseUrl);
        boolean subscription = codexBaseUrl.equals("https://chatgpt.com/backend-api/codex");
        boolean local = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!subscription && !local) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Codex upstream must be ChatGPT or a loopback test server");
        }
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 8 * 1024 * 1024 : maxResponseBytes;
    }

    // Configuration never contains a provider credential.
    @Override public String toString() {
        return "IntegrationProperties[web=" + webEnabled + ", codex=" + codexEnabled + "]";
    }
}
