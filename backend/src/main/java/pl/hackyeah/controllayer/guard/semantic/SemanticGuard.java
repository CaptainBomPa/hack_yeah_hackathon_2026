package pl.hackyeah.controllayer.guard.semantic;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.guard.Verdict;

/**
 * SEM-001: kontrola semantyczna (prompt injection) przez sidecar (semantic-sidecar/). Sidecar zwraca
 * SYGNAŁY (wynik 0-1 per detektor), a decyzję podejmuje ten guard na podstawie progu z konfiguracji,
 * zgodnie z VISION.md §4 ("semantyka jest sygnałem, Java decyduje").
 *
 * <p>Parametry (`control-layer.guards.rules.SEM-001.params`):
 * <ul>
 *   <li>{@code blockThreshold} (domyślnie 0.998): blokada, gdy wynik któregoś detektora jest nie mniejszy.
 *       Wynik jest SKALIBROWANY na zbiorze o dużym udziale ataków, więc próg wybieramy z docelowego odsetka
 *       fałszywych alarmów, nie z "0.5" (zob. semantic-sidecar/docs/models.md).</li>
 *   <li>{@code timeoutMs} (domyślnie 4000).</li>
 *   <li>{@code failureMode}: "closed" (domyślnie) albo "open". Dotyczy awarii sidecara, braku wyniku,
 *       niepełnych kontroli ({@code complete=false}) oraz pustej listy wyników.</li>
 * </ul>
 *
 * <p>Pusta lista wyników NIE oznacza "przeszło": sidecar bez detektora dla tego punktu kontroli niczego
 * nie sprawdził, więc traktujemy to jak brak kontroli (zgodnie z failureMode). Awaria providera nie może
 * po cichu omijać kontroli (VISION.md §2).
 *
 * <p>Tekst jest wysyłany tak, jak przyszedł do guarda. Normalizacja (Unicode, kodowania) ma być wykonana
 * wcześniej w warstwie deterministycznej (ustalenie zespołu); do tego czasu zakodowane ataki mogą przejść.
 */
@Component
public class SemanticGuard implements Guard {

    static final String ID = "SEM-001";

    private static final Logger log = LoggerFactory.getLogger(SemanticGuard.class);
    private static final double DEFAULT_THRESHOLD = 0.998;
    private static final int DEFAULT_TIMEOUT_MS = 4000;

    private final SidecarClient client;

    public SemanticGuard(SidecarClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Sidecar pokrywa dziś P1 (prompt). Odpowiedź modelu (P4) i narzędzia (P3) nie mają jeszcze detektorów. */
    @Override
    public Set<Stage> stages() {
        return Set.of(Stage.INPUT);
    }

    @Override
    public String kind() {
        return "semantic";
    }

    @Override
    public Verdict check(GuardContext ctx, GuardSettings settings) {
        double threshold = settings.doubleParam("blockThreshold", DEFAULT_THRESHOLD);
        Duration timeout = Duration.ofMillis(settings.intParam("timeoutMs", DEFAULT_TIMEOUT_MS));
        boolean failClosed = !"open".equalsIgnoreCase(settings.stringParam("failureMode", "closed"));

        SidecarResponse response;
        try {
            response = client.classify("P1", ctx.text(), null, timeout);
        } catch (RuntimeException error) {
            // Treści wyjątku nie dołączamy do trace: może zawierać fragment wejścia.
            log.warn("requestId={} guard={} sidecar unavailable errorType={}",
                    ctx.requestId(), ID, error.getClass().getSimpleName());
            return failure("sidecar unavailable", failClosed);
        }

        List<SidecarResponse.DetectorResult> ok = response.results().stream()
                .filter(SidecarResponse.DetectorResult::ok)
                .toList();

        var top = ok.stream().max((a, b) -> Double.compare(a.score(), b.score()));
        if (top.isPresent() && top.get().score() >= threshold) {
            return new Verdict.Block(format(top.get(), threshold), signal(top.get(), threshold));
        }
        if (response.results().isEmpty()) {
            return failure("no semantic coverage for this input (sidecar returned no results)", failClosed);
        }
        if (!response.complete() || ok.isEmpty()) {
            String missing = response.missingChecks().stream()
                    .map(m -> m.check() + ":" + m.reason())
                    .collect(Collectors.joining(","));
            return failure("incomplete semantic check (" + missing + ")", failClosed);
        }
        return new Verdict.Allow(format(top.get(), threshold), signal(top.get(), threshold));
    }

    private static Verdict failure(String reason, boolean failClosed) {
        // Bez sygnału: awaria providera nie ma wyniku, więc UI nie ma czego rysować na pasku.
        return failClosed
                ? new Verdict.Block(reason + " (fail-closed)")
                : Verdict.allow(reason + " (fail-open)");
    }

    private static Verdict.Signal signal(SidecarResponse.DetectorResult result, double threshold) {
        return new Verdict.Signal(result.score(), threshold);
    }

    /** Czytelny dla człowieka ślad do audytu i eksportu CSV; liczby jadą osobno w {@link Verdict.Signal}. */
    private static String format(SidecarResponse.DetectorResult result, double threshold) {
        return String.format(Locale.ROOT, "%s score=%.4f threshold=%.4f", result.detector(), result.score(), threshold);
    }
}
