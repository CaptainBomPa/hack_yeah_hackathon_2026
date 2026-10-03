package pl.hackyeah.controllayer.budget;

import java.util.List;
import pl.hackyeah.controllayer.chat.ChatMessage;

/**
 * Konserwatywna estymata tokenów bez tokenizera (docs/deterministic/14-token-budget-quotas.md
 * §4.5: "ceil(chars/3) jest zgrubna, ale konserwatywna i O(1)"). Służy tylko do pre-flight
 * (limit wejścia, rezerwacja worst-case) — rozliczenie używa prawdziwego `usage` z Ollamy.
 */
final class TokenEstimator {

    private TokenEstimator() {}

    static long estimate(String text) {
        return text == null || text.isEmpty() ? 0 : Math.ceilDiv(text.length(), 3);
    }

    static long estimateMessages(List<ChatMessage> messages) {
        return messages.stream().mapToLong(message -> estimate(message.content())).sum();
    }
}
