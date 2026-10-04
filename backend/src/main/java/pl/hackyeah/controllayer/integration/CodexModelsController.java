package pl.hackyeah.controllayer.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
class CodexModelsController {
    private final CodexModelsService service;
    CodexModelsController(CodexModelsService service) { this.service = service; }

    @GetMapping("/v1/models")
    Mono<ResponseEntity<byte[]>> models(@RequestHeader HttpHeaders headers,
            @RequestParam(value = "client_version", required = false) String clientVersion) {
        return service.models(clientVersion, new CodexHeaders(headers));
    }
}
