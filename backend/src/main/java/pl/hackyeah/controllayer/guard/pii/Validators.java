package pl.hackyeah.controllayer.guard.pii;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Rejestr nazwanych walidatorów kandydatów (odpowiednik {@code validate_result} z Presidio
 * {@code PatternRecognizer}). Tylko algorytmy żyją w kodzie; to, który walidator dostaje który
 * wzorzec, jest w paczce recognizerów. Algorytmy: docs/deterministic/01-pii-detection.md §4.2.
 */
public final class Validators {

    /** Semantyka jak w Presidio: VALID → score 1.0, INVALID → odrzuć, UNKNOWN → zostaw score wzorca. */
    public enum Result { VALID, INVALID, UNKNOWN }

    private static final Map<String, Function<String, Result>> REGISTRY = Map.of(
            "pesel", Validators::pesel,
            "nip", Validators::nip,
            "regon", Validators::regon,
            "pl_id_card", Validators::plIdCard,
            "luhn", Validators::luhn,
            "iban", Validators::iban,
            "email", Validators::email);

    private Validators() {}

    public static boolean exists(String name) {
        return REGISTRY.containsKey(name);
    }

    public static Result validate(String name, String candidate) {
        var validator = REGISTRY.get(name);
        if (validator == null) {
            throw new IllegalArgumentException("Unknown validator: " + name);
        }
        return validator.apply(candidate);
    }

    private static Result of(boolean valid) {
        return valid ? Result.VALID : Result.INVALID;
    }

    /** Zostawia same znaki alfanumeryczne (zdejmuje spacje, myślniki, kropki). */
    static String compact(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean allDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static int weightedSum(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i] * (digits.charAt(i) - '0');
        }
        return sum;
    }

    // PESEL: wagi 1,3,7,9,1,3,7,9,1,3; cyfra kontrolna (10 - S mod 10) mod 10; poprawna data urodzenia.
    static Result pesel(String candidate) {
        String d = compact(candidate);
        if (d.length() != 11 || !allDigits(d)) {
            return Result.INVALID;
        }
        int control = (10 - weightedSum(d, new int[] {1, 3, 7, 9, 1, 3, 7, 9, 1, 3}) % 10) % 10;
        return of(control == d.charAt(10) - '0' && hasValidPeselBirthDate(d));
    }

    private static boolean hasValidPeselBirthDate(String pesel) {
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

    // NIP: wagi 6,5,7,2,3,4,5,6,7; S mod 11 == 10. cyfra; reszta 10 = NIP nie istnieje.
    static Result nip(String candidate) {
        String d = compact(candidate).toUpperCase(Locale.ROOT);
        if (d.startsWith("PL")) {
            d = d.substring(2);
        }
        if (d.length() != 10 || !allDigits(d)) {
            return Result.INVALID;
        }
        int r = weightedSum(d, new int[] {6, 5, 7, 2, 3, 4, 5, 6, 7}) % 11;
        return of(r != 10 && r == d.charAt(9) - '0');
    }

    // REGON-9: wagi 8,9,2,3,4,5,6,7; REGON-14: wagi 2,4,8,5,0,9,7,3,6,1,2,4,8 i poprawny prefiks REGON-9.
    static Result regon(String candidate) {
        String d = compact(candidate);
        if (!allDigits(d)) {
            return Result.INVALID;
        }
        if (d.length() == 9) {
            return of(regonControl(d, new int[] {8, 9, 2, 3, 4, 5, 6, 7}));
        }
        if (d.length() == 14) {
            return of(regonControl(d.substring(0, 9), new int[] {8, 9, 2, 3, 4, 5, 6, 7})
                    && regonControl(d, new int[] {2, 4, 8, 5, 0, 9, 7, 3, 6, 1, 2, 4, 8}));
        }
        return Result.INVALID;
    }

    private static boolean regonControl(String d, int[] weights) {
        int r = weightedSum(d, weights) % 11;
        return (r == 10 ? 0 : r) == d.charAt(weights.length) - '0';
    }

    // Dowód osobisty: 3 litery (A=10..Z=35) + 6 cyfr, wagi 7,3,1,9,7,3,1,7,3, suma mod 10 == 0.
    static Result plIdCard(String candidate) {
        String v = compact(candidate).toUpperCase(Locale.ROOT);
        if (v.length() != 9) {
            return Result.INVALID;
        }
        int[] weights = {7, 3, 1, 9, 7, 3, 1, 7, 3};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            char c = v.charAt(i);
            int value;
            if (i < 3 && c >= 'A' && c <= 'Z') {
                value = c - 'A' + 10;
            } else if (i >= 3 && c >= '0' && c <= '9') {
                value = c - '0';
            } else {
                return Result.INVALID;
            }
            sum += weights[i] * value;
        }
        return of(sum % 10 == 0);
    }

    // Luhn dla kart płatniczych (12–19 cyfr po zdjęciu separatorów).
    static Result luhn(String candidate) {
        String d = compact(candidate);
        if (d.length() < 12 || d.length() > 19 || !allDigits(d)) {
            return Result.INVALID;
        }
        int sum = 0;
        for (int i = 0; i < d.length(); i++) {
            int digit = d.charAt(d.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return of(sum % 10 == 0);
    }

    // IBAN ISO 13616 mod-97; 26 cyfr bez kraju traktujemy jak polski NRB (prefiks PL).
    static Result iban(String candidate) {
        String v = compact(candidate).toUpperCase(Locale.ROOT);
        if (v.length() == 26 && allDigits(v)) {
            v = "PL" + v;
        }
        if (v.length() < 15 || v.length() > 34
                || !Character.isLetter(v.charAt(0)) || !Character.isLetter(v.charAt(1))) {
            return Result.INVALID;
        }
        if (v.startsWith("PL") && v.length() != 28) {
            return Result.INVALID;
        }
        String rearranged = v.substring(4) + v.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            int value;
            if (c >= '0' && c <= '9') {
                value = c - '0';
                remainder = (remainder * 10 + value) % 97;
            } else if (c >= 'A' && c <= 'Z') {
                value = c - 'A' + 10;
                remainder = (remainder * 100 + value) % 97;
            } else {
                return Result.INVALID;
            }
        }
        return of(remainder == 1);
    }

    // E-mail: domena z co najmniej jedną kropką i alfabetycznym TLD (Presidio: tldextract → fqdn).
    static Result email(String candidate) {
        int at = candidate.lastIndexOf('@');
        if (at <= 0 || at == candidate.length() - 1) {
            return Result.INVALID;
        }
        String domain = candidate.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        if (dot <= 0 || domain.contains("..")) {
            return Result.INVALID;
        }
        String tld = domain.substring(dot + 1);
        return of(tld.length() >= 2 && tld.chars().allMatch(Character::isLetter));
    }
}
