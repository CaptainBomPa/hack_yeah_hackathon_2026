package pl.hackyeah.controllayer.guard.pii;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardProperties;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.guard.Verdict;
import pl.hackyeah.controllayer.guard.pii.RecognizerPack.Action;

/**
 * PII-RECOGNIZERS: silnik PII z konceptami Presidio (recognizery jako dane, walidatory, kontekst,
 * score, rozwiązywanie konfliktów, operatory anonymizera), zaimplementowany w Javie bez NLP.
 * Recognizery (PII-001..PII-008) są w paczce YAML ({@code rules/pii/recognizers.yaml}); każdy ma
 * własne {@code id} widoczne w trace. Szczegóły: docs/deterministic/pii-recognizers.md.
 *
 * <p>Parametry w {@code control-layer.guards.rules.PII-RECOGNIZERS.params}:
 * {@code pack}, {@code threshold}, {@code contextPrefixWords}, {@code contextSuffixWords},
 * {@code disabledRecognizers}, {@code blockRecognizers}, {@code monitorRecognizers} (id
 * rozdzielone przecinkami; nadpisują {@code action} z paczki).
 */
@Component
public class PiiRecognizerGuard implements Guard {

    static final String ID = "PII-RECOGNIZERS";
    static final String DEFAULT_PACK = "classpath:rules/pii/recognizers.yaml";
    private static final Logger log = LoggerFactory.getLogger(PiiRecognizerGuard.class);

    private final RecognizerPack pack;

    @Autowired
    public PiiRecognizerGuard(GuardProperties properties, ResourceLoader resourceLoader) {
        var rule = properties.rules().get(ID);
        Object configured = rule == null ? null : rule.params().get("pack");
        String location = configured instanceof String s && !s.isBlank() ? s : DEFAULT_PACK;
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            this.pack = RecognizerPack.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read PII recognizer pack " + location, e);
        }
        log.info("pii recognizers pack={} loaded={}", location,
                pack.recognizers().stream().map(RecognizerPack.Recognizer::id).toList());
    }

    /** Dla testów: paczka podana wprost. */
    PiiRecognizerGuard(RecognizerPack pack) {
        this.pack = pack;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Stage> stages() {
        return Set.of(Stage.INPUT, Stage.OUTPUT, Stage.TOOL_CALL);
    }

    @Override
    public Verdict check(GuardContext ctx, GuardSettings settings) {
        var defaults = RecognizerEngine.Options.defaults();
        var options = new RecognizerEngine.Options(
                settings.doubleParam("threshold", defaults.threshold()),
                settings.intParam("contextPrefixWords", defaults.contextPrefixWords()),
                settings.intParam("contextSuffixWords", defaults.contextSuffixWords()),
                defaults.contextBoost(),
                defaults.minScoreWithContext());
        Set<String> blockIds = idSet(settings, "blockRecognizers");
        Set<String> monitorIds = idSet(settings, "monitorRecognizers");

        List<PiiFinding> findings = new RecognizerEngine(pack.recognizers(), options)
                .analyze(ctx.text(), idSet(settings, "disabledRecognizers"));
        if (findings.isEmpty()) {
            return Verdict.allow();
        }

        Map<Action, List<PiiFinding>> byAction = findings.stream().collect(Collectors.groupingBy(
                f -> effectiveAction(f, blockIds, monitorIds), LinkedHashMap::new, Collectors.toList()));
        List<PiiFinding> toBlock = byAction.getOrDefault(Action.BLOCK, List.of());
        List<PiiFinding> toRedact = byAction.getOrDefault(Action.REDACT, List.of());
        List<PiiFinding> monitored = byAction.getOrDefault(Action.MONITOR, List.of());

        if (!toBlock.isEmpty()) {
            return new Verdict.Block(summary(toBlock));
        }
        String monitorDetail = monitored.isEmpty() ? null : "monitor " + summary(monitored);
        if (toRedact.isEmpty()) {
            return new Verdict.Allow(monitorDetail);
        }
        String detail = summary(toRedact) + (monitorDetail == null ? "" : "; " + monitorDetail);
        return new Verdict.Redact(Anonymizer.apply(ctx.text(), toRedact), detail);
    }

    private static Action effectiveAction(PiiFinding finding, Set<String> blockIds, Set<String> monitorIds) {
        String id = finding.recognizer().id();
        if (blockIds.contains(id)) {
            return Action.BLOCK;
        }
        if (monitorIds.contains(id)) {
            return Action.MONITOR;
        }
        return finding.recognizer().action();
    }

    /** Np. {@code PII-001/PL_PESEL×1 [pattern PESEL 0.40 → pesel:valid 1.00]} — bez surowych wartości. */
    private static String summary(List<PiiFinding> findings) {
        Map<String, List<PiiFinding>> byRecognizer = findings.stream().collect(Collectors.groupingBy(
                f -> f.recognizer().id() + "/" + f.recognizer().entity(), LinkedHashMap::new, Collectors.toList()));
        return byRecognizer.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue().size() + " [" + e.getValue().getFirst().explanation() + "]")
                .collect(Collectors.joining("; "));
    }

    /** Lista YAML, napis rozdzielony przecinkami albo mapa indeksów (tak Spring wiąże listy w Map). */
    private static Set<String> idSet(GuardSettings settings, String name) {
        Collection<?> items = switch (settings.params().get(name)) {
            case null -> List.of();
            case Collection<?> c -> c;
            case Map<?, ?> indexed -> indexed.values();
            case String s -> Arrays.asList(s.split(","));
            case Object other -> List.of(other);
        };
        return items.stream().map(item -> String.valueOf(item).trim()).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
