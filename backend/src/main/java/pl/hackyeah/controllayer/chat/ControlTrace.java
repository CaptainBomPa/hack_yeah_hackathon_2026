package pl.hackyeah.controllayer.chat;

/**
 * Wynik pojedynczej kontroli dla jednego żądania — odpowiada `ControlTrace` z
 * frontend/src/api/types.ts oraz `ControlResult` z VISION.md §4 (tu już spłaszczony do tego,
 * co pokazuje UI; `confidence` i pełny `ControlResult` dojdą z resztą decision pipeline).
 *
 * <p>{@code latencyMs} to czas własny tej jednej kontroli, nie znacznik czasu w pipeline —
 * dzięki temu suma po wpisach jest sensownym „ile zajęły kontrole” (UI liczy na tym rozkład
 * latencji). {@code stage} jest ustawiony tylko dla guardów z łańcucha; kontrole bramkujące
 * samo żądanie (allowlista modelu, budżet, audyt) nie należą do żadnego etapu i mają tu null.
 */
public record ControlTrace(
        String policy,
        String kind, // "deterministic" | "semantic"
        String action, // "allow" | "redact" | "block"
        long latencyMs,
        String detail,
        String stage) { // "input" | "output" | "tool_call" | null

    /** Kontrola poza łańcuchem guardów, czyli bez etapu. */
    public ControlTrace(String policy, String kind, String action, long latencyMs, String detail) {
        this(policy, kind, action, latencyMs, detail, null);
    }
}
