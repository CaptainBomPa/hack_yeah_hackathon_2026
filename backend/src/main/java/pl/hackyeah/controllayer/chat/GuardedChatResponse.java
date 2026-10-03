package pl.hackyeah.controllayer.chat;

import java.util.List;

/**
 * Odpowiedź gatewaya na POST /v1/chat/completions — dokładnie `GuardedChatResponse` z
 * frontend/src/api/types.ts. Dopóki decision pipeline (VISION.md §9, kroki 1-4) nie istnieje,
 * jedyną realną kontrolą w `trace` jest allowlista modeli; reszta dojdzie, gdy inni podłączą
 * swoje filtry przed/po wywołaniu modelu.
 */
public record GuardedChatResponse(
        String requestId,
        String action, // "allow" | "redact" | "block"
        ChatMessage message, // brak, gdy action == "block"
        String blockedBy,
        List<ControlTrace> trace,
        Usage usage) {

    public static GuardedChatResponse allow(
            String requestId, ChatMessage message, Usage usage, List<ControlTrace> trace) {
        return new GuardedChatResponse(requestId, "allow", message, null, trace, usage);
    }

    public static GuardedChatResponse block(
            String requestId, String blockedBy, List<ControlTrace> trace) {
        return new GuardedChatResponse(requestId, "block", null, blockedBy, trace, null);
    }
}
