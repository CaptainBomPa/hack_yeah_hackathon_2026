package pl.hackyeah.controllayer.chat;

/**
 * Czasy jednego żądania mierzone w gatewayu — odpowiada `latency` w `GuardedChatResponse`
 * (docs/frontend-flows-and-api.md §5.1). Bez tego UI musiałoby liczyć całość w przeglądarce
 * i nie umiałoby oddzielić czasu kontroli od czasu chronionego modelu.
 *
 * @param totalMs    czas od wejścia żądania do kontrolera do złożenia odpowiedzi (bez zapisu audytu)
 * @param upstreamMs czas wywołania chronionego modelu; null, gdy do modelu nie doszło
 */
public record ChatLatency(long totalMs, Long upstreamMs) {}
