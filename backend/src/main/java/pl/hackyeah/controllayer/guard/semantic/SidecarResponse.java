package pl.hackyeah.controllayer.guard.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Odpowiedź `POST /classify` sidecara semantycznego (semantic-sidecar/docs/input-contract.md).
 * Mapujemy tylko pola, których potrzebuje decyzja. Sidecar zwraca sygnały, nie akcje.
 *
 * @param complete       false, gdy któraś włączona kontrola nie dała wyniku (`missingChecks` niepuste).
 * @param missingChecks  kontrole, które miały się wykonać, a nie dały wyniku.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SidecarResponse(
        String checkpoint,
        List<DetectorResult> results,
        boolean complete,
        @JsonProperty("missing_checks") List<MissingCheck> missingChecks) {

    public SidecarResponse {
        results = results == null ? List.of() : List.copyOf(results);
        missingChecks = missingChecks == null ? List.of() : List.copyOf(missingChecks);
    }

    /** Wynik jednego detektora. Gdy `status` != "ok", `score` jest null i kontrola nie dała sygnału. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetectorResult(
            String detector,
            String version,
            String status,
            String reason,
            Double score,
            @JsonProperty("raw_score") Double rawScore) {

        public boolean ok() {
            return "ok".equals(status) && score != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MissingCheck(String check, String status, String reason) {}
}
