package pl.hackyeah.controllayer.guard;

/**
 * Wejście guarda. `text` to treść do sprawdzenia (jedna wiadomość, odpowiedź modelu albo
 * zserializowane argumenty narzędzia); `tool` i `agent` są null, gdy nie dotyczą.
 */
public record GuardContext(String requestId, String text, String tool, String agent) {

    public GuardContext withText(String newText) {
        return new GuardContext(requestId, newText, tool, agent);
    }
}
