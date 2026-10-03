package pl.hackyeah.controllayer.guard.pii;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.guard.Guard;
import pl.hackyeah.controllayer.guard.GuardContext;
import pl.hackyeah.controllayer.guard.GuardSettings;
import pl.hackyeah.controllayer.guard.Stage;
import pl.hackyeah.controllayer.guard.Verdict;

/**
 * PII-001 (docs/deterministic/01-pii-detection.md §4.2): 11 cyfr, checksum wag 1,3,7,9 oraz
 * poprawna data urodzenia zakodowana w PESEL. Wersja minimalna — bez kanonikalizacji, separatorów
 * i słów kontekstowych; to przykład, jak pisać guard.
 */
@Component
public class PeselGuard implements Guard {

    static final String ID = "PII-001";

    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    private static final int[] WEIGHTS = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Stage> stages() {
        return Set.of(Stage.INPUT, Stage.OUTPUT);
    }

    @Override
    public Verdict check(GuardContext ctx, GuardSettings settings) {
        var found = new AtomicInteger();
        String redacted = CANDIDATE.matcher(ctx.text()).replaceAll(match -> {
            if (!isValidPesel(match.group())) {
                return match.group();
            }
            found.incrementAndGet();
            return "[REDACTED:" + ID + "]";
        });
        if (found.get() == 0) {
            return Verdict.allow();
        }
        return new Verdict.Redact(redacted, found + " PESEL");
    }

    static boolean isValidPesel(String pesel) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * (pesel.charAt(i) - '0');
        }
        int control = (10 - sum % 10) % 10;
        return control == pesel.charAt(10) - '0' && hasValidBirthDate(pesel);
    }

    private static boolean hasValidBirthDate(String pesel) {
        int yy = Integer.parseInt(pesel.substring(0, 2));
        int mm = Integer.parseInt(pesel.substring(2, 4));
        int dd = Integer.parseInt(pesel.substring(4, 6));
        int century = switch (mm / 20) {
            case 0 -> 1900;
            case 1 -> 2000;
            case 2 -> 2100;
            case 3 -> 2200;
            case 4 -> 1800;
            default -> -1;
        };
        int month = mm % 20;
        if (century < 0 || month < 1 || month > 12) {
            return false;
        }
        try {
            return dd >= 1 && dd <= YearMonth.of(century + yy, month).lengthOfMonth();
        } catch (DateTimeException e) {
            return false;
        }
    }
}
