package pl.hackyeah.controllayer.chat;

/** Odpowiada `usage` z frontend/src/api/types.ts. */
public record Usage(int promptTokens, int completionTokens) {}
