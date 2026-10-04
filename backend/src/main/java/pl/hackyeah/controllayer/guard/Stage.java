package pl.hackyeah.controllayer.guard;

import java.util.Locale;

/** Punkt pipeline'u, w którym uruchamiany jest łańcuch guardów. */
public enum Stage {
    /** Treść wiadomości od klienta, przed wywołaniem modelu. */
    INPUT,
    /** Odpowiedź modelu, przed zwróceniem klientowi. */
    OUTPUT,
    /** Argumenty lub wynik tool-calla (jeszcze bez endpointu, który by go wołał). */
    TOOL_CALL;

    /** Wartość zgodna z `ControlTrace.stage` w kontrakcie API (docs/api/openapi.yaml). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
