package pl.hackyeah.controllayer.threatfeed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Źródło advisory: OSV.dev ({@code POST https://api.osv.dev/v1/query}) albo zapisany snapshot
 * odpowiedzi (tryb offline — demo i testy bez internetu). W trybie online każda odpowiedź jest
 * zapisywana do snapshotu, więc snapshot to zawsze ostatnie realne dane z OSV.
 */
final class OsvSource {

    static final URI QUERY = URI.create("https://api.osv.dev/v1/query");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Path snapshotDir;
    private final boolean offline;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    OsvSource(Path snapshotDir, boolean offline) {
        this.snapshotDir = snapshotDir;
        this.offline = offline;
    }

    /** Surowe rekordy OSV ({@code vulns[]}) dla paczki. */
    List<Map<String, Object>> vulns(WatchedPackage pkg) throws IOException, InterruptedException {
        Path snapshot = snapshotDir.resolve(pkg.snapshotFileName());
        String body;
        if (offline) {
            if (!Files.exists(snapshot)) {
                throw new IOException("offline mode: no snapshot " + snapshot + " (run once without --offline)");
            }
            body = Files.readString(snapshot, StandardCharsets.UTF_8);
        } else {
            body = JSON.writeValueAsString(Map.of("vulns", fetchAllPages(pkg)));
            Files.createDirectories(snapshotDir);
            Files.writeString(snapshot, body, StandardCharsets.UTF_8);
        }
        return listOfMaps(JSON.readValue(body, MAP).get("vulns"));
    }

    private List<Map<String, Object>> fetchAllPages(WatchedPackage pkg) throws IOException, InterruptedException {
        var all = new ArrayList<Map<String, Object>>();
        String pageToken = null;
        do {
            var query = new LinkedHashMap<String, Object>();
            query.put("package", Map.of("name", pkg.name(), "ecosystem", pkg.ecosystem()));
            if (pageToken != null) {
                query.put("page_token", pageToken);
            }
            HttpRequest request = HttpRequest.newBuilder(QUERY)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(query)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OSV " + pkg + " HTTP " + response.statusCode());
            }
            Map<String, Object> page = JSON.readValue(response.body(), MAP);
            all.addAll(listOfMaps(page.get("vulns")));
            pageToken = page.get("next_page_token") instanceof String token && !token.isBlank() ? token : null;
        } while (pageToken != null);
        return all;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }
}
