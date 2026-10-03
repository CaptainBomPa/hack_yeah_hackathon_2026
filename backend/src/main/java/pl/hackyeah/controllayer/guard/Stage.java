package pl.hackyeah.controllayer.guard;

/** Punkt pipeline'u, w którym uruchamiany jest łańcuch guardów. */
public enum Stage {
    /** Treść wiadomości od klienta, przed wywołaniem modelu. */
    INPUT,
    /** Odpowiedź modelu, przed zwróceniem klientowi. */
    OUTPUT,
    /** Argumenty lub wynik tool-calla (jeszcze bez endpointu, który by go wołał). */
    TOOL_CALL
}
