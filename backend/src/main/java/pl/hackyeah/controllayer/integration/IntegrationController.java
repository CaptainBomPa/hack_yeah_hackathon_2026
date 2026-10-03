package pl.hackyeah.controllayer.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationController {
    private final IntegrationProperties properties;
    public IntegrationController(IntegrationProperties properties) { this.properties = properties; }

    /** Protected by the existing admin security rule; never exposes credentials. */
    @GetMapping("/api/integration")
    public Map<String, Object> integration() {
        List<Map<String, Object>> active = new ArrayList<>();
        if (properties.webEnabled()) {
            active.add(Map.of("name", "web", "protocol", "chat-completions", "endpoint", "/v1/chat/completions",
                    "bufferedStreaming", false, "authentication", "gateway-account"));
        }
        if (properties.codexEnabled()) {
            active.add(Map.of("name", "codex", "protocol", "responses", "endpoint", "/v1/responses",
                    "bufferedStreaming", true, "authentication", "chatgpt-subscription"));
        }
        return Map.of("integrations", active, "providerConfigured", true);
    }
}
