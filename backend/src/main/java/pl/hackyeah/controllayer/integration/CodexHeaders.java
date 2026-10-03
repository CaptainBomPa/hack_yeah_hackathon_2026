package pl.hackyeah.controllayer.integration;

import java.util.List;
import org.springframework.http.HttpHeaders;

/** Request-scoped OAuth headers. Never persisted, logged or shared between callers. */
final class CodexHeaders {
    static final String GATEWAY_AUTH = "X-Control-Layer-Authorization";
    private static final List<String> REQUEST_HEADERS = List.of("Authorization", "ChatGPT-Account-ID",
            "OpenAI-Beta", "originator", "User-Agent", "session_id", "conversation_id", "version",
            "x-codex-installation-id", "x-codex-routing-hint", "x-codex-turn-state", "x-codex-turn-metadata",
            "x-codex-parent-thread-id", "x-codex-window-id", "x-openai-subagent",
            "x-openai-memgen-request", "x-responsesapi-include-timing-metrics");
    private static final List<String> RESPONSE_HEADERS = List.of("x-request-id", "retry-after",
            "x-codex-turn-state", "x-codex-routing-hint", "x-codex-primary-used-percent",
            "x-codex-primary-reset-at", "x-codex-primary-window-minutes", "x-codex-secondary-used-percent",
            "x-codex-secondary-reset-at", "x-codex-secondary-window-minutes");
    private final HttpHeaders headers;

    CodexHeaders(HttpHeaders incoming) {
        headers = copyAllowed(incoming, REQUEST_HEADERS);
    }

    boolean hasSubscriptionCredentials() {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        String account = headers.getFirst("ChatGPT-Account-ID");
        return single("Authorization") && single("ChatGPT-Account-ID") && authorization != null
                && authorization.startsWith("Bearer ") && !authorization.substring(7).isBlank()
                && !authorization.substring(7).startsWith("sk-")
                && authorization.substring(7).chars().noneMatch(Character::isWhitespace)
                && account != null && account.matches("[A-Za-z0-9_-]{1,128}");
    }

    void applyTo(HttpHeaders target) {
        headers.forEach((name, values) -> target.put(name, List.copyOf(values)));
    }

    static HttpHeaders responseHeaders(HttpHeaders incoming) {
        return copyAllowed(incoming, RESPONSE_HEADERS);
    }

    private boolean single(String name) {
        return headers.get(name) != null && headers.get(name).size() == 1;
    }

    private static HttpHeaders copyAllowed(HttpHeaders incoming, List<String> allowed) {
        var copy = new HttpHeaders();
        for (String name : allowed) {
            var values = incoming.get(name);
            if (values != null) copy.put(name, List.copyOf(values));
        }
        return copy;
    }

    @Override public String toString() { return "CodexHeaders[redacted]"; }
}
