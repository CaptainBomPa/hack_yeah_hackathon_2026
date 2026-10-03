package pl.hackyeah.controllayer.chat.upstream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import pl.hackyeah.controllayer.chat.ChatMessage;

/**
 * Kształt odpowiedzi OpenAI-compatible chat-completions, tak jak zwraca ją Ollama
 * (POST /v1/chat/completions na providerze). Mapujemy tylko pola, których potrzebujemy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatCompletionResponse(List<Choice> choices, UpstreamUsage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(ChatMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens) {}
}
