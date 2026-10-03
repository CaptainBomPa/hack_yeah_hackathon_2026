package pl.hackyeah.controllayer.chat.upstream;

/**
 * Padnięty, przeciążony albo zbyt wolny provider modelu. Kontroler mapuje to na ustandaryzowaną
 * odpowiedź "block" zamiast surowego stacktrace'u klientowi (fail-closed, VISION.md §2).
 */
public class UpstreamModelException extends RuntimeException {
    private final boolean upstreamMayStillBeRunning;
    private final boolean requestNotSent;

    public UpstreamModelException(String message) {
        this(message, null, false, false);
    }

    public UpstreamModelException(String message, Throwable cause) {
        this(message, cause, true, false);
    }

    public UpstreamModelException(String message, Throwable cause,
            boolean upstreamMayStillBeRunning, boolean requestNotSent) {
        super(message, cause);
        this.upstreamMayStillBeRunning = upstreamMayStillBeRunning;
        this.requestNotSent = requestNotSent;
    }

    public boolean upstreamMayStillBeRunning() { return upstreamMayStillBeRunning; }
    public boolean requestNotSent() { return requestNotSent; }
}
