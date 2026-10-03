package pl.hackyeah.controllayer.chat.upstream;

/**
 * Padnięty, przeciążony albo zbyt wolny provider modelu. Kontroler mapuje to na ustandaryzowaną
 * odpowiedź "block" zamiast surowego stacktrace'u klientowi (fail-closed, VISION.md §2).
 */
public class UpstreamModelException extends RuntimeException {

    public UpstreamModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
