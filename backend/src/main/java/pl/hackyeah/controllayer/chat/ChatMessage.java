package pl.hackyeah.controllayer.chat;

import jakarta.validation.constraints.NotBlank;

/** Odpowiada `ChatMessage` z frontend/src/api/types.ts. */
public record ChatMessage(@NotBlank String role, @NotBlank String content) {}
