package pl.hackyeah.controllayer.integration;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import pl.hackyeah.controllayer.model.ModelCatalog;
import pl.hackyeah.controllayer.policy.ActivePolicy;
import pl.hackyeah.controllayer.policy.ModelAccessPolicy;
import pl.hackyeah.controllayer.policy.PolicySource;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ArrayNode;

/** Preserve Codex model metadata, exposing only the active policy's allowed subscription models. */
@Service
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
class CodexModelsService {
    private final ResponsesUpstream upstream;
    private final PolicySource policies;
    private final ModelCatalog catalog;
    private final ModelAccessPolicy access;
    private final IntegrationProperties integration;

    CodexModelsService(ResponsesUpstream upstream, PolicySource policies, ModelCatalog catalog,
            ModelAccessPolicy access, IntegrationProperties integration) {
        this.upstream = upstream;
        this.policies = policies;
        this.catalog = catalog;
        this.access = access;
        this.integration = integration;
    }

    Mono<ResponseEntity<byte[]>> models(String clientVersion, CodexHeaders credentials) {
        if (!credentials.hasSubscriptionCredentials()) return Mono.just(error(401));
        if (clientVersion != null && !clientVersion.matches("[A-Za-z0-9._-]{1,64}")) return Mono.just(error(400));
        ActivePolicy policy = policies.current();
        return ReactiveSecurityContextHolder.getContext().flatMap(context -> {
            var authentication = context.getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) return Mono.empty();
            String role = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .map(authority -> authority.substring(5).toLowerCase(Locale.ROOT)).findFirst().orElse("");
            return upstream.models(clientVersion, credentials).map(reply -> filtered(reply, policy, role));
        }).switchIfEmpty(Mono.fromSupplier(() -> error(401)))
                .onErrorResume(exception -> Mono.just(error(exception instanceof ResponsesUpstream.Failure failure ? failure.status : 502)));
    }

    private ResponseEntity<byte[]> filtered(ResponsesUpstream.Reply reply, ActivePolicy policy, String role) {
        var response = ResponsesPayload.parse(reply.body());
        if (!(response.get("models") instanceof ArrayNode models)) throw new IllegalArgumentException("Invalid model catalog");
        for (int index = models.size() - 1; index >= 0; index--) {
            String tag = models.get(index).path("slug").asText();
            boolean allowed = access.allowsModel(policy, role, tag) && catalog.resolveAllowed(policy, tag)
                    .filter(model -> model.baseUrl().equals(integration.codexBaseUrl())).isPresent();
            if (!allowed) models.remove(index);
        }
        return ResponseEntity.ok().headers(reply.headers()).contentType(MediaType.APPLICATION_JSON)
                .body(ResponsesPayload.encode(response).getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> error(int status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"type\":\"control_layer_error\",\"code\":\"integration.models-unavailable\"}}"
                        .getBytes(StandardCharsets.UTF_8));
    }
}
