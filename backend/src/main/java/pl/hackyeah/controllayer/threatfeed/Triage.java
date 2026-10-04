package pl.hackyeah.controllayer.threatfeed;

import java.util.List;
import java.util.Map;

/**
 * Deterministyczny triage advisory: klasa ataku z CWE i odpowiedź na pytanie, czy gateway w ogóle
 * może zobaczyć atak. Każde advisory z zakresem wersji da się pokryć sygnaturą „podatny komponent"
 * (wzmianka o paczce w podatnej wersji). Klasy „payload" dodatkowo przechodzą przez treść żądania lub
 * odpowiedzi, więc warto dla nich ręcznie napisać sygnaturę wzorca — tej nie da się wygenerować z
 * samego advisory.
 */
final class Triage {

    enum View {
        /** Atak przechodzi przez treść (prompt, argumenty narzędzia, odpowiedź) — sygnatura wzorca ma sens. */
        PAYLOAD,
        /** Błąd wewnątrz komponentu (DoS, auth, wyciek pamięci) — w ruchu widać najwyżej wersję komponentu. */
        COMPONENT_ONLY
    }

    record Result(String threatClass, View view) {}

    /** CWE → klasa ataku przechodzącego przez treść. Pozostałe CWE = błąd wewnątrz komponentu. */
    private static final Map<String, String> PAYLOAD_CLASSES = Map.ofEntries(
            Map.entry("CWE-77", "command-injection"),
            Map.entry("CWE-78", "command-injection"),
            Map.entry("CWE-94", "code-injection"),
            Map.entry("CWE-95", "code-injection"),
            Map.entry("CWE-502", "unsafe-deserialization"),
            Map.entry("CWE-22", "path-traversal"),
            Map.entry("CWE-23", "path-traversal"),
            Map.entry("CWE-918", "ssrf"),
            Map.entry("CWE-89", "sql-injection"),
            Map.entry("CWE-1336", "template-injection"),
            Map.entry("CWE-1427", "prompt-injection"),
            Map.entry("CWE-79", "xss"));

    private Triage() {}

    static Result classify(List<String> cwes) {
        for (String cwe : cwes) {
            String threatClass = PAYLOAD_CLASSES.get(cwe);
            if (threatClass != null) {
                return new Result(threatClass, View.PAYLOAD);
            }
        }
        return new Result(cwes.isEmpty() ? "unclassified" : "component-flaw", View.COMPONENT_ONLY);
    }
}
