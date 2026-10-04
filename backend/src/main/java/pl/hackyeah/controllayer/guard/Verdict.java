package pl.hackyeah.controllayer.guard;

/** Wynik jednego guarda dla jednego tekstu. */
public sealed interface Verdict {

    /**
     * Liczbowy sygnał kontroli, gdy ona taki produkuje (dziś: semantyka). {@code confidence} to
     * wynik detektora, {@code threshold} próg, przy którym zapada blokada — oba w tej samej skali
     * 0-1. Trafia do {@code ControlTrace.confidence}/{@code .threshold}, żeby UI rysowało pasek ze
     * znacznikiem progu, a nie parsowało {@code detail} (docs/api/openapi.yaml, VISION.md §5 E).
     *
     * <p>Kontrole bez wyniku liczbowego (PII, sekrety, bramki żądania) zostawiają tu null. Null jest
     * też poprawną odpowiedzią na awarię providera: wtedy nie ma czego pokazywać na pasku, a powód
     * zostaje w {@code detail}.
     */
    record Signal(double confidence, double threshold) {}

    /**
     * Nic do zrobienia, łańcuch idzie dalej. `detail` (opcjonalny) trafia do `trace` — np. wynik
     * semantyczny albo trafienia reguł w trybie `monitor`, które są raportowane, ale nie zmieniają
     * przepływu. Bez surowych wartości.
     */
    record Allow(String detail, Signal signal) implements Verdict {
        public Allow(String detail) {
            this(detail, null);
        }
    }

    /** Podmień tekst (np. zamaskuj PESEL); następne guardy dostaną już `newText`. */
    record Redact(String newText, String detail, Signal signal) implements Verdict {
        public Redact(String newText, String detail) {
            this(newText, detail, null);
        }
    }

    /** Przerwij łańcuch i odrzuć żądanie/odpowiedź. `reason` trafia do `trace`, nie do klienta jako wzorzec reguły. */
    record Block(String reason, Signal signal) implements Verdict {
        public Block(String reason) {
            this(reason, null);
        }
    }

    /** Sygnał liczbowy tego werdyktu albo null, gdy kontrola go nie produkuje. */
    Signal signal();

    static Verdict allow() {
        return new Allow(null);
    }

    static Verdict allow(String detail) {
        return new Allow(detail);
    }
}
