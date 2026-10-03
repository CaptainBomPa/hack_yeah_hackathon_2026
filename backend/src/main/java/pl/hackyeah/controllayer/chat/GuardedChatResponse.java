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
        String policyHash,
        String redactedPrompt, // ostatni prompt użytkownika po redakcji; null, gdy nic nie zredagowano
        ChatLatency latency) { // czasy żądania; uzupełniane na końcu, bo totalMs znamy dopiero wtedy

    public GuardedChatResponse {
        trace = List.copyOf(trace);
    }

    public static GuardedChatResponse allow(
            String requestId, ChatMessage message, Usage usage, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "allow", message, null, trace, usage, budget, null, null, null, null);
    }

    public static GuardedChatResponse redact(
            String requestId, ChatMessage message, Usage usage, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "redact", message, null, trace, usage, budget, null, null, null, null);
    }

    public static GuardedChatResponse block(
            String requestId, String blockedBy, List<ControlTrace> trace, BudgetUsage budget) {
        return new GuardedChatResponse(requestId, "block", null, blockedBy, trace, null, budget, null, null, null, null);
    }

    public static GuardedChatResponse block(String requestId, String blockedBy, List<ControlTrace> trace) {
        return block(requestId, blockedBy, trace, null);
    }

    /** Ta sama odpowiedź z oznaczeniem wersji polityki, która ją wyprodukowała. */
    public GuardedChatResponse withPolicy(long version, String hash) {
        return new GuardedChatResponse(requestId, action, message, blockedBy, trace, usage, budget, version, hash,
                redactedPrompt, latency);
    }

    /**
     * Ta sama odpowiedź z promptem po redakcji. Klient wysyła go w historii zamiast oryginału, żeby
     * kolejne pytania nie były redagowane (ani blokowane po zmianie polityki) przez tę samą starą wiadomość.
     */
    public GuardedChatResponse withRedactedPrompt(String prompt) {
        return new GuardedChatResponse(requestId, action, message, blockedBy, trace, usage, budget, policyVersion,
                policyHash, prompt, latency);
    }

    /** Ta sama odpowiedź ze zmierzonymi czasami żądania. */
    public GuardedChatResponse withLatency(ChatLatency measured) {
        return new GuardedChatResponse(requestId, action, message, blockedBy, trace, usage, budget, policyVersion,
                policyHash, redactedPrompt, measured);
    }
}
