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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.guard.GuardChainResult.Action;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.PolicyDocument;
import pl.hackyeah.controllayer.policy.PolicySource;

/**
 * Chain of responsibility: odpala po kolei włączone guardy danego etapu. `Allow` → dalej,
 * `Redact` → podmienia tekst i idzie dalej, `Block` → przerywa. Wyjątek w guardzie = Block
 * (fail-closed). Które guardy, w jakiej kolejności i z jakimi parametrami — to aktywna polityka
 * ({@link PolicySource}); łańcuch jest budowany raz na wersję polityki i cache'owany, więc zmiana
 * polityki z UI działa od następnego żądania.
 */
@Service
public class GuardChain {

    private static final Logger log = LoggerFactory.getLogger(GuardChain.class);
    private static final String GUARD_ERROR = "guard error";

    private record Entry(Guard guard, GuardSettings settings, int order) {}

    /**
     * Guard, którego aktywna polityka nie uruchamia. Nie wykonujemy go, ale musi zostać widoczny
     * w `trace` (VISION.md §4: tryb wyłączony „pozostaje widoczny”), inaczej brak kontroli wygląda
     * w UI identycznie jak brak kontrolki — a jurorzy wyłączają kontrole celowo (CRITERIA §6).
     */
    private record Off(String id, String kind, String detail) {}

    /** Łańcuch zbudowany dla jednej wersji polityki. */
    private record Built(long version, String hash, Map<Stage, List<Entry>> byStage, Map<Stage, List<Off>> offByStage) {}

    private final List<Guard> guards;
    private final PolicySource policySource;
    private volatile Built built;
    private final GuardResultCache resultCache = new GuardResultCache(10_000, Duration.ofMinutes(30));

    @Autowired
    public GuardChain(List<Guard> guards, PolicySource policySource) {
        var knownIds = new HashSet<String>();
        for (Guard guard : guards) {
            if (!knownIds.add(guard.id())) {
                throw new IllegalStateException("Duplicate guard id: " + guard.id());
            }
        }
        this.guards = List.copyOf(guards);
        this.policySource = policySource;
    }

    /** Dla testów: stała konfiguracja guardów (jak dawniej z `control-layer.guards`). */
    public GuardChain(List<Guard> guards, GuardProperties properties) {
        this(guards, PolicySource.fixed(PolicyDocument.fromConfig(null, null, properties, null)));
        var knownIds = guards.stream().map(Guard::id).toList();
        for (String configuredId : properties.rules().keySet()) {
            if (!knownIds.contains(configuredId)) {
                throw new IllegalStateException(
                        "control-layer.guards.rules." + configuredId + " has no matching Guard bean");
            }
        }
    }

    public GuardChainResult run(Stage stage, GuardContext context) {
        return run(policySource.current(), stage, context);
    }

    /** Wynik z {@link #runCached}: `cacheHit` = łańcuch nie był uruchamiany, wynik pochodzi z cache. */
    public record CachedRun(GuardChainResult result, boolean cacheHit) {}

    /**
     * Jak {@link #run(ActivePolicy, Stage, GuardContext)}, ale z cache po treści (historia rozmowy,
     * {@link ConversationGuard}). Nie cache'ujemy wyników niepewnych: błąd guarda albo awaria providera
     * (fail-closed / fail-open) — po powrocie providera ta sama treść musi zostać sprawdzona naprawdę.
     */
    public CachedRun runCached(ActivePolicy policy, Stage stage, GuardContext context) {
        boolean cacheable = chainFor(policy).get(stage).stream().allMatch(entry -> entry.guard().cacheable());
        if (!cacheable) {
            return new CachedRun(run(policy, stage, context), false);
        }
        String key = GuardResultCache.key(policy.hash(), stage, context.text());
        var hit = resultCache.get(key);
        if (hit.isPresent()) {
            return new CachedRun(hit.get(), true);
        }
        GuardChainResult result = run(policy, stage, context);
        boolean degraded = result.trace().stream().map(ControlTrace::detail).anyMatch(detail -> detail != null
                && (detail.contains("fail-open") || detail.contains("fail-closed") || detail.equals(GUARD_ERROR)));
        if (!degraded) {
            resultCache.put(key, result);
        }
        return new CachedRun(result, false);
    }

    /** Uruchamia łańcuch według podanej wersji polityki (snapshot wzięty na początku żądania). */
    public GuardChainResult run(ActivePolicy policy, Stage stage, GuardContext context) {
        Built chain = chainFor(policy);
        var trace = new ArrayList<ControlTrace>();
        var current = context;
        var action = Action.ALLOW;

        for (Entry entry : chain.byStage().get(stage)) {
            String id = entry.guard().id();
            long startedAt = System.nanoTime();
            Verdict verdict;
            try {
                verdict = entry.guard().check(current, entry.settings());
            } catch (RuntimeException error) {
                log.error("requestId={} guard={} action=block reason=guard-error", context.requestId(), id, error);
                verdict = new Verdict.Block(GUARD_ERROR);
            }
            double latencyMs = ControlTrace.elapsedMs(startedAt);

            switch (verdict) {
                case Verdict.Allow allow -> trace.add(
                        trace(entry.guard().kind(), id, stage, Action.ALLOW, latencyMs, allow.detail(), allow.signal()));
                case Verdict.Redact redact -> {
                    current = current.withText(redact.newText());
                    action = Action.REDACT;
                    trace.add(trace(entry.guard().kind(), id, stage, Action.REDACT, latencyMs, redact.detail(),
                            redact.signal()));
                }
                case Verdict.Block block -> {
                    trace.add(trace(entry.guard().kind(), id, stage, Action.BLOCK, latencyMs, block.reason(),
                            block.signal()));
                    // Przerwany łańcuch: nie dokładamy wpisów `off`, bo guardy za blokadą też się nie
                    // wykonały i trace ma uczciwie kończyć się na tym, co faktycznie zaszło.
                    return new GuardChainResult(Action.BLOCK, current.text(), id, trace);
                }
            }
        }
        chain.offByStage().get(stage).forEach(off -> trace.add(off(off, stage)));
        return new GuardChainResult(action, current.text(), null, trace);
    }

    private Built chainFor(ActivePolicy policy) {
        Built cached = built;
        if (cached != null && cached.version() == policy.version() && cached.hash().equals(policy.hash())) {
            return cached;
        }
        Built fresh = build(policy);
        built = fresh;
        return fresh;
    }

    private Built build(ActivePolicy policy) {
        Map<Stage, List<Entry>> byStage = new EnumMap<>(Stage.class);
        Map<Stage, List<Off>> offByStage = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            byStage.put(stage, new ArrayList<>());
            offByStage.put(stage, new ArrayList<>());
        }
        for (Guard guard : guards) {
            var rule = policy.document().guards().get(guard.id());
            if (rule == null || !rule.enabled()) {
                var off = new Off(guard.id(), guard.kind(),
                        rule == null ? "not configured in policy" : "disabled in policy");
                guard.stages().forEach(stage -> offByStage.get(stage).add(off));
                continue;
            }
            var entry = new Entry(guard, new GuardSettings(true, rule.params()), rule.order());
            guard.stages().forEach(stage -> byStage.get(stage).add(entry));
        }
        byStage.values().forEach(entries -> entries.sort(
                Comparator.comparingInt(Entry::order).thenComparing(entry -> entry.guard().id())));
        // Wyłączone idą na koniec etapu i alfabetycznie: polityka nie zna dla nich sensownej kolejności.
        offByStage.values().forEach(entries -> entries.sort(Comparator.comparing(Off::id)));
        byStage.forEach((stage, entries) -> log.info("guards policyVersion={} stage={} active={} off={}",
                policy.version(), stage, entries.stream().map(e -> e.guard().id()).toList(),
                offByStage.get(stage).stream().map(Off::id).toList()));
        return new Built(policy.version(), policy.hash(), byStage, offByStage);
    }

    private static ControlTrace trace(String kind, String id, Stage stage, Action action, double latencyMs,
            String detail, Verdict.Signal signal) {
        return new ControlTrace(id, kind, action.wire(), latencyMs, detail, stage.wire(),
                signal == null ? null : signal.confidence(),
                signal == null ? null : signal.threshold());
    }

    private static ControlTrace off(Off off, Stage stage) {
        return new ControlTrace(off.id(), off.kind(), ControlTrace.ACTION_OFF, 0, off.detail(), stage.wire(),
                null, null);
    }
}
