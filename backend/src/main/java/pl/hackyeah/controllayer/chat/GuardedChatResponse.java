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
        Usage usage,
        BudgetUsage budget, // null, gdy rola wywołującego nie ma skonfigurowanego limitu
        Long policyVersion, // wersja polityki, która podjęła decyzję (docs/policy-management-plan.md)
        String policyHash) {

    public static GuardedChatResponse allow(
            String requestId, ChatMessage message, Usage usage, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "allow", message, null, trace, usage, budget, null, null);
    }

    public static GuardedChatResponse redact(
            String requestId, ChatMessage message, Usage usage, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "redact", message, null, trace, usage, budget, null, null);
    }

    public static GuardedChatResponse block(
            String requestId, String blockedBy, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "block", null, blockedBy, trace, null, budget, null, null);
    }

    public static GuardedChatResponse block(String requestId, String blockedBy, List<ControlTrace> trace) {
        return block(requestId, blockedBy, trace, null);
    }

    /** Ta sama odpowiedź z oznaczeniem wersji polityki, która ją wyprodukowała. */
    public GuardedChatResponse withPolicy(long version, String hash) {
        return new GuardedChatResponse(requestId, action, message, blockedBy, trace, usage, budget, version, hash);
    }
}
