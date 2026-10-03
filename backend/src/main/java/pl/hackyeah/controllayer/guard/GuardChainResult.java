package pl.hackyeah.controllayer.guard;

import java.util.List;
import pl.hackyeah.controllayer.chat.ControlTrace;

/**
 * Wynik przejścia łańcucha. `text` to treść po ewentualnej redakcji; `blockedBy` to id guarda,
 * który przerwał łańcuch (tylko gdy `action == BLOCK`).
 */
public record GuardChainResult(Action action, String text, String blockedBy, List<ControlTrace> trace) {

    public enum Action {
        ALLOW,
        REDACT,
        BLOCK;

        /** Wartość zgodna z `ControlTrace.action` / `GuardedChatResponse.action`. */
        public String wire() {
            return name().toLowerCase();
        }
    }

    public boolean blocked() {
        return action == Action.BLOCK;
    }
}
