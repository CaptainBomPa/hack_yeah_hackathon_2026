package pl.hackyeah.controllayer.threatfeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.hackyeah.controllayer.guard.signature.FeedRegression;
import pl.hackyeah.controllayer.guard.signature.FeedRegression.Check;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed;
import pl.hackyeah.controllayer.guard.signature.SignatureFeed.Signature;
import pl.hackyeah.controllayer.guard.signature.VersionRange;

/**
 * Threat feed: OSV → triage → proponowany feed sygnatur + raport (docs/redteam-feed.md).
 * Narzędzie niczego nie zmienia: raport mówi, co pękło i jakie gotowe bloki reguł można wkleić do
 * {@code config/signatures/active.yaml} (regresja aktywnego feedu i aktywnego + propozycje).
 *
 * <pre>
 * ./gradlew threatFeed                       # online: OSV.dev, odświeża snapshot
 * ./gradlew threatFeed --args="--offline"    # z zapisanego snapshotu, bez sieci
 * </pre>
 *
 * Opcje (ścieżki względem {@code backend/}): {@code --watchlist}, {@code --active}, {@code --snapshot},
 * {@code --out}, {@code --date}.
 */
public final class ThreatFeedTool {

    record PackageResult(WatchedPackage pkg, List<Advisory> advisories, List<Advisory> fresh, String proposalId) {}

    /**
     * Wynik regresji: {@code active} — sam aktywny feed; {@code introduced} — błędy, które pojawiają się
     * dopiero po dodaniu propozycji do aktywnego feedu (pęknięte testy propozycji albo konflikty z istniejącymi).
     */
    record Regression(List<Check> active, List<Check> introduced) {

        List<Check> activeFailures() {
            return active.stream().filter(c -> !c.passed()).toList();
        }

        /** Błędy dotyczące danej propozycji: jej własne testy albo trafienia jej sygnatury w cudze teksty. */
        List<Check> failuresOf(String proposalId) {
            return introduced.stream()
                    .filter(c -> proposalId.equals(c.signatureId()) || c.message().contains(proposalId))
                    .toList();
        }
    }

    private ThreatFeedTool() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        boolean offline = options.containsKey("offline");
        Path watchlist = Path.of(options.getOrDefault("watchlist", "config/signatures/watchlist.yaml"));
        Path active = Path.of(options.getOrDefault("active", "config/signatures/active.yaml"));
        Path snapshot = Path.of(options.getOrDefault("snapshot", "../tests/redteam/osv-snapshot"));
        Path benign = Path.of(options.getOrDefault("benign", "config/signatures/benign.yaml"));
        String date = options.getOrDefault("date", LocalDate.now(ZoneOffset.UTC).toString());
        Path out = Path.of(options.getOrDefault("out", "../tests/redteam/proposals/" + date));

        SignatureFeed activeFeed = Files.exists(active)
                ? SignatureFeed.parse(Files.readString(active, StandardCharsets.UTF_8))
                : SignatureFeed.empty();
        Set<String> covered = new HashSet<>();
        activeFeed.signatures().forEach(s -> covered.addAll(s.refs()));

        var source = new OsvSource(snapshot, offline);
        var results = new ArrayList<PackageResult>();
        var blocks = new LinkedHashMap<String, String>();
        var drafts = new StringBuilder();
        for (WatchedPackage pkg : WatchedPackage.load(watchlist)) {
            List<Advisory> advisories = Advisory.fromOsv(source.vulns(pkg), pkg);
            List<Advisory> fresh = advisories.stream()
                    .filter(a -> a.allIds().stream().noneMatch(covered::contains))
                    .toList();
            String proposalId = null;
            if (!fresh.isEmpty() && advisories.stream().anyMatch(a -> !a.ranges().isEmpty())) {
                proposalId = pkg.signatureId();
                blocks.put(proposalId, packageSignature(pkg, advisories, fresh));
            }
            fresh.stream()
                    .filter(a -> Triage.classify(a.cwes()).view() == Triage.View.PAYLOAD)
                    .forEach(a -> drafts.append(payloadDraft(pkg, a)));
            results.add(new PackageResult(pkg, advisories, fresh, proposalId));
            System.out.printf("%-45s advisories=%d new=%d%n", pkg, advisories.size(), fresh.size());
        }

        String proposedYaml = "# Proponowany feed sygnatur — wygenerowany " + date + " z OSV ("
                + (offline ? "snapshot offline" : "online") + ").\n"
                + "# NIC Z TEGO PLIKU NIE JEST AKTYWNE. Przenieś wybrane wpisy z 'signatures' do\n"
                + "# backend/config/signatures/active.yaml, przejrzyj testy i uruchom: ./gradlew signatureRegression\n"
                + "# 'drafts' to advisory, przez które atak przechodzi w treści — wzorzec (match) i testy pisze człowiek.\n"
                + "feed_version: \"proposed-" + date + "\"\n"
                + "signatures:\n" + (blocks.isEmpty() ? "  []\n" : String.join("", blocks.values()))
                + "drafts:\n" + (drafts.isEmpty() ? "  []\n" : drafts);
        SignatureFeed proposed = SignatureFeed.parse(proposedYaml);
        Regression regression = regress(activeFeed, proposed, FeedRegression.loadBenign(benign));

        String reportMd = report(date, offline, activeFeed, results, regression, blocks);
        // Kopia w <out>/../latest: stała ścieżka dla run configów (signatureRegression -Pfeed=...).
        for (Path dir : List.of(out, out.resolveSibling("latest"))) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("proposed-feed.yaml"), proposedYaml, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("report.md"), reportMd, StandardCharsets.UTF_8);
        }
        System.out.println();
        // Konsola bez ogonków: przez Gradle i cmd.exe kodowanie bywa różne, raport w pliku zostaje po polsku.
        System.out.println(ascii(summary(regression, blocks).replace("**", "").replace("`", "")));
        System.out.println("Raport: " + out.resolveSibling("latest").resolve("report.md").toAbsolutePath().normalize());
    }

    /** Regresja aktywnego feedu oraz aktywnego z propozycjami (propozycja o tym samym id zastępuje aktywną). */
    static Regression regress(SignatureFeed active, SignatureFeed proposed, List<String> benign) {
        List<Check> activeChecks = FeedRegression.run(active, benign);
        Set<String> proposedIds = new HashSet<>();
        proposed.signatures().forEach(s -> proposedIds.add(s.id()));
        var merged = new ArrayList<Signature>();
        active.signatures().stream().filter(s -> !proposedIds.contains(s.id())).forEach(merged::add);
        merged.addAll(proposed.signatures());
        Set<Check> activeFailures = new HashSet<>(activeChecks.stream().filter(c -> !c.passed()).toList());
        List<Check> introduced = FeedRegression.run(new SignatureFeed(active.feedVersion(), merged), benign).stream()
                .filter(c -> !c.passed() && !activeFailures.contains(c))
                .toList();
        return new Regression(activeChecks, introduced);
    }

    /** Krótki wynik: stan aktywnego feedu i decyzja dla każdej propozycji. */
    static String summary(Regression regression, Map<String, String> blocks) {
        var s = new StringBuilder();
        List<Check> activeFailures = regression.activeFailures();
        s.append(activeFailures.isEmpty()
                ? "- Aktywny feed: OK (" + regression.active().size() + " sprawdzeń)\n"
                : "- Aktywny feed: **PĘKŁO " + activeFailures.size() + " z " + regression.active().size()
                        + "** — szczegóły w sekcji „Co pękło”\n");
        if (blocks.isEmpty()) {
            s.append("- Nowe reguły: brak — nic nie trzeba robić\n");
        }
        for (String id : blocks.keySet()) {
            List<Check> failures = regression.failuresOf(id);
            s.append(failures.isEmpty()
                    ? "- Nowa reguła `" + id + "`: gotowa — testy i korpus benign przechodzą, można wkleić\n"
                    : "- Nowa reguła `" + id + "`: **NIE wklejaj** — pęka " + failures.size() + " sprawdzeń\n");
        }
        return s.toString();
    }

    /** Jedna sygnatura „podatny komponent" na paczkę: suma zakresów wszystkich advisory. */
    static String packageSignature(WatchedPackage pkg, List<Advisory> advisories, List<Advisory> fresh) {
        List<Advisory> withRanges = advisories.stream().filter(a -> !a.ranges().isEmpty()).toList();
        List<VersionRange> ranges = withRanges.stream().flatMap(a -> a.ranges().stream()).toList();
        var refs = new LinkedHashSet<String>();
        withRanges.forEach(a -> refs.addAll(a.allIds()));
        boolean block = withRanges.stream().anyMatch(Advisory::highOrCritical);

        String positive = ranges.stream().map(VersionRange::sampleAffected).filter(v -> v != null).findFirst().orElse(null);
        // Negatywny: najwyższa wersja z poprawką, o ile nie jest podatna w żadnym innym zakresie.
        String negative = ranges.stream().map(VersionRange::sampleFixed).filter(v -> v != null)
                .max(VersionRange::compare)
                .filter(v -> ranges.stream().noneMatch(r -> r.contains(v)))
                .orElse(null);
        // Brak takiej (np. advisory z last_affected bez poprawki): wersja syntetyczna powyżej wszystkich zakresów.
        boolean syntheticNegative = false;
        if (negative == null) {
            negative = ranges.stream().flatMap(r -> r.events().stream()).map(VersionRange.Event::version)
                    .max(VersionRange::compare).map(ThreatFeedTool::bumpPatch)
                    .filter(v -> ranges.stream().noneMatch(r -> r.contains(v)))
                    .orElse(null);
            syntheticNegative = negative != null;
        }

        var y = new StringBuilder();
        y.append("  - id: ").append(pkg.signatureId()).append('\n');
        y.append("    title: ").append(q("Vulnerable " + pkg + " (" + withRanges.size() + " advisories in OSV)")).append('\n');
        y.append("    class: vulnerable-component\n");
        y.append("    # nowe od ostatniej akceptacji: ").append(String.join(", ", fresh.stream().map(Advisory::id).toList())).append('\n');
        y.append("    refs: [").append(String.join(", ", refs.stream().map(ThreatFeedTool::q).toList())).append("]\n");
        y.append("    action: ").append(block ? "block" : "monitor")
                .append(block ? "   # co najmniej jedno advisory HIGH/CRITICAL\n" : "   # brak advisory HIGH/CRITICAL\n");
        y.append("    match:\n");
        y.append("      type: package-version\n");
        y.append("      names: [").append(String.join(", ", pkg.mentionNames().stream().map(ThreatFeedTool::q).toList())).append("]\n");
        y.append("      ranges:\n");
        for (Advisory advisory : withRanges) {
            for (List<Map<String, String>> events : advisory.rawRanges()) {
                y.append("        - [");
                y.append(String.join(", ", events.stream().map(e -> {
                    var entry = e.entrySet().iterator().next();
                    return "{" + entry.getKey() + ": " + q(entry.getValue()) + "}";
                }).toList()));
                y.append("]   # ").append(advisory.id()).append('\n');
            }
        }
        y.append("    tests:   # wygenerowane automatycznie — przejrzyj przed akceptacją\n");
        y.append("      positive: [").append(positive == null ? "" : q(pkg.sampleMention(positive))).append("]\n");
        y.append("      negative: [").append(negative == null ? "" : q(pkg.sampleMention(negative))).append("]")
                .append(syntheticNegative ? "   # wersja syntetyczna: powyżej wszystkich zakresów OSV" : "")
                .append('\n');
        return y.toString();
    }

    /** {@code 0.3.14} → {@code 0.3.15}. */
    static String bumpPatch(String version) {
        String[] parts = version.split("\\.");
        String last = parts[parts.length - 1].replaceAll("\\D.*$", "");
        parts[parts.length - 1] = String.valueOf(Long.parseLong(last.isEmpty() ? "0" : last) + 1);
        return String.join(".", parts);
    }

    static String payloadDraft(WatchedPackage pkg, Advisory advisory) {
        var y = new StringBuilder();
        y.append("  - advisory: ").append(q(advisory.id())).append('\n');
        y.append("    cve: ").append(q(advisory.cve())).append('\n');
        y.append("    package: ").append(q(pkg.toString())).append('\n');
        y.append("    severity: ").append(advisory.severity()).append('\n');
        y.append("    cwes: [").append(String.join(", ", advisory.cwes())).append("]\n");
        y.append("    class: ").append(Triage.classify(advisory.cwes()).threatClass()).append('\n');
        y.append("    summary: ").append(q(advisory.summary())).append('\n');
        y.append("    todo: \"napisz match (regex/contains) na ładunek ataku + tests.positive/negative\"\n");
        return y.toString();
    }

    static String report(String date, boolean offline, SignatureFeed active, List<PackageResult> results,
            Regression regression, Map<String, String> blocks) {
        var r = new StringBuilder();
        r.append("# Threat feed — raport ").append(date).append("\n\n");
        r.append("Źródło: OSV.dev (").append(offline ? "snapshot offline `tests/redteam/osv-snapshot/`" : "online, snapshot odświeżony")
                .append(") · aktywny feed: `").append(active.feedVersion()).append("` (")
                .append(active.signatures().size()).append(" sygnatur)\n\n");
        r.append("Raport niczego nie zmienia. O tym, co trafia do `backend/config/signatures/active.yaml`, decyduje człowiek.\n\n");

        r.append("## Wynik\n\n").append(summary(regression, blocks)).append('\n');

        r.append("## Co pękło\n\n");
        List<Check> activeFailures = regression.activeFailures();
        if (activeFailures.isEmpty() && regression.introduced().isEmpty()) {
            r.append("Nic. Aktywny feed i wszystkie propozycje przechodzą regresję.\n\n");
        } else {
            r.append("| Gdzie | Sygnatura | Sprawdzenie | Tekst | Dlaczego |\n|---|---|---|---|---|\n");
            activeFailures.forEach(c -> failureRow(r, "aktywny feed", c));
            regression.introduced().forEach(c -> failureRow(r, "po dodaniu propozycji", c));
            r.append("\n`false positive` = sygnatura trafia w niewinny tekst (za szeroka). ")
                    .append("`not matched` = sygnatura nie łapie własnego przypadku pozytywnego.\n\n");
        }

        r.append("## Reguły do wprowadzenia\n\n");
        if (blocks.isEmpty()) {
            r.append("Brak. Wszystkie advisory z OSV dla obserwowanych paczek są już pokryte.\n\n");
        } else {
            r.append("Wklej wybrany blok na koniec `backend/config/signatures/active.yaml` (wcięcie bez zmian), zapisz i uruchom ")
                    .append("`3. Signature regression - active feed`. Gateway przeładuje plik sam.\n\n");
            for (var entry : blocks.entrySet()) {
                boolean ready = regression.failuresOf(entry.getKey()).isEmpty();
                r.append("### `").append(entry.getKey()).append("` — ")
                        .append(ready ? "gotowa do wklejenia" : "NIE wklejaj, pęka (patrz „Co pękło”)").append("\n\n");
                r.append("```yaml\n").append(entry.getValue()).append("```\n\n");
            }
        }

        r.append("## Podsumowanie paczek\n\n");
        r.append("| Paczka | Advisory | Pokryte | Nowe | Nowe z ładunkiem w treści | Propozycja |\n|---|---|---|---|---|---|\n");
        for (PackageResult result : results) {
            long payload = result.fresh().stream()
                    .filter(a -> Triage.classify(a.cwes()).view() == Triage.View.PAYLOAD).count();
            r.append("| ").append(result.pkg()).append(" | ").append(result.advisories().size())
                    .append(" | ").append(result.advisories().size() - result.fresh().size())
                    .append(" | ").append(result.fresh().size())
                    .append(" | ").append(payload)
                    .append(" | ").append(result.proposalId() == null ? "—" : "`" + result.proposalId() + "`")
                    .append(" |\n");
        }

        r.append("\n## Nowe advisory\n\n");
        r.append("Widoczność: **payload** = atak przechodzi przez treść żądania/odpowiedzi (warto napisać sygnaturę wzorca, ")
                .append("szkic w `drafts`); **component** = błąd wewnątrz komponentu, w ruchu widać najwyżej jego wersję.\n\n");
        for (PackageResult result : results) {
            if (result.fresh().isEmpty()) {
                continue;
            }
            r.append("### ").append(result.pkg()).append("\n\n");
            r.append("| Advisory | CVE | Severity | CWE | Klasa | Widoczność | Opublikowano | Opis |\n|---|---|---|---|---|---|---|---|\n");
            for (Advisory a : result.fresh()) {
                Triage.Result triage = Triage.classify(a.cwes());
                r.append("| ").append(a.id()).append(" | ").append(a.cve()).append(" | ").append(a.severity())
                        .append(" | ").append(String.join(", ", a.cwes())).append(" | ").append(triage.threatClass())
                        .append(" | ").append(triage.view() == Triage.View.PAYLOAD ? "payload" : "component")
                        .append(" | ").append(a.published().length() >= 10 ? a.published().substring(0, 10) : a.published())
                        .append(" | ").append(a.summary().replace("|", "\\|"))
                        .append(a.ranges().isEmpty() ? " _(brak zakresu wersji w OSV)_" : "")
                        .append(" |\n");
            }
            r.append('\n');
        }

        r.append("Advisory z widocznością **payload** są też w `proposed-feed.yaml` → `drafts`. Dla nich wzorzec ataku ")
                .append("trzeba napisać ręcznie; to opcjonalne i nie blokuje niczego.\n");
        return r.toString();
    }

    private static void failureRow(StringBuilder r, String where, Check check) {
        r.append("| ").append(where).append(" | ")
                .append(check.signatureId() == null ? "korpus benign" : "`" + check.signatureId() + "`")
                .append(" | ").append(check.kind().name().toLowerCase())
                .append(" | `").append(check.text().replace("|", "\\|").replace("`", "'")).append("` | ")
                .append(check.message().replace("|", "\\|")).append(" |\n");
    }

    static String ascii(String text) {
        String stripped = java.text.Normalizer.normalize(text.replace('ł', 'l').replace('Ł', 'L'), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.replace('—', '-').replace('„', '"').replace('”', '"');
    }

    /** Napis YAML w podwójnym cudzysłowie. */
    static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> parseArgs(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + args[i]);
            }
            String key = args[i].substring(2);
            if (key.equals("offline")) {
                options.put(key, "true");
            } else if (i + 1 < args.length) {
                options.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("missing value for --" + key);
            }
        }
        return options;
    }
}
