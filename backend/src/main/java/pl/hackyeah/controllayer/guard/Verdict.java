package pl.hackyeah.controllayer.guard;

/** Wynik jednego guarda dla jednego tekstu. */
public sealed interface Verdict {

    /** Nic do zrobienia, łańcuch idzie dalej. */
    record Allow() implements Verdict {}

    /** Podmień tekst (np. zamaskuj PESEL); następne guardy dostaną już `newText`. */
    record Redact(String newText, String detail) implements Verdict {}

    /** Przerwij łańcuch i odrzuć żądanie/odpowiedź. `reason` trafia do `trace`, nie do klienta jako wzorzec reguły. */
    record Block(String reason) implements Verdict {}

    static Verdict allow() {
        return new Allow();
    }
}
