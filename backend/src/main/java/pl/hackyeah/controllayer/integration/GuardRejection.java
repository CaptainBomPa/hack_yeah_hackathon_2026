package pl.hackyeah.controllayer.integration;

import java.util.List;
import java.util.regex.Pattern;
import pl.hackyeah.controllayer.guard.GuardChainResult;
import pl.hackyeah.controllayer.guard.Stage;

/** Public rejection metadata contains classifications only, never raw guard explanations. */
record GuardRejection(int status, String code, String stage, String guard, String reason,
        List<String> detections) {
    private static final Pattern PII = Pattern.compile("\\bPII-[0-9]{3}/[A-Z][A-Z0-9_]{0,63}\\b");

    static GuardRejection from(GuardChainResult result, Stage stage, boolean streamRedaction) {
        String detail = result.trace().stream()
                .filter(trace -> trace.policy().equals(result.blockedBy()) && "block".equals(trace.action()))
                .map(trace -> trace.detail() == null ? "" : trace.detail()).findFirst().orElse("");
        boolean unavailable = "SEM-001".equals(result.blockedBy()) && detail.endsWith("(fail-closed)");
        String guard = streamRedaction ? result.trace().stream()
                .filter(trace -> "redact".equals(trace.action())).map(trace -> trace.policy())
                .findFirst().orElse("output-policy") : result.blockedBy();
        var detections = "PII-RECOGNIZERS".equals(guard)
                ? result.trace().stream().filter(trace -> guard.equals(trace.policy()))
                    .flatMap(trace -> PII.matcher(trace.detail() == null ? "" : trace.detail())
                            .results().map(match -> match.group())).distinct().toList()
                : List.<String>of();
        String reason = unavailable ? (detail.contains("timeout") ? "check_timeout" : "check_unavailable")
                : streamRedaction ? "stream_redaction_required"
                : "PII-RECOGNIZERS".equals(guard) ? "personal_data"
                : "SEC-GITLEAKS".equals(guard) ? "secret_detected"
                : "SEM-001".equals(guard) ? "prompt_injection" : "content_policy";
        return new GuardRejection(unavailable ? 503 : 400,
                unavailable ? "policy.check-unavailable" : "policy." + stage.name().toLowerCase(java.util.Locale.ROOT) + "-blocked",
                stage.name().toLowerCase(java.util.Locale.ROOT), guard, reason, detections);
    }

    String message() {
        return (status == 503 ? "Security check unavailable" : "Content blocked") + " at " + stage
                + " by " + guard + ": " + reason
                + (detections.isEmpty() ? "" : " (" + String.join(", ", detections) + ")");
    }
}
