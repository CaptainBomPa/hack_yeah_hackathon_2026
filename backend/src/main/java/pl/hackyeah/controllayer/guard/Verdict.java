package pl.hackyeah.controllayer.guard;

/** Wynik jednego guarda dla jednego tekstu. */
public sealed interface Verdict {
    /**
     * Nic do zrobienia, łańcuch idzie dalej. `detail` (opcjonalny) trafia do `trace` — np. wynik
     * semantyczny albo trafienia reguł w trybie `monitor`, które są raportowane, ale nie zmieniają
     * przepływu. Bez surowych wartości.
     */
    record Allow(String detail) implements Verdict {}
    /** Podmień tekst (np. zamaskuj PESEL); następne guardy dostaną już `newText`. */
    record Redact(String newText, String detail) implements Verdict {}
    /** Przerwij łańcuch i odrzuć żądanie/odpowiedź. `reason` trafia do `trace`, nie do klienta jako wzorzec reguły. */
    record Block(String reason) implements Verdict {}

    static Verdict allow() {
        return new Allow(null);
    }

    static Verdict allow(String detail) {
        return new Allow(detail);
    }
}
