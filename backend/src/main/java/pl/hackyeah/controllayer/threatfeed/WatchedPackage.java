package pl.hackyeah.controllayer.threatfeed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Paczka obserwowana w OSV ({@code config/signatures/watchlist.yaml}). {@code aliases} to
 * dodatkowe nazwy, pod którymi komponent pojawia się w ruchu (np. obraz {@code ollama/ollama}
 * dla modułu Go {@code github.com/ollama/ollama}).
 */
record WatchedPackage(String ecosystem, String name, List<String> aliases) {

    WatchedPackage {
        aliases = List.copyOf(aliases);
    }

    List<String> mentionNames() {
        var names = new ArrayList<String>();
        names.add(name);
        aliases.stream().filter(alias -> !names.contains(alias)).forEach(names::add);
        return names;
    }

    String snapshotFileName() {
        return (ecosystem + "__" + name).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    String signatureId() {
        return ("SIG-PKG-" + ecosystem + "-" + name).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-").replaceAll("-+$", "");
    }

    /** Przykładowa wzmianka o wersji, jaką gateway może zobaczyć w ruchu. */
    String sampleMention(String version) {
        return switch (ecosystem) {
            case "npm" -> "npx " + name + "@" + version;
            case "PyPI" -> "pip install " + name + "==" + version;
            case "Go" -> aliases.isEmpty()
                    ? "go get " + name + "@v" + version
                    : "docker pull " + aliases.getFirst() + ":" + version;
            default -> name + "@" + version;
        };
    }

    @Override
    public String toString() {
        return ecosystem + ":" + name;
    }

    static List<WatchedPackage> load(Path file) throws IOException {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> map) || !(map.get("packages") instanceof List<?> list)) {
            throw new IllegalArgumentException(file + ": expected top-level 'packages' list");
        }
        var packages = new ArrayList<WatchedPackage>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> entry) || entry.get("ecosystem") == null || entry.get("name") == null) {
                throw new IllegalArgumentException(file + ": each package needs 'ecosystem' and 'name': " + raw);
            }
            List<String> aliases = entry.get("aliases") instanceof List<?> a
                    ? a.stream().map(String::valueOf).toList()
                    : List.of();
            packages.add(new WatchedPackage(String.valueOf(entry.get("ecosystem")), String.valueOf(entry.get("name")), aliases));
        }
        return packages;
    }
}
