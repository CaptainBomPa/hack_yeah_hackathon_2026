package pl.hackyeah.controllayer.integration;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
public class ResponsesUpstream {
    private final WebClient client;
    private final IntegrationProperties properties;

    public ResponsesUpstream(WebClient.Builder builder, IntegrationProperties properties) {
        this.properties = properties;
        this.client = builder.clone().codecs(codecs -> codecs.defaultCodecs()
                .maxInMemorySize(properties.maxResponseBytes())).build();
    }

    Mono<Reply> complete(ObjectNode request, CodexHeaders credentials, ResponsesOperation operation) {
        if (!credentials.hasSubscriptionCredentials()) return Mono.error(new Failure(401, new HttpHeaders()));
        return client.post().uri(properties.codexBaseUrl().replaceAll("/+$", "") + "/" + operation.path())
                .headers(credentials::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(request.path("stream").asBoolean(false) ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                .bodyValue(ResponsesPayload.encode(request))
                .exchangeToMono(this::readReply).timeout(properties.timeout());
    }

    Mono<Reply> models(String clientVersion, CodexHeaders credentials) {
        var uri = UriComponentsBuilder.fromUriString(properties.codexBaseUrl()).path("/models");
        if (clientVersion != null) uri.queryParam("client_version", clientVersion);
        return client.get().uri(uri.build().toUri()).headers(credentials::applyTo)
                .accept(MediaType.APPLICATION_JSON).exchangeToMono(this::readReply).timeout(properties.timeout());
    }

    private Mono<Reply> readReply(ClientResponse response) {
        var headers = CodexHeaders.responseHeaders(response.headers().asHttpHeaders());
        if (!response.statusCode().is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(new Failure(response.statusCode().value(), headers)));
        }
        return response.bodyToMono(byte[].class).map(bytes -> new Reply(new String(bytes, StandardCharsets.UTF_8), headers));
    }

    record Reply(String body, HttpHeaders headers) {
        @Override public String toString() { return "UpstreamReply[redacted]"; }
    }

    static final class Failure extends RuntimeException {
        final int status;
        final HttpHeaders headers;
        Failure(int status, HttpHeaders headers) {
            super("Codex upstream rejected request");
            this.status = status;
            this.headers = headers;
        }
    }
}
