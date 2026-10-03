package pl.hackyeah.controllayer.chat.bdd;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The only steps in this suite that call the real Python semantic-sidecar process (the actual
 * Horizon prompt-injection classifier) instead of the HTTP fake used everywhere else — see
 * {@code semantic_sidecar_e2e.feature}. Everywhere else, SEM-001 is tested against a scripted
 * fake score, which proves the Java-side threshold/fail-open/fail-closed logic but never proves
 * the real model classifies real text correctly or that the HTTP contract still matches.
 *
 * <p>By default these scenarios are skipped (not failed) unless a sidecar already answers on
 * {@code localhost:8001} — plain {@code ./gradlew test} stays fast and Docker-free, which matters
 * for anyone (CI, a grader) without Docker or without the ~600MB model already downloaded.
 *
 * <p>Run {@code ./gradlew testWithSidecar} instead to get the real thing automatically: that task
 * sets {@code -DsidecarE2E=true}, which makes {@link #startSidecarIfRequested()} stand up
 * {@code semantic-sidecar-init}/{@code semantic-sidecar} from the repo's own {@code
 * docker-compose.yml} via Testcontainers before any scenario runs, and {@link
 * #stopSidecarIfStarted()} tears it down again after the whole suite finishes — one command,
 * Docker in the background, no manual {@code docker compose up}/{@code down}.
 *
 * <p>Three rough edges fixed here, each of which looks exactly like a hang from the outside:
 * (1) {@code ComposeContainer} by default preemptively pulls every image referenced ANYWHERE in
 * {@code docker-compose.yml} before it ever starts our two services — including {@code postgres}
 * and the multi-GB {@code ollama/ollama} — easily 20+ minutes on its own; fixed with {@code
 * withPull(false)}, since {@code docker compose up --build} still pulls what our two services
 * actually need, on its own. (2) that build/download then happens with zero console output by
 * default — fixed with a log consumer that prints straight to {@code System.out} (see {@code
 * testWithSidecar}'s {@code testLogging.showStandardStreams} in {@code build.gradle}).
 * (3) Testcontainers gives every run a fresh, randomly-suffixed compose project, which would
 * otherwise re-download the ~600MB model on every single run — fixed with a fixed external cache
 * volume, see {@code docker/semantic-sidecar-e2e-cache.yml}.
 */
public class SemanticSidecarE2ESteps {

    private static final String DEFAULT_URL = "http://localhost:8001";
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final boolean AUTO_START = Boolean.parseBoolean(
            System.getProperty("sidecarE2E", System.getenv().getOrDefault("SIDECAR_E2E", "false")));

    // Testcontainers' ComposeContainer always appends a random suffix to the compose project name
    // (see ComposeDelegate#randomProjectId), so Docker Compose would namespace `sidecar_models` as
    // a fresh, empty, project-scoped volume on every run — re-downloading the ~600MB model from
    // Hugging Face every time. docker/semantic-sidecar-e2e-cache.yml overrides that volume to this
    // fixed, external name instead, so the cache survives across runs regardless of project name.
    private static final String MODEL_CACHE_VOLUME = "hackyeah-semantic-sidecar-models-cache";

    private static ComposeContainer sidecarCompose;
    private static String autoStartedUrl;

    private final BddWorld world;

    public SemanticSidecarE2ESteps(BddWorld world) {
        this.world = world;
    }

    /** Runs once before the whole suite — Cucumber's equivalent of a JUnit {@code @BeforeAll}. */
    @BeforeAll
    public static void startSidecarIfRequested() throws IOException, InterruptedException {
        if (!AUTO_START) {
            return;
        }
        ensureModelCacheVolumeExists();
        File composeFile = new File(System.getProperty("compose.file", defaultComposeFilePath()));
        File cacheOverride = classpathResourceFile("docker/semantic-sidecar-e2e-cache.yml");
        var compose = new ComposeContainer(composeFile, cacheOverride)
                .withServices("semantic-sidecar-init", "semantic-sidecar")
                // THE actual cause of multi-minute "hangs": by default ComposeContainer.start()
                // preemptively pulls every image referenced ANYWHERE in the compose file — not just
                // in withServices() above — including postgres, ollama/ollama (multi-GB) and every
                // other service's build base image, before it ever gets to our two services. withPull
                // (false) skips that scan entirely; `docker compose up --build` still pulls whatever
                // the two services we actually start need, on its own, as part of the normal build.
                .withPull(false)
                // Streams straight to System.out (not SLF4J/Logback) so the build + model download
                // is visible live in the Gradle console — without this, a slow first build/download
                // looks indistinguishable from a hang (`testLogging.showStandardStreams` in
                // build.gradle's testWithSidecar task makes Gradle actually print it).
                .withLogConsumer("semantic-sidecar-init", logConsumer("sidecar-init"))
                .withLogConsumer("semantic-sidecar", logConsumer("sidecar"))
                .withExposedService("semantic-sidecar", 8001,
                        Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(10)));
        compose.start();
        // Only recorded once start() actually succeeds, so a failed start never gets a redundant
        // (and equally failing) stop() attempt from stopSidecarIfStarted() afterwards.
        sidecarCompose = compose;
        autoStartedUrl = "http://" + compose.getServiceHost("semantic-sidecar", 8001) + ":"
                + compose.getServicePort("semantic-sidecar", 8001);
    }

    /** {@code docker volume create} is idempotent — a no-op if the volume already exists. */
    private static void ensureModelCacheVolumeExists() throws IOException, InterruptedException {
        var process = new ProcessBuilder("docker", "volume", "create", MODEL_CACHE_VOLUME)
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "`docker volume create " + MODEL_CACHE_VOLUME + "` exited with " + exitCode);
        }
    }

    private static java.util.function.Consumer<OutputFrame> logConsumer(String label) {
        return frame -> System.out.print("[" + label + "] " + frame.getUtf8String());
    }

    /** Runs once after the whole suite, win or lose — stops and removes the containers it started. */
    @AfterAll
    public static void stopSidecarIfStarted() {
        if (sidecarCompose != null) {
            sidecarCompose.stop();
            sidecarCompose = null;
            autoStartedUrl = null;
        }
    }

    @Given("the real semantic sidecar is reachable")
    public void theRealSemanticSidecarIsReachable() {
        String baseUrl = autoStartedUrl != null ? autoStartedUrl
                : System.getProperty("sidecar.e2e.url", System.getenv().getOrDefault("SIDECAR_E2E_URL", DEFAULT_URL));
        Assumptions.assumeTrue(isHealthy(baseUrl), () -> "real semantic-sidecar not reachable at " + baseUrl
                + " — run `./gradlew testWithSidecar` to start it automatically (Testcontainers), "
                + "or `docker compose up -d semantic-sidecar-init semantic-sidecar` yourself first");
        world.useRealSemanticSidecar(baseUrl);
    }

    private static String defaultComposeFilePath() {
        return new File(System.getProperty("user.dir")).getParentFile().toPath().resolve("docker-compose.yml")
                .toString();
    }

    private static File classpathResourceFile(String path) {
        var url = SemanticSidecarE2ESteps.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new IllegalStateException("missing test resource on classpath: " + path);
        }
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isHealthy(String baseUrl) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(HEALTH_CHECK_TIMEOUT).build()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
