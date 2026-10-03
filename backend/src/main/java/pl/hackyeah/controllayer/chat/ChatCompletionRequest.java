package pl.hackyeah.controllayer.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Ciało POST /v1/chat/completions — zgodne z kształtem OpenAI chat-completions na wejściu
 * (VISION.md §7). `model` to dokładny tag u providera, bez warstwy aliasów. `maxTokens` jest
 * opcjonalny; klient może nie wysyłać go wcale — gateway i tak nadpisuje/clampuje tę wartość
 * przed wysłaniem do providera (BUDGET-001, docs/deterministic/14-token-budget-quotas.md §6).
 */
public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        @JsonProperty("max_tokens") @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxTokens) {

    public ChatCompletionRequest(String model, List<ChatMessage> messages) {
        this(model, messages, null);
    }

    public ChatCompletionRequest withMaxTokens(int clampedMaxTokens) {
        return new ChatCompletionRequest(model, messages, clampedMaxTokens);
    }
}
