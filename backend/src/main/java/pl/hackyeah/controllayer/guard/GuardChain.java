package pl.hackyeah.controllayer.guard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.guard.GuardChainResult.Action;

/**
 * Chain of responsibility: odpala po kolei włączone guardy danego etapu. `Allow` → dalej,
 * `Redact` → podmienia tekst i idzie dalej, `Block` → przerywa. Wyjątek w guardzie = Block
 * (fail-closed). Łańcuch jest budowany raz na starcie z beanów `Guard` i `GuardProperties`.
 */
@Service
public class GuardChain {

    private static final Logger log = LoggerFactory.getLogger(GuardChain.class);

    private record Entry(Guard guard, GuardSettings settings, int order) {}

    private final Map<Stage, List<Entry>> byStage = new EnumMap<>(Stage.class);

    public GuardChain(List<Guard> guards, GuardProperties properties) {
        var knownIds = new HashSet<String>();
        for (Guard guard : guards) {
            if (!knownIds.add(guard.id())) {
                throw new IllegalStateException("Duplicate guard id: " + guard.id());
            }
        }
        for (String configuredId : properties.rules().keySet()) {
            if (!knownIds.contains(configuredId)) {
                throw new IllegalStateException(
                        "control-layer.guards.rules." + configuredId + " has no matching Guard bean");
            }
        }

        for (Stage stage : Stage.values()) {
            byStage.put(stage, new ArrayList<>());
        }
        if (properties.enabled()) {
            for (Guard guard : guards) {
                var rule = properties.rules().get(guard.id());
                if (rule == null || !rule.enabled()) {
                    continue;
                }
                var entry = new Entry(guard, new GuardSettings(true, rule.params()), rule.order());
                guard.stages().forEach(stage -> byStage.get(stage).add(entry));
            }
        }
        byStage.values().forEach(entries -> entries.sort(
                Comparator.comparingInt(Entry::order).thenComparing(entry -> entry.guard().id())));
        byStage.forEach((stage, entries) -> log.info(
                "guards stage={} active={}", stage, entries.stream().map(e -> e.guard().id()).toList()));
    }

    public GuardChainResult run(Stage stage, GuardContext context) {
        var trace = new ArrayList<ControlTrace>();
        var current = context;
        var action = Action.ALLOW;

        for (Entry entry : byStage.get(stage)) {
            String id = entry.guard().id();
            long startedAt = System.nanoTime();
            Verdict verdict;
            try {
                verdict = entry.guard().check(current, entry.settings());
            } catch (RuntimeException error) {
                log.error("requestId={} guard={} action=block reason=guard-error", context.requestId(), id, error);
                verdict = new Verdict.Block("guard error");
            }
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            switch (verdict) {
                case Verdict.Allow allow -> trace.add(trace(entry.guard().kind(), id, Action.ALLOW, latencyMs, allow.detail()));
                case Verdict.Redact redact -> {
                    current = current.withText(redact.newText());
                    action = Action.REDACT;
                    trace.add(trace(entry.guard().kind(), id, Action.REDACT, latencyMs, redact.detail()));
                }
                case Verdict.Block block -> {
                    trace.add(trace(entry.guard().kind(), id, Action.BLOCK, latencyMs, block.reason()));
                    return new GuardChainResult(Action.BLOCK, current.text(), id, trace);
                }
            }
        }
        return new GuardChainResult(action, current.text(), null, trace);
    }

    private static ControlTrace trace(String kind, String id, Action action, long latencyMs, String detail) {
        return new ControlTrace(id, kind, action.wire(), latencyMs, detail);
    }
}
