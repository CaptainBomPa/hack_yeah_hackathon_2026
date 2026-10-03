package pl.hackyeah.controllayer.guard.pii;

import pl.hackyeah.controllayer.guard.pii.RecognizerPack.Recognizer;

/**
 * Trafienie recognizera: zakres {@code [start, end)} w analizowanym tekście, końcowy score i
 * {@code explanation} — ścieżka decyzji jak {@code return_decision_process} w Presidio
 * (np. {@code "pattern PESEL 0.40 → pesel:valid 1.00"}). Nie zawiera surowej wartości.
 */
public record PiiFinding(Recognizer recognizer, int start, int end, double score, String explanation) {

    public int length() {
        return end - start;
    }

    public boolean overlaps(PiiFinding other) {
        return start < other.end && other.start < end;
    }
}
