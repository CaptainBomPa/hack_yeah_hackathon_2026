package pl.hackyeah.controllayer.chat;

/**
 * Wynik pojedynczej kontroli dla jednego żądania — odpowiada `ControlTrace` z
 * frontend/src/api/types.ts oraz `ControlResult` z VISION.md §4 (tu już spłaszczony do tego,
 * co pokazuje UI).
 *
 * <p>{@code latencyMs} to czas własny tej jednej kontroli, nie znacznik czasu w pipeline —
 * dzięki temu suma po wpisach jest sensownym „ile zajęły kontrole” (UI liczy na tym rozkład
 * latencji). Jest {@code double} z rozdzielczością mikrosekundy, bo kontrole deterministyczne
 * trwają ułamki milisekundy: przy liczbie całkowitej cały silnik PII i sekretów raportował
 * „0 ms”, czyli wyglądał na niezmierzony. Sumy pozostają dokładne, bo wartości są zaokrąglane
 * do pełnych mikrosekund. {@code stage} jest ustawiony tylko dla guardów z łańcucha; kontrole bramkujące
 * samo żądanie (allowlista modelu, budżet, audyt) nie należą do żadnego etapu i mają tu null.
 *
 * <p>{@code action} to {@code allow | redact | block} dla kontroli, która się wykonała, oraz
 * {@code off} dla kontroli wyłączonej w aktywnej polityce. Wpis {@code off} jest celowy: tryb
 * wyłączony ma pozostać widoczny (VISION.md §4), żeby brak kontroli dał się odróżnić od braku
 * kontrolki. Taki wpis ma zerową latencję i nie jest trafieniem — konsumenci liczący trafienia
 * muszą pomijać {@code off} tak samo jak {@code allow}.
 *
 * <p>{@code confidence} i {@code threshold} są wypełnione tylko dla kontroli z wynikiem liczbowym
 * (dziś semantyka, {@link pl.hackyeah.controllayer.guard.Verdict.Signal}); pozostałe mają null.
 */
public record ControlTrace(
        String policy,
        String kind, // "deterministic" | "semantic"
        String action, // "allow" | "redact" | "block" | "off"
        double latencyMs, // czas własny kontroli, rozdzielczość 1 µs (zob. elapsedMs)
        String detail,
        String stage, // "input" | "output" | "tool_call" | null
        Double confidence, // wynik detektora 0-1; null, gdy kontrola nie zwraca liczby
        Double threshold) { // próg blokady 0-1 w tej samej skali co confidence

    /** Akcja wpisu, który tylko informuje o kontroli wyłączonej w polityce. */
    public static final String ACTION_OFF = "off";

    /** Akcja kontroli, która się wykonała i nie miała nic do zgłoszenia. */
    public static final String ACTION_ALLOW = "allow";

    /** Kontrola poza łańcuchem guardów: bez etapu i bez sygnału liczbowego. */
    public ControlTrace(String policy, String kind, String action, double latencyMs, String detail) {
        this(policy, kind, action, latencyMs, detail, null, null, null);
    }

    /**
     * Czas własny kontroli w milisekundach, zaokrąglony do pełnej mikrosekundy. Jedno miejsce na
     * ten pomiar, żeby wszystkie wpisy `trace` miały tę samą jednostkę i rozdzielczość —
     * niezależnie od tego, czy mierzy je {@code GuardChain}, bramka żądania czy audyt.
     *
     * <p>Mikrosekunda, a nie nanosekunda: poniżej tego progu i tak mierzymy narzut
     * {@code System.nanoTime()}, a nie kontrolę.
     */
    public static double elapsedMs(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000.0) / 1_000.0;
    }

    /**
     * Czy akcja jest trafieniem kontroli, czy tylko informacją („przeszło” albo „wyłączone”).
     * Metoda statyczna, nie instancyjna: {@code isHit()} na rekordzie Jackson wyserializowałby
     * jako dodatkowe pole {@code hit}, co wysypałoby odczyt wpisów audytu zapisanych wcześniej.
     */
    public static boolean isHit(String action) {
        return !ACTION_ALLOW.equals(action) && !ACTION_OFF.equals(action);
    }
}
