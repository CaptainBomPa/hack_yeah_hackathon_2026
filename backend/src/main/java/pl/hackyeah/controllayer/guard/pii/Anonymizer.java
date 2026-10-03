package pl.hackyeah.controllayer.guard.pii;

import java.util.List;
import pl.hackyeah.controllayer.guard.pii.RecognizerPack.Operator;

/**
 * Operatory anonymizera w stylu Presidio: {@code replace} (domyślnie {@code [REDACTED:<ENTITY>]}),
 * {@code mask} ({@code masking_char}, {@code chars_to_mask}, {@code from_end}) i {@code redact}
 * (usunięcie). Działa na rozłącznych zakresach z {@link RecognizerEngine}, więc wynik nie zależy
 * od kolejności recognizerów.
 */
final class Anonymizer {

    private Anonymizer() {}

    static String apply(String text, List<PiiFinding> findings) {
        var out = new StringBuilder(text.length());
        int cursor = 0;
        for (PiiFinding finding : findings) {
            out.append(text, cursor, finding.start());
            out.append(render(finding, text.substring(finding.start(), finding.end())));
            cursor = finding.end();
        }
        return out.append(text, cursor, text.length()).toString();
    }

    private static String render(PiiFinding finding, String value) {
        Operator op = finding.recognizer().operator();
        return switch (op.type()) {
            case "redact" -> "";
            case "mask" -> mask(value, op);
            default -> op.newValue() != null ? op.newValue() : "[REDACTED:" + finding.recognizer().entity() + "]";
        };
    }

    private static String mask(String value, Operator op) {
        int count = Math.min(op.charsToMask(), value.length());
        var chars = value.toCharArray();
        int from = op.fromEnd() ? value.length() - count : 0;
        for (int i = from; i < from + count; i++) {
            chars[i] = op.maskingChar();
        }
        return new String(chars);
    }
}
