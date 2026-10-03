package pl.hackyeah.controllayer.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** HTTP entry point for the native Responses protocol. */
@RestController
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
public class ResponsesController {
    private final ResponsesService service;

    public ResponsesController(ResponsesService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> responses(@RequestBody String body,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = "X-Session-Id", required = false) String session) {
        return service.respond(body, session, new CodexHeaders(headers), ResponsesOperation.GENERATE);
    }

    @PostMapping(value = "/v1/responses/compact", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> compact(@RequestBody String body,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = "X-Session-Id", required = false) String session) {
        return service.respond(body, session, new CodexHeaders(headers), ResponsesOperation.COMPACT);
    }
}
