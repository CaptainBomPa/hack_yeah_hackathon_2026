package pl.hackyeah.controllayer.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Ciało POST /v1/chat/completions — zgodne z kształtem OpenAI chat-completions na wejściu
 * (VISION.md §7). `model` to dokładny tag u providera, bez warstwy aliasów.
 */
public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages) {}
