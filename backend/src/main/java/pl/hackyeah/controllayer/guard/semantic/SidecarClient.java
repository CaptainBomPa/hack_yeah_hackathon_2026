package pl.hackyeah.controllayer.guard.semantic;

import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Klient HTTP sidecara semantycznego. Wołanie jest BLOKUJĄCE (`block(timeout)`), bo `Guard` jest
 * synchroniczny, a `GuardChain` biegnie w kontrolerze na `Schedulers.boundedElastic()`, gdzie blokowanie
 * jest dozwolone (nie wolno wołać go z wątku pętli zdarzeń WebFlux). Każdy błąd (timeout, połączenie,
 * HTTP 5xx, zły JSON) kończy się wyjątkiem: decyzję "fail-open / fail-closed" podejmuje guard, nie klient.
 */
@Component
public class SidecarClient {

    private final WebClient webClient;
    private final String baseUrl;

    public SidecarClient(WebClient.Builder webClientBuilder, SidecarProperties properties) {
        this.webClient = webClientBuilder.build();
        this.baseUrl = properties.baseUrl();
    }

    /**
     * @param checkpoint punkt kontroli sidecara: P1 prompt, P2 dane niezaufane, P3 narzędzie, P4 odpowiedź, P5 pamięć.
     * @param sessionId  opcjonalny identyfikator sesji (kontekst dla detektorów sesyjnych), może być null.
     */
    public SidecarResponse classify(String checkpoint, String text, String sessionId, Duration timeout) {
        Map<String, Object> context = sessionId == null ? Map.of() : Map.of("session_id", sessionId);
        Map<String, Object> body = Map.of("checkpoint", checkpoint, "text", text, "context", context);
        SidecarResponse response = webClient
                .post()
                .uri(baseUrl + "/classify")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SidecarResponse.class)
                .block(timeout);
        if (response == null) {
            throw new IllegalStateException("sidecar returned an empty body");
        }
        return response;
    }
}
