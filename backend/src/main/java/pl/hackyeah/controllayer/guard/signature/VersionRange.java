package pl.hackyeah.controllayer.guard.signature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Zakres podatnych wersji w modelu OSV ({@code affected[].ranges[].events}): lista zdarzeń
 * {@code introduced}, {@code fixed}, {@code last_affected}. Wersja jest podatna, jeśli po
 * posortowaniu zdarzeń ostatnie zdarzenie nie większe od niej to {@code introduced}
 * (algorytm z https://ossf.github.io/osv-schema/#evaluation).
 *
 * <p>Porównanie wersji jest numeryczne po segmentach ({@code 0.1.16 > 0.1.9}); sufiksy
 * pre-release ({@code rc1}, {@code .dev0}) są ignorowane — to świadome uproszczenie MVP.
 */
public record VersionRange(List<Event> events) {

    public enum Kind { INTRODUCED, FIXED, LAST_AFFECTED }

    public record Event(Kind kind, String version) {}

    public VersionRange {
        events = events.stream()
                .sorted(Comparator.comparing(Event::version, VersionRange::compare))
                .toList();
        if (events.isEmpty()) {
            throw new IllegalArgumentException("version range has no events");
        }
    }

    /** Z mapy w stylu OSV/YAML: {@code [{introduced: "0"}, {fixed: "0.1.16"}]}. */
    public static VersionRange fromEvents(List<?> rawEvents) {
        var events = new ArrayList<Event>();
        for (Object raw : rawEvents) {
            if (!(raw instanceof Map<?, ?> map) || map.size() != 1) {
                throw new IllegalArgumentException("range event must be a single-key map: " + raw);
            }
            var entry = map.entrySet().iterator().next();
            Kind kind = switch (String.valueOf(entry.getKey())) {
                case "introduced" -> Kind.INTRODUCED;
                case "fixed" -> Kind.FIXED;
                case "last_affected" -> Kind.LAST_AFFECTED;
                default -> throw new IllegalArgumentException("unknown range event: " + entry.getKey());
            };
            String version = String.valueOf(entry.getValue()).trim();
            if (!version.matches("\\d.*")) {
                throw new IllegalArgumentException("range event version must start with a digit: " + version);
            }
            events.add(new Event(kind, version));
        }
        return new VersionRange(events);
    }

    public boolean contains(String version) {
        boolean affected = false;
        for (Event event : events) {
            int cmp = compare(version, event.version());
            switch (event.kind()) {
                case INTRODUCED -> { if (cmp >= 0) affected = true; }
                case FIXED -> { if (cmp >= 0) affected = false; }
                case LAST_AFFECTED -> { if (cmp > 0) affected = false; }
            }
        }
        return affected;
    }

    /** Wersja należąca do zakresu — do automatycznie generowanego przypadku pozytywnego. */
    public String sampleAffected() {
        for (Event event : events) {
            if (event.kind() == Kind.LAST_AFFECTED) {
                return event.version();
            }
        }
        for (Event event : events) {
            if (event.kind() == Kind.INTRODUCED && !isZero(event.version())) {
                return event.version();
            }
        }
        return contains("0.0.1") ? "0.0.1" : null;
    }

    /** Pierwsza wersja z poprawką — do automatycznie generowanego przypadku negatywnego. */
    public String sampleFixed() {
        for (Event event : events) {
            if (event.kind() == Kind.FIXED) {
                return event.version();
            }
        }
        return null;
    }

    public static int compare(String a, String b) {
        long[] left = numericSegments(a);
        long[] right = numericSegments(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long l = i < left.length ? left[i] : 0;
            long r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    }

    private static boolean isZero(String version) {
        return compare(version, "0") == 0;
    }

    /** {@code "v0.1.16-rc1"} → {@code [0, 1, 16]}: wiodące segmenty liczbowe, reszta ignorowana. */
    private static long[] numericSegments(String version) {
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        var segments = new ArrayList<Long>();
        for (String part : v.split("\\.")) {
            int end = 0;
            while (end < part.length() && end < 18 && Character.isDigit(part.charAt(end))) {
                end++;
            }
            if (end == 0) {
                break;
            }
            segments.add(Long.parseLong(part.substring(0, end)));
            if (end < part.length()) {
                break;
            }
        }
        return segments.stream().mapToLong(Long::longValue).toArray();
    }
}
