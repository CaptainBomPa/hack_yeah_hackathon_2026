package pl.hackyeah.controllayer.audit;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Neutralizacja pól niezaufanych przed zapisem do audytu (AUDIT-005, CWE-117): usuwa sekwencje
 * ANSI, znaki sterujące i znaki Unicode bidi, normalizuje do NFC i przycina do limitu długości.
 */
final class AuditSanitizer {

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern CONTROL_AND_BIDI =
            Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u202A-\\u202E\\u2066-\\u2069]");

    private AuditSanitizer() {
    }

    static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = Normalizer.normalize(value, Normalizer.Form.NFC);
        cleaned = ANSI.matcher(cleaned).replaceAll("");
        cleaned = CONTROL_AND_BIDI.matcher(cleaned).replaceAll("?");
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength) + "…";
        }
        return cleaned;
    }
}
