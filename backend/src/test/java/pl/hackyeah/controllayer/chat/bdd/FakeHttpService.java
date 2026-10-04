package pl.hackyeah.controllayer.chat.bdd;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * A fake for one external service (model/sidecar) — the same {@code
 * com.sun.net.httpserver.HttpServer} pattern as in {@code ChatCompletionControllerTest} and
 * {@code SemanticGuardControllerTest}, just extracted for reuse across Cucumber steps. The
 * response is a function of the request body — one process can serve any number of scenarios
 * without sharing state between the runner and the server.
 */
class FakeHttpService {

    record Response(int status, String body) {}

    private final HttpServer server;
    private volatile Function<String, Response> handler = body -> new Response(200, "{}");
    private volatile long delayMillis;

    FakeHttpService(String path) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext(path, exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Response response = handler.apply(requestBody);
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    void respond(Function<String, Response> newHandler) {
        this.handler = newHandler;
    }

    /** Delays every response by this long — used to force a client-side timeout deterministically. */
    void delayResponsesBy(long millis) {
        this.delayMillis = millis;
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }
}
