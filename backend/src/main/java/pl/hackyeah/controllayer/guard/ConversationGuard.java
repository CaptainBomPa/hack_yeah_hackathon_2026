package pl.hackyeah.controllayer.guard;

import java.util.ArrayList;
import java.util.List;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.guard.GuardChainResult.Action;
import pl.hackyeah.controllayer.policy.ActivePolicy;

/**
 * Guardy INPUT dla całej rozmowy, niezależnie od protokołu (chat completions, w przyszłości Codex).
 * Klient wysyła pełną, surową historię; serwer ją czyści, bez żadnego stanu:
 * <ul>
 *   <li>element <b>bieżący</b> (nowy prompt) decyduje o akcji żądania — może je zablokować;</li>
 *   <li><b>historia</b> nie zmienia akcji, ale model nie dostaje jej surowej treści: redakcja jest
 *       stosowana, a treść, którą guard by zablokował, zastępuje {@link #placeholder}.</li>
 * </ul>
 * Wyniki dla historii pochodzą zwykle z cache ({@link GuardChain#runCached}).
 */
public class ConversationGuard {

    public static final String HISTORY_POLICY = "input.history";

    public record Item(String text, boolean current) {}

    /** `texts`: treści dla modelu, w kolejności wejścia. `blockedBy` tylko przy blokadzie. */
    public record Result(Action action, String blockedBy, List<String> texts, List<ControlTrace> trace) {
        public boolean blocked() {
            return action == Action.BLOCK;
        }
    }

    private final GuardChain guardChain;

    public ConversationGuard(GuardChain guardChain) {
        this.guardChain = guardChain;
    }

    public static String placeholder(String guardId) {
        return "[removed by LLMinator: " + guardId + "]";
    }

    public Result check(ActivePolicy policy, String requestId, List<Item> items) {
        var texts = new ArrayList<String>();
        var trace = new ArrayList<ControlTrace>();
        var action = Action.ALLOW;
        int history = 0;
        int fromCache = 0;
        int cleaned = 0;

        for (Item item : items) {
            var run = guardChain.runCached(policy, Stage.INPUT, new GuardContext(requestId, item.text(), null, null));
            GuardChainResult result = run.result();
            if (item.current()) {
                trace.addAll(result.trace());
                if (result.blocked()) {
                    return new Result(Action.BLOCK, result.blockedBy(), texts, trace);
                }
                if (result.action() == Action.REDACT) {
                    action = Action.REDACT;
                }
                texts.add(result.text());
            } else {
                history++;
                fromCache += run.cacheHit() ? 1 : 0;
                cleaned += result.action() == Action.ALLOW ? 0 : 1;
                texts.add(result.blocked() ? placeholder(result.blockedBy()) : result.text());
            }
        }
        if (history > 0) {
            trace.addFirst(new ControlTrace(HISTORY_POLICY, "deterministic",
                    (cleaned > 0 ? Action.REDACT : Action.ALLOW).wire(), 0,
                    history + " earlier messages re-checked (" + fromCache + " from cache, " + cleaned + " cleaned)"));
        }
        return new Result(action, null, texts, trace);
    }
}
