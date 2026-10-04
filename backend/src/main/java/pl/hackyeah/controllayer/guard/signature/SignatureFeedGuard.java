package pl.hackyeah.controllayer.guard.signature;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.guard.Verdict;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed.Action;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed.Signature;

/**
 * SIG-FEED (docs/redteam-feed.md, case 22): blokuje wzorce historycznych ataków z zewnętrznego
 * feedu sygnatur ({@code config/signatures/active.yaml}). Feed jest danymi z hot reloadem — admin
 * dopisuje sygnaturę, puszcza regresję i zmiana działa od następnego żądania, bez restartu.
 *
 * <p>Parametry: {@code feed} (ścieżka pliku, domyślnie {@value #DEFAULT_FEED}),
 * {@code checkIntervalMs} (jak często sprawdzać zmianę pliku, domyślnie 1000) i
 * {@code disabledSignatures} (id sygnatur pomijanych przez politykę).
 * W {@code trace} trafiają tylko id sygnatur, bez wzorców.
 */
@Component
public class SignatureFeedGuard implements Guard {

    static final String ID = "SIG-FEED";
    static final String DEFAULT_FEED = "config/signatures/active.yaml";

    private final Map<String, SignatureFeedLoader> loaders = new ConcurrentHashMap<>();

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
        SignatureFeed feed = loader(settings).current();
        Set<String> disabled = settings.stringSetParam("disabledSignatures", Set.of());
        List<Signature> hits = hits(feed, ctx.text(), disabled);
        if (hits.isEmpty()) {
            return Verdict.allow();
        }
        List<String> blocked = hits.stream().filter(s -> s.action() == Action.BLOCK).map(Signature::id).toList();
        List<String> monitored = hits.stream().filter(s -> s.action() == Action.MONITOR).map(Signature::id).toList();
        if (!blocked.isEmpty()) {
            return new Verdict.Block("signature " + String.join(", ", blocked) + " (feed " + feed.feedVersion() + ")");
        }
        return Verdict.allow("monitor signature " + String.join(", ", monitored) + " (feed " + feed.feedVersion() + ")");
    }

    /** Włączone sygnatury trafione przez tekst; wspólne dla guarda i regresji. */
    public static List<Signature> hits(SignatureFeed feed, String text, Set<String> disabled) {
        String normalized = SignatureMatcher.normalize(text);
        return feed.signatures().stream()
                .filter(Signature::enabled)
                .filter(s -> !disabled.contains(s.id()))
                .filter(s -> s.matcher().matches(normalized))
                .toList();
    }

    private SignatureFeedLoader loader(GuardSettings settings) {
        String feed = settings.stringParam("feed", DEFAULT_FEED);
        long interval = settings.intParam("checkIntervalMs", 1000);
        return loaders.computeIfAbsent(feed + "|" + interval,
                key -> new SignatureFeedLoader(Path.of(feed), interval));
    }
}
