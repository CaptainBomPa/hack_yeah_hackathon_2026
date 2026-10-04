package pl.hackyeah.controllayer.guard.signature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot reload pliku feedu bez restartu: przy {@link #current()} co najwyżej raz na
 * {@code checkIntervalMs} sprawdza datę modyfikacji pliku i przeładowuje go, gdy się zmieniła.
 * Błędny plik nie zastępuje ostatniej poprawnej wersji (błąd jest logowany i widoczny w
 * {@link #lastError()}). Błędny plik bez żadnej wcześniejszej poprawnej wersji rzuca wyjątek,
 * więc guard blokuje (fail-closed). Brak pliku = pusty feed.
 */
public final class SignatureFeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SignatureFeedLoader.class);

    private record State(SignatureFeed feed, FileTime modified, long checkedAtMs, String error) {}

    private final Path path;
    private final long checkIntervalMs;
    private volatile State state;

    public SignatureFeedLoader(Path path, long checkIntervalMs) {
        this.path = path;
        this.checkIntervalMs = checkIntervalMs;
    }

    public SignatureFeed current() {
        State snapshot = state;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.checkedAtMs() < checkIntervalMs) {
            return requireFeed(snapshot);
        }
        synchronized (this) {
            snapshot = state;
            if (snapshot != null && now - snapshot.checkedAtMs() < checkIntervalMs) {
                return requireFeed(snapshot);
            }
            state = reloadIfChanged(snapshot, now);
            return requireFeed(state);
        }
    }

    public String lastError() {
        State snapshot = state;
        return snapshot == null ? null : snapshot.error();
    }

    private State reloadIfChanged(State previous, long now) {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(path);
        } catch (NoSuchFileException missing) {
            if (previous == null || previous.modified() != null) {
                log.warn("signature feed not found path={} — no signatures active", path);
            }
            return new State(SignatureFeed.empty(), null, now, null);
        } catch (IOException error) {
            return keepPrevious(previous, now, "cannot stat feed: " + error.getMessage());
        }
        if (previous != null && modified.equals(previous.modified())) {
            return new State(previous.feed(), previous.modified(), now, previous.error());
        }
        try {
            SignatureFeed feed = SignatureFeed.parse(Files.readString(path, StandardCharsets.UTF_8));
            log.info("signature feed loaded path={} feedVersion={} signatures={}",
                    path, feed.feedVersion(), feed.signatures().size());
            return new State(feed, modified, now, null);
        } catch (IOException | RuntimeException error) {
            log.error("signature feed rejected path={} — keeping last good version: {}", path, error.getMessage());
            // modified zapisujemy, żeby nie parsować tego samego zepsutego pliku przy każdym sprawdzeniu.
            return new State(previous == null ? null : previous.feed(), modified, now, error.getMessage());
        }
    }

    private static State keepPrevious(State previous, long now, String error) {
        return new State(previous == null ? null : previous.feed(),
                previous == null ? null : previous.modified(), now, error);
    }

    private static SignatureFeed requireFeed(State state) {
        if (state.feed() == null) {
            throw new IllegalStateException("signature feed invalid and no previous version: " + state.error());
        }
        return state.feed();
    }
}
