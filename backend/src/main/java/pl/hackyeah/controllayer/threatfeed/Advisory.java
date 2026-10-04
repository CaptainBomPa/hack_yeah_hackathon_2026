package pl.hackyeah.controllayer.threatfeed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pl.hackyeah.controllayer.guard.signature.VersionRange;

/**
 * Advisory z OSV sprowadzone do pól potrzebnych w triage. {@code ranges} to tylko zakresy
 * {@code SEMVER}/{@code ECOSYSTEM} dla obserwowanej paczki (zakresy {@code GIT} na commitach
 * nie dają się dopasować do wersji w ruchu).
 */
record Advisory(
        String id,
        List<String> aliases,
        String summary,
        String published,
        String severity,
        List<String> cwes,
        List<List<Map<String, String>>> rawRanges,
        List<VersionRange> ranges) {

    /** Wszystkie identyfikatory (id + aliasy, np. CVE). */
    List<String> allIds() {
        var ids = new ArrayList<String>();
        ids.add(id);
        ids.addAll(aliases);
        return ids;
    }

    String cve() {
        return aliases.stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse("");
    }

    boolean highOrCritical() {
        return severity.equals("CRITICAL") || severity.equals("HIGH");
    }

    /**
     * Rekordy OSV → advisory dla paczki: pomija wycofane ({@code withdrawn}) i duplikaty — ten sam
     * błąd bywa w OSV pod kilkoma id (GHSA-…, GO-…, PYSEC-…) połączonymi aliasami; wygrywa GHSA.
     */
    static List<Advisory> fromOsv(List<Map<String, Object>> vulns, WatchedPackage pkg) {
        var sorted = new ArrayList<>(vulns);
        sorted.sort(Comparator.comparing((Map<String, Object> v) -> !String.valueOf(v.get("id")).startsWith("GHSA-"))
                .thenComparing(v -> String.valueOf(v.get("id"))));
        Set<String> seen = new HashSet<>();
        var advisories = new ArrayList<Advisory>();
        for (Map<String, Object> vuln : sorted) {
            if (vuln.get("withdrawn") != null) {
                continue;
            }
            String id = String.valueOf(vuln.get("id"));
            List<String> aliases = strings(vuln.get("aliases"));
            if (seen.contains(id) || aliases.stream().anyMatch(seen::contains)) {
                seen.add(id);
                seen.addAll(aliases);
                continue;
            }
            seen.add(id);
            seen.addAll(aliases);
            advisories.add(toAdvisory(vuln, id, aliases, pkg));
        }
        advisories.sort(Comparator.comparing(Advisory::published).reversed());
        return advisories;
    }

    private static Advisory toAdvisory(Map<String, Object> vuln, String id, List<String> aliases, WatchedPackage pkg) {
        Map<?, ?> dbSpecific = vuln.get("database_specific") instanceof Map<?, ?> m ? m : Map.of();
        String severity = dbSpecific.get("severity") == null
                ? "UNKNOWN" : String.valueOf(dbSpecific.get("severity")).toUpperCase(Locale.ROOT);
        if (severity.equals("MODERATE")) {
            severity = "MEDIUM";
        }
        var rawRanges = new ArrayList<List<Map<String, String>>>();
        var ranges = new ArrayList<VersionRange>();
        for (Map<String, Object> affected : OsvSource.listOfMaps(vuln.get("affected"))) {
            if (!(affected.get("package") instanceof Map<?, ?> p)
                    || !pkg.ecosystem().equalsIgnoreCase(String.valueOf(p.get("ecosystem")))
                    || !pkg.name().equalsIgnoreCase(String.valueOf(p.get("name")))) {
                continue;
            }
            for (Map<String, Object> range : OsvSource.listOfMaps(affected.get("ranges"))) {
                String type = String.valueOf(range.get("type"));
                if (!type.equals("SEMVER") && !type.equals("ECOSYSTEM")) {
                    continue;
                }
                List<Map<String, String>> events = OsvSource.listOfMaps(range.get("events")).stream()
                        .map(e -> {
                            var entry = e.entrySet().iterator().next();
                            return Map.of(entry.getKey(), String.valueOf(entry.getValue()));
                        })
                        .toList();
                try {
                    ranges.add(VersionRange.fromEvents(events));
                    rawRanges.add(events);
                } catch (IllegalArgumentException unusable) {
                    // np. "limit" albo wersja nieliczbowa — zakres pomijamy, advisory zostaje w raporcie
                }
            }
        }
        String summary = vuln.get("summary") != null ? String.valueOf(vuln.get("summary"))
                : vuln.get("details") != null ? String.valueOf(vuln.get("details")) : "";
        return new Advisory(id, aliases, oneLine(summary), String.valueOf(vuln.getOrDefault("published", "")),
                severity, strings(dbSpecific.get("cwe_ids")), rawRanges, ranges);
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 137) + "..." : flat;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
