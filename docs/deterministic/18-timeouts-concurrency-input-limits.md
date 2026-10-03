# Timeouty, limity współbieżności i limity rozmiaru wejścia (resource limits / LLM10)
> **ID:** LIMIT-001..LIMIT-012  | **Kategoria:** resource | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** input / session / LLM-call / output (streaming)

## 1. Overview

Chronimy dostępność i zasoby gatewaya oraz — przede wszystkim — **Raspberry Pi z Ollamą**, który jest wąskim gardłem całego systemu
(VISION.md §2–3: ~10–15 tok/s dla `qwen2.5:1.5b` na Pi 5 CPU-only). Jedno długie żądanie albo kilka równoległych potrafi zająć model
na minuty i zablokować demo dla jury. To bezpośrednio OWASP **LLM10:2025 Unbounded Consumption** (kategorie: Variable-Length Input Flood,
Continuous Input Overflow, Resource-Intensive Queries, Denial of Wallet) oraz punkt 5 i 11 katalogu deterministycznego z VISION.md
(„limity rozmiaru/zagnieżdżenia", „circuit breaker przy zbyt długiej inferencji").

Zakres kontroli:
1. **Limity rozmiaru** — body HTTP (przed i po dekompresji), liczba znaków/tokenów promptu, liczba wiadomości w `messages[]`, głębokość/rozmiar JSON (MCP tool-calls).
2. **Timeouty** — odczyt nagłówków/body (slowloris), czas całej inferencji, czas bezczynności strumienia, timeout do sidecara.
3. **Współbieżność** — bulkhead/semafor per model i per caller, ograniczona kolejka, load shedding (HTTP 429/503 + `Retry-After`).
4. **Parametry generacji** — server-side cap na `max_tokens`/`num_predict`, `num_ctx`, `n`, wymuszenie allowlisty opcji Ollamy.
5. **Streaming cancellation** — rozłączenie klienta lub przekroczenie budżetu musi przerwać generację na Pi, a nie tylko zamknąć socket.
6. **Many-shot / context stuffing** — twardy limit liczby „tur" w jednym żądaniu (heurystyka strukturalna, nie semantyczna).

## 2. Threat / Attack

Mechanizmy ataku (każdy kończy się wyczerpaniem CPU/RAM Pi, kolejki lub puli wątków/połączeń gatewaya):

1. **Variable-Length Input Flood / context stuffing** — atakujący wysyła prompty o maksymalnej dopuszczalnej długości. Koszt prefill rośnie z długością,
   koszt KV-cache rośnie liniowo z `n_ctx`; na CPU Pi to sekundy–minuty na żądanie.
2. **Request flooding / brak limitu współbieżności** — Ollama domyślnie `OLLAMA_NUM_PARALLEL=1`, kolejka do `OLLAMA_MAX_QUEUE=512`, potem HTTP 503.
   Kolejka 512 na Pi oznacza, że ostatnie żądanie czeka godzinami; łatwo „zakorkować" model jednym klientem i zagłodzić pozostałych (head-of-line blocking).
3. **Runaway generation** — brak `max_tokens`/`num_predict` lub parametr przekazywany od użytkownika bez capa; model w pętli powtórzeń generuje do końca kontekstu.
4. **Sponge examples** — wejścia dobrane tak, by maksymalizować czas/energię inferencji (nawet ~30x dla modeli językowych wg badań).
5. **Slowloris / slow-body (slow POST)** — wiele połączeń z częściowym nagłówkiem/body wysyłanym bajt po bajcie; wyczerpuje pulę połączeń Netty bez dużego ruchu.
6. **Zip/decompression bomb** — `Content-Encoding: gzip` z małym body rozpakowującym się do GB; wyczerpuje heap JVM gatewaya (CWE-409).
7. **Deeply nested JSON / duże liczby / długie stringi** — `[[[[...]]]]` powoduje `StackOverflowError` w parserach rekurencyjnych; ogromne stringi/liczby powodują alokacje i CPU (BigInteger).
8. **Many-shot jailbreaking** — setki fałszywych dialogów user/assistant w jednym prompcie przełamują alignment (skalowanie potęgowe względem liczby shotów).
   Tu limit długości jest **mitygacją częściową**, nie pełną.
9. **Porzucone strumienie** — klient zamyka połączenie SSE/NDJSON, a gateway/Ollama kontynuują generację dla nikogo (marnowanie Pi, „zombie generations").
10. **HTTP/2 Rapid Reset / stream flood** — otwieranie i natychmiastowe resetowanie strumieni (dotyczy, jeśli gateway wystawia h2).
11. **Pętle agentowe / rekurencyjne tool-calls** — agent wywołuje kolejne wywołania modelu bez końca (patrz też budżety w pliku o budget governance).

## 3. Real-World Evidence

| Tag | Co | Mechanizm / komponent / wpływ / zapobieganie |
|---|---|---|
| `[RESEARCH]` | **OWASP LLM10:2025 Unbounded Consumption** — https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ | Katalog: Variable-Length Input Flood, Denial of Wallet, Continuous Input Overflow, Resource-Intensive Queries. Zalecenia: ścisła walidacja rozmiaru wejścia, rate limiting i kwoty, timeouty i throttling, graceful degradation, ograniczanie kolejki, logowanie i anomaly detection. Zweryfikowane (WebFetch). |
| `[RESEARCH]` | **Sponge Examples** (Shumailov i in., arXiv:2006.03463) — https://arxiv.org/abs/2006.03463 | Wejścia maksymalizujące latencję/energię; modele językowe „zaskakująco podatne", wzrost latencji i energii do ~30x, na CPU/GPU/ASIC. Mitygacja: limity czasu/kosztu na żądanie. Zweryfikowane na poziomie streszczenia wyników wyszukiwania. |
| `[RESEARCH]` | **Many-Shot Jailbreaking** (Anthropic, 2.04.2024) — https://www.anthropic.com/research/many-shot-jailbreaking | Do 256 fałszywych dialogów w prompcie; skuteczność rośnie wg prawa potęgowego z liczbą shotów, większe modele bardziej podatne. Mitygacja klasyfikacją/modyfikacją promptu obniżyła skuteczność z 61% do 2% (czyli mitygacja jest semantyczna). Zweryfikowane (WebFetch). |
| `[RESEARCH]` | **Fill and Squeeze** — DoS na schedulery serwerów LLM (vLLM/SGLang), arXiv 2511.04686 — https://arxiv.org/pdf/2511.04686v1 | Wyczerpanie globalnego KV-cache powoduje head-of-line blocking i powtarzalny preemption; raportowany wzrost TTFT do ~20 000x. Dotyczy GPU serwerów, ale pokazuje zasadę: atak na scheduler, nie na model. Zweryfikowane tylko ze snippetu wyszukiwania — niezweryfikowane szczegóły liczbowe. |
| `[CONFIRMED-VULN]` | **CVE-2025-0315** (Ollama ≤ 0.3.14) — https://access.redhat.com/security/cve/CVE-2025-0315 | CWE-770, CVSS 7.5: spreparowany GGUF powoduje nieograniczoną alokację pamięci → DoS. Pokrewne: CVE-2025-0312 (null deref), CVE-2025-0317 (dzielenie przez zero w `ggufPadding`). Wniosek: nie wystawiać API zarządzania modelami (`/api/create`, `/api/pull`) przez gateway. Zweryfikowane (wyniki wyszukiwania, nie pełny NVD). |
| `[CONFIRMED-VULN]` | **CVE-2025-52999** (jackson-core < 2.15.0) — https://www.tenable.com/cve/CVE-2025-52999 | Głęboko zagnieżdżony JSON → `StackOverflowError`. Fix: `StreamReadConstraints.maxNestingDepth` (domyślnie 1000) od 2.15.0. Zapobieganie: aktualizacja + niższy limit (np. 32–64 dla MCP). Zweryfikowane. |
| `[CONFIRMED-VULN]` | **CVE-2023-44487 HTTP/2 Rapid Reset** — https://cloud.google.com/blog/products/identity-security/how-it-works-the-novel-http2-rapid-reset-ddos-attack (10.10.2023) | Szczyt 398 mln req/s; open+RST_STREAM kosztuje atakującego mało, serwer alokuje zasoby. Netty naprawił w 4.1.100.Final. Mitygacja: limit RST_STREAM/connection, GOAWAY. Zweryfikowane. |
| `[CONFIRMED-VULN]` | **CVE-2026-47244** (Netty < 4.1.135.Final / < 4.2.15.Final, opubl. 19.06.2026) — https://www.sentinelone.com/vulnerability-database/cve-2026-47244/ | Brak egzekwowania `SETTINGS_MAX_CONCURRENT_STREAMS`: jedno połączenie TCP alokuje ~2^30 obiektów stream → wyczerpanie heapu (CVSS 5.3). Netty jest pod Spring Cloud Gateway/Reactor Netty, więc sprawdzić wersję Netty w BOM. Zweryfikowane (WebFetch agregatora; nie NVD). |
| `[CONFIRMED-VULN]` | **CVE-2026-53659** (http4k-core, unbounded gzip) — https://securelayer7.net/lab/cve-2026-53659-http4k-gzip-decompression-bomb-dos | `RequestFilters.GunZip` bez limitu rozmiaru po dekompresji → małe gzip body rozwija się do GB, OOM. Fix: `SizeLimitedInputStream` domyślnie 10 MiB, odpowiedź 413. Nie dotyczy naszego stacku wprost; jest dowodem na wzorzec CWE-409. Zweryfikowane ze snippetów. |
| `[CONFIRMED-VULN]` | **CVE-2026-28435** (cpp-httplib) — https://db.gcve.eu/vuln/CVE-2026-28435 | `payload_max_length` nie był egzekwowany na rozpakowanym body → obejście limitu. Wzorzec: limit musi dotyczyć rozmiaru PO dekompresji. Zweryfikowane ze snippetu. |
| `[CONFIRMED-VULN]` | **CVE-2026-28975** (SwiftNIO extras `NIOHTTPRequestDecompressor`) — https://feedly.com/cve/CVE-2026-28975 | Limit `ratio(N)` liczony od nagłówka `Content-Length` (fałszowalnego) zamiast faktycznych bajtów → obejście ochrony przed gzip bomb. Uwaga: wyszukiwarka opisała to jako „Spring Boot related" — **nie** potwierdzono związku ze Spring; traktować jako wzorzec (nie ufać nagłówkom do liczenia limitu). Częściowo zweryfikowane. |
| `[VENDOR-CLAIM]` | **Ollama FAQ** — https://docs.ollama.com/faq | `OLLAMA_NUM_PARALLEL` domyślnie 1, `OLLAMA_MAX_QUEUE` domyślnie 512, po przepełnieniu HTTP 503, `OLLAMA_MAX_LOADED_MODELS` (3 dla CPU), `keep_alive`, domyślny kontekst 4096 tokenów. Dokumentacja producenta — wiarygodna dla konfiguracji. Zweryfikowane (WebFetch). |
| `[MITIGATION]` | **Reactor Netty HTTP server** — https://projectreactor.io/docs/netty/release/reference/http-server.html | `readTimeout`, `requestTimeout`, `idleTimeout`, `httpRequestDecoder(maxInitialLineLength=4096, maxHeaderSize=8192)`, `maxConnections`, `http2Settings`. Zweryfikowane (WebFetch). |
| `[MITIGATION]` | **Resilience4j Bulkhead** — https://resilience4j.readme.io/docs/bulkhead | `SemaphoreBulkhead` (`maxConcurrentCalls`=25, `maxWaitDuration`=0 ms) vs `ThreadPoolBulkhead` (kolejka 100). Dla WebFlux właściwy jest semafor (nie blokujemy wątków). Zweryfikowane (WebFetch). |
| `[MITIGATION]` | **Spring WebFlux codec limit** — `spring.codec.max-in-memory-size`, domyślnie 256 KB (`DataBufferLimitException`) — https://www.baeldung.com/spring-webflux-databufferlimitexception | Domyślny limit buforowania body w kodekach; uwaga raportowana w społeczności, że property nie zawsze działa dla wszystkich dekoderów — ustawiać także programowo przez `WebFluxConfigurer`. Częściowo zweryfikowane. |

Nie znaleziono (niezweryfikowane): publicznego, nazwanego incydentu produkcyjnego „slowloris na gateway LLM". Slowloris jest klasycznym, udokumentowanym atakiem
HTTP (zalecane mitygacje: timeouty nagłówków/body, minimalna przepływność — przykład Apache `mod_reqtimeout`, https://docs.cpanel.net/knowledge-base/security/how-to-mitigate-slowloris-attacks/), ale przypisanie do LLM to `[THEORETICAL]`.

## 4. Deterministic Detection

Wszystko poniżej jest deterministyczne, O(1) lub O(n) po bajtach — bez AI.

**4.1 Rozmiar body (HTTP)**
- `Content-Length` > limit → 413 przed czytaniem body (szybka ścieżka). Nie ufać jej jednak samej: body chunked nie ma CL, więc **licz bajty w locie** (`DataBuffer` counting) i przerwij po przekroczeniu.
- Limity bazowe (propozycja dla Pi): body ≤ 64 KB dla chatu, ≤ 256 KB dla MCP tool-call; dla uploadów osobny route.

**4.2 Dekompresja**
- Najprościej: **odrzucić `Content-Encoding` ≠ `identity`** dla requestów (413/415). Klienci chatu nie kompresują promptów. To eliminuje całą klasę zip bomb.
- Jeśli dopuszczone: strumieniowa dekompresja z licznikiem **rozpakowanych** bajtów (twardy cap, np. 1 MB) + limit współczynnika (ratio) liczony z faktycznie przeczytanych bajtów skompresowanych (nie z `Content-Length`, por. CVE-2026-28975). Dotyczy też zagnieżdżonych archiwów w załącznikach (zip, tar.gz): zakaz lub limit liczby wpisów, rozmiaru łącznego i głębokości.

**4.3 Prompt / kontekst**
- Liczba znaków promptu (`max_chars`), liczba wiadomości (`max_messages`), łączna długość `messages[].content`, liczba tur `role=assistant` w historii dostarczonej przez klienta (anty many-shot).
- Estymacja tokenów bez tokenizera: `ceil(chars/3)` (konserwatywnie dla PL/kodu) — wystarczy do capa; dokładny licznik tokenów tylko do budżetów.
- **Many-shot heurystyki strukturalne**: (a) liczba wystąpień znaczników ról w pojedynczej wiadomości user (`(?im)^(user|human|assistant|ai)\s*:` ≥ N, `<\|im_start\|>`, `[INST]`), (b) liczba par Q/A w jednym stringu, (c) stosunek długości historii do ostatniej wiadomości. Próg: np. > 8 sztucznych tur w polu `user` → REVIEW/BLOCK.
- Wysoka powtarzalność (kompresowalność): `gzip(prompt).length / prompt.length < 0.02` jako detektor „pad & repeat" (context stuffing, sponge); koszt O(n), tylko po przejściu limitu rozmiaru.
- Zakaz znaków kontrolnych/zero-width w nadmiarze (`\p{Cf}` > 1%) — dołożyć do canonicalization (osobny plik o Unicode).

**4.4 JSON**
- `StreamReadConstraints` w Jacksonie: `maxNestingDepth` (domyślnie 1000, ustawić 32–64), `maxStringLength` (domyślnie 20M od 2.15.1, ustawić ~256k), `maxNumberLength` (1000 → 50), `maxNameLength` (50 000 od 2.16.0 → 256), `maxDocumentLength` (od 2.16.0), `maxTokenCount` (od 2.18.0). Zweryfikowane w release notes jackson-core.
- Dodatkowo: max liczba kluczy w obiekcie, max długość tablicy, odrzucenie duplikatów kluczy (`STRICT_DUPLICATE_DETECTION`) — różnice parserów to osobny wektor.

**4.5 Parametry generacji**
- Allowlista kluczy `options` (Ollama): dopuszczamy tylko `temperature`, `top_p`, `num_predict` (≤ cap), `seed`; **`num_ctx` ustawia wyłącznie gateway**, nie klient (zmiana `num_ctx` powoduje przeładowanie modelu na Pi — koszt pamięci i czasu). Wartości > cap → clamp (nie błąd) albo BLOCK zależnie od polityki.
- Wymuszenie `stream` według polityki, `n`=1, `keep_alive` ustawiane przez gateway.

**4.6 Timeouty (sieć)**
- Czas odczytu nagłówków i body (slowloris/slow-POST): `requestTimeout`/`readTimeout` w Reactor Netty; minimalna przepływność (np. 100 B/s po 5 s) — Netty tego nie ma natywnie, robimy w filtrze (licznik bajtów/czas) lub na reverse proxy.
- `maxConnections` oraz limit połączeń per IP (licznik w filtrze/`ConcurrentHashMap` z TTL).

**4.7 Współbieżność**
- Semafor per model (`Semaphore(permits = OLLAMA_NUM_PARALLEL)`) + semafor per caller (np. 1–2) + ograniczona kolejka (długość 2–4, nie 512!) + max czas oczekiwania w kolejce. Przepełnienie → natychmiastowy 429/503 z `Retry-After`.

## 5. Detection Pipeline

Request → **[Netty: nagłówki, timeouty, maxConnections]** → **[Canonicalization: limit rozmiaru body, brak Content-Encoding, JSON parse z constraints]** → AuthN (identyfikacja callera potrzebna do limitów per caller;
limity „anonimowe" i tak działają na poziomie IP) → **Policy/Rules [LIMIT-003..LIMIT-007: długość, wiadomości, many-shot, parametry]** → **[LIMIT-008/009: bulkhead + kolejka + load shedding]**
→ LLM/MCP (Ollama na Pi; **[LIMIT-010: timeout całkowity i idle, LIMIT-011: cancel]**) → Output (limit rozmiaru odpowiedzi/tokenów, LIMIT-012) → Response.

Kolejność jest istotna: najtańsze kontrole (nagłówki, `Content-Length`, kodowanie) przed parsowaniem, parsowanie JSON z limitami przed kontrolami semantycznymi, a sidecar semantyczny
wołany **dopiero po** limitach (sidecar też ma ograniczone CPU — nie wolno go zasypać 1 MB tekstu). Slot bulkheada zajmujemy jak najpóźniej (tuż przed Ollamą) i zwalniamy w `doFinally`
(również przy cancel/error), aby nie przeciekały permity.

## 6. Possible Actions

| Sytuacja | Akcja |
|---|---|
| Body/prompt > twardy limit, Content-Encoding niedozwolony, JSON za głęboki | **BLOCK** (413 / 415 / 400) |
| `max_tokens`/`num_predict`/`n` ponad cap | clamp do limitu (**ALLOW** z adnotacją w audycie) lub **BLOCK** w trybie strict |
| Podejrzenie many-shot (strukturalne) poniżej twardego limitu | **REVIEW** (flaga do sidecara, zob. §8) lub **CHALLENGE** w trybie strict |
| Przekroczony limit współbieżności/kolejki | **RATE_LIMIT** (429 + `Retry-After`), globalny przeciążony Pi: 503 (load shedding) |
| Caller wielokrotnie przekracza limity | **QUARANTINE** (czasowa blokada klucza) |
| Inferencja > timeout całkowity lub brak tokenów > idle timeout | przerwanie generacji + częściowa odpowiedź z markerem lub 504; **BLOCK** dla wzorca runaway |
| Klient się rozłączył | anulowanie żądania do Ollamy (nie jest to „decyzja polityki", ale zdarzenie audytowane) |
| Wszystko w normie | **ALLOW** |

## 7. Bypass / Limitations

- **Limit znaków ≠ limit tokenów**: różne skrypty/języki/emoji mają inny stosunek znaków do tokenów; prompt z rzadkich znaków Unicode może mieć 3–4 tokeny na znak i ominąć cap znakowy. Mitygacja: konserwatywny przelicznik + docelowo prawdziwy tokenizer (sidecar) oraz cap po stronie Ollamy (`num_ctx` obcina kontekst).
- **Obejście przez chunking**: wiele małych żądań w limicie kumuluje koszt — potrzebne limity per caller/okno czasowe i budżety (osobny plik o rate limit/budget). Limit pojedynczego żądania nie wystarcza.
- **Many-shot poniżej progu**: kilka (nie 256) shotów + inne techniki łączy się (Anthropic: kombinacja skraca potrzebny prompt). Heurystyki strukturalne mają FN przy shotach w naturalnym tekście bez znaczników ról. FP: legalna rozmowa z długą historią, transkrypty, few-shot prompting w uczciwych celach.
- **Wykrywanie powtarzalności (gzip ratio)** ma FP dla logów, tabel, kodu z boilerplate — stosować jako sygnał, nie jedyną bramkę.
- **Slowloris poniżej progu** minimalnej przepływności; skuteczna obrona leży też w warstwie sieciowej (reverse proxy/firewall), poza Spring Cloud Gateway.
- **Kolejka w gatewayu vs kolejka w Ollamie**: jeśli ktoś ma bezpośredni dostęp do Ollamy w LAN, omija wszystkie limity — Ollama musi być osiągalna tylko z gatewaya (firewall/bind), co wymaga konfiguracji poza kodem.
- **Cancel nie zawsze zatrzymuje inferencję**: zależy od zachowania serwera (Ollama kończy generację po zamknięciu połączenia przez klienta HTTP, ale w trakcie prefill długiego promptu przerwanie może być opóźnione) — **niezweryfikowane** w naszej wersji Ollamy; test empiryczny na Pi jest obowiązkowy (zob. §14, LIMIT-T020).
- **Wydajność**: wszystkie kontrole to O(n) po bajtach, ale kodowanie/gzip-ratio na 64 KB to ułamki ms; semafory są tanie. Najdroższe jest samo parsowanie JSON (stąd kolejność: limit rozmiaru → parse).
- **Netty/HTTP2**: jeśli wystawiamy h2, wersja Netty musi zawierać poprawki CVE-2023-44487 i CVE-2026-47244; w demo prościej wyłączyć h2 (HTTP/1.1 wystarczy).

## 8. Deterministic vs AI

Deterministycznie (Java, gateway): wszystkie limity rozmiaru, głębokości, timeouty, bulkhead, kolejka, cap parametrów, cancel, heurystyki many-shot oparte na strukturze/powtarzalności.

Wymaga lub korzysta z AI (sidecar):
- **Many-shot jailbreaking jako treść** — limit długości tylko ogranicza skalę; rzeczywistą detekcję „fałszywych dialogów z szkodliwymi odpowiedziami" robi klasyfikator prompt-injection/jailbreak (Anthropic: skuteczna mitygacja to klasyfikacja/modyfikacja promptu, 61% → 2%). Gateway przekazuje do sidecara tylko żądania po limitach i może skracać wejście (np. tylko ostatnie N tur / okno fragmentów) przed klasyfikacją, żeby sidecar nie był sam wąskim gardłem.
- **Sponge / adversarial prompts** — nie da się niezawodnie przewidzieć kosztu inferencji z samego tekstu; deterministyczna obrona to twardy timeout i cap tokenów, nie detekcja.
- **Predykcja kosztu** (długość odpowiedzi) — opcjonalnie lekki model, ale cap `num_predict` + timeout wystarczą.
- Sidecar sam wymaga własnych limitów (maks. długość tekstu do klasyfikacji, timeout 1–2 s, fallback fail-closed/fail-open wg polityki — decyzja do zapisania w configu).

## 9. Implementation Options

**Java / Spring Cloud Gateway (zalecane)**
- Wbudowany filtr `RequestSize` (Spring Cloud Gateway) — strona dokumentacji nie dała się zweryfikować (404 przy próbie pobrania), więc: **niezweryfikowane**, nazwa filtra i zachowanie do sprawdzenia w używanej wersji; własny `GatewayFilterFactory` z licznikiem bajtów i tak jest potrzebny dla chunked i po dekompresji.
- Własny filtr dekompresji/odrzucenia `Content-Encoding`.
- `Resilience4j` `SemaphoreBulkhead`/`BulkheadOperator` (moduł reactor) lub prosty `Semaphore` + `Mono.defer`/`doFinally`; kolejka: `Sinks.many()` ograniczony lub `Semaphore.tryAcquire(timeout)` w trybie reaktywnym (`Mono.fromFuture`/`Schedulers.boundedElastic` niepotrzebne, jeśli używamy nieblokującego licznika, np. `AtomicInteger` + `Sinks`).
- Timeouty: `HttpServer.requestTimeout/readTimeout/idleTimeout` (Reactor Netty), `Mono.timeout(Duration)` na wywołaniu Ollamy, `Flux.timeout(first, nextTimeoutFactory)` na strumieniu tokenów (idle).
- Cancel: w WebFlux rozłączenie klienta emituje `cancel` w łańcuchu reaktywnym; jeśli `WebClient` do Ollamy jest częścią łańcucha, zamknięcie połączenia upstream następuje automatycznie — zweryfikować testem (§14).
- Jackson: `JsonFactory.builder().streamReadConstraints(...)` przekazane przez `Jackson2ObjectMapperBuilderCustomizer`.
- Limity i progi jako dane: YAML/Postgres, hot-reload (instrukcja CLAUDE.md); zmiana limitów musi działać bez restartu — semafory z dynamiczną liczbą permitów wymagają własnej implementacji (np. `Semaphore` z `reducePermits`/`release(n)`; Resilience4j pozwala na `changeConfig` w nowszych wersjach — niezweryfikowane).

**Python sidecar**: `asyncio.Semaphore`, `asyncio.wait_for`, limit `max_length` w tokenizerze (`truncation=True`), Pydantic `max_length`, Uvicorn `--limit-concurrency` i `--timeout-keep-alive`.

**Poza aplikacją**: reverse proxy (nginx/Caddy/Traefik) z `client_header_timeout`, `client_body_timeout`, `limit_conn`, `client_max_body_size` — warstwa obrony przed slowloris, jeśli demo na to pozwala.

**Ollama (strona Pi)**: `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_QUEUE` obniżyć (np. 4–8, zamiast 512), `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_KEEP_ALIVE` stały, `num_ctx` ustawiony w Modelfile (np. 2048–4096). To pasek bezpieczeństwa **za** gatewayem, nie zamiennik.

## 10. Existing Open Source

| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| Resilience4j (Bulkhead, RateLimiter, TimeLimiter) | https://github.com/resilience4j/resilience4j | Java | Apache-2.0 | Bulkhead semaforowy, time limiter, moduł reactor | Dojrzała, integracja Spring/Reactor | Dynamiczna zmiana permitów ograniczona; domyślnie brak kolejki w SemaphoreBulkhead | Niska | Tak | Wysoka |
| Reactor Netty (HttpServer timeouts, maxConnections) | https://projectreactor.io/docs/netty/release/reference/http-server.html | Java | Apache-2.0 | Timeouty i limity warstwy HTTP | Już w stacku SCG | Brak min. przepływności | Niska | Tak | Wysoka |
| Jackson `StreamReadConstraints` | https://github.com/FasterXML/jackson-core | Java | Apache-2.0 | Limity głębokości/stringów/dokumentu | Wbudowane od 2.15 | Wymaga jawnej konfiguracji (domyślne luźne) | Niska | Tak | Wysoka |
| Spring Cloud Gateway `RequestRateLimiter`/`RequestSize` | https://spring.io/projects/spring-cloud-gateway | Java | Apache-2.0 | Wbudowane filtry limitów | Gotowe | `RequestRateLimiter` domyślnie Redis; `RequestSize` niezweryfikowany | Niska–średnia | Tak (bez Redis: własny) | Średnia |
| Bucket4j | https://github.com/bucket4j/bucket4j | Java | Apache-2.0 | Token bucket w pamięci/Postgres | Brak zewn. zależności, reaktywne API | Potrzebne mapowanie na naszą politykę | Niska | Tak | Wysoka (rate limit) |
| LiteLLM proxy | https://github.com/BerriAI/litellm | Python | MIT (core; część enterprise płatna) | Gateway LLM z max_parallel_requests, budżetami, timeouts | Gotowe funkcje | Osobny stack, nie wpisuje się w SCG; funkcje enterprise płatne (niezweryfikowane które) | Średnia | Tak | Niska (inspiracja) |
| Envoy / nginx limit_conn, body size, timeouts | https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html | C | BSD-2 | Obrona L4/L7 przed slowloris | Sprawdzone | Dodatkowy komponent | Średnia | Tak | Średnia (opcjonalnie) |
| Uvicorn limits (`--limit-concurrency`) | https://www.uvicorn.org/settings/ | Python | BSD-3 | Limity sidecara | Proste | Tylko sidecar | Niska | Tak | Wysoka dla sidecara |
| Ollama (`OLLAMA_NUM_PARALLEL`, `MAX_QUEUE`) | https://docs.ollama.com/faq | Go | MIT | Limit po stronie modelu | Natywne | Domyślna kolejka 512 jest za duża dla Pi | Niska | Tak | Wysoka (config) |

## 11. Proposed Control

| ID | Kontrola | Opis | Priorytet |
|---|---|---|---|
| LIMIT-001 | Max request body bytes | Licznik bajtów w locie, 413; Content-Length jako szybka ścieżka | MUST |
| LIMIT-002 | Content-Encoding denylist / limit dekompresji | Odrzucić kodowanie na requestach lub cap po dekompresji + ratio z faktycznych bajtów | MUST |
| LIMIT-003 | Max prompt length (znaki/est. tokeny) i liczba messages | Cap konfigurowalny per model/caller | MUST |
| LIMIT-004 | JSON structural limits | Jackson `StreamReadConstraints` (depth, string, number, name, doc, tokens) | MUST |
| LIMIT-005 | Many-shot / context stuffing structural heuristics | Liczba sztucznych tur ról, gzip-ratio, historia vs ostatnia wiadomość | SHOULD |
| LIMIT-006 | Generation parameter allowlist and caps | `num_predict`, `n`, `num_ctx` ustawia gateway; clamp | MUST |
| LIMIT-007 | Slow request protection | `requestTimeout`/`readTimeout`, min. throughput, `maxConnections`, limit połączeń per IP | MUST |
| LIMIT-008 | Bulkhead per model / per caller | Semafor, ograniczona kolejka (2–4), max czas oczekiwania | MUST |
| LIMIT-009 | Load shedding | Przy głębokości kolejki/latencji p95 > próg: 503 + `Retry-After`; priorytet dla wcześniej przyjętych | SHOULD |
| LIMIT-010 | Inference timeouts (total + idle + TTFT) | `Mono.timeout`, `Flux.timeout(first,next)`; przerwanie runaway | MUST |
| LIMIT-011 | Streaming cancellation propagation | Cancel klienta/timeout → anulowanie upstream; audyt zdarzenia; zwolnienie permitu w `doFinally` | MUST |
| LIMIT-012 | Output size cap + repetition guard | Max znaków/tokenów odpowiedzi; wykrycie pętli powtórzeń (n-gram) w strumieniu → przerwanie | SHOULD |

Domyślne wartości startowe (do kalibracji na Pi): body 64 KB; prompt 8 000 znaków (~2 700 tokenów, mieści się w `num_ctx` 4096 z odpowiedzią);
messages ≤ 20; sztuczne tury ≤ 8; JSON depth 32; `num_predict` ≤ 512; semafor modelu = 1; kolejka = 3 z oczekiwaniem ≤ 20 s; per caller 1 aktywne + 2 w kolejce;
TTFT timeout 60 s (prefill na Pi bywa wolny), idle między tokenami 30 s, total 120 s; `requestTimeout` odczytu body 10 s. Wartości są propozycjami — **niezweryfikowane pomiarami na naszym Pi**.

## 12. Example Configuration

```yaml
- id: LIMIT-001
  name: Max request body size
  category: resource
  enabled: true
  priority: 5
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: body_bytes, max_bytes: 65536, count_streaming: true, on_content_length_exceeded: fast_reject }
  action: BLOCK            # HTTP 413
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/"] }

- id: LIMIT-002
  name: Reject compressed request bodies (decompression bomb)
  category: resource
  enabled: true
  priority: 6
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: header_denylist, header: Content-Encoding, deny_except: [identity], fallback_max_decompressed_bytes: 1048576, ratio_basis: actual_bytes }
  action: BLOCK            # HTTP 415
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], cwe: [CWE-409], references: ["CVE-2026-53659", "CVE-2026-28435"] }

- id: LIMIT-003
  name: Prompt and message count limits
  category: resource
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: size_limits, max_chars: 8000, max_est_tokens: 2700, max_messages: 20, token_estimator: chars_div_3 }
  action: BLOCK
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LIMIT-004
  name: JSON structural limits for requests and MCP tool-calls
  category: resource
  enabled: true
  priority: 10
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: json_constraints, max_depth: 32, max_string_length: 262144, max_number_length: 50, max_name_length: 256, max_tokens: 20000, strict_duplicate_keys: true }
  action: BLOCK            # HTTP 400
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["CVE-2025-52999"] }

- id: LIMIT-005
  name: Many-shot / context stuffing structural heuristics
  category: input
  enabled: true
  priority: 30
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher:
    type: heuristic_score
    signals:
      - { name: fake_role_turns, pattern: '(?im)^\s*(user|human|assistant|ai)\s*:', max: 8 }
      - { name: chat_template_tokens, pattern: '<\|im_start\|>|\[INST\]|<\|start_header_id\|>', max: 0, in_roles: [user] }
      - { name: gzip_ratio_below, value: 0.02 }
  action: REVIEW           # sidecar jailbreak classifier gets full text (truncated); BLOCK when fake_role_turns > 32
  severity: MEDIUM
  threshold: { review: 8, block: 32 }
  exceptions: [{ agents: ["transcript-summarizer"] }]
  metadata: { owasp: [LLM10, LLM01], references: ["https://www.anthropic.com/research/many-shot-jailbreaking"] }

- id: LIMIT-006
  name: Generation options allowlist and caps
  category: resource
  enabled: true
  priority: 25
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: options_allowlist, allow: [temperature, top_p, seed, num_predict], caps: { num_predict: 512, n: 1 }, force: { num_ctx: 4096, keep_alive: "10m" }, on_exceed: clamp }
  action: ALLOW            # clamp + audit annotation; strict mode: BLOCK
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://docs.ollama.com/faq"] }

- id: LIMIT-007
  name: Slow request / slowloris protection
  category: network
  enabled: true
  priority: 1
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: connection_limits, request_timeout_s: 10, read_timeout_s: 5, idle_timeout_s: 30, max_connections: 200, max_connections_per_ip: 10, min_bytes_per_sec: 100 }
  action: BLOCK            # close connection / 408
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://projectreactor.io/docs/netty/release/reference/http-server.html"] }

- id: LIMIT-008
  name: Model and caller bulkhead with bounded queue
  category: resource
  enabled: true
  priority: 60
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: bulkhead, per_model: { max_concurrent: 1, queue_size: 3, max_wait_s: 20 }, per_caller: { max_active: 1, queue_size: 2 } }
  action: RATE_LIMIT       # 429 + Retry-After
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://resilience4j.readme.io/docs/bulkhead"] }

- id: LIMIT-009
  name: Load shedding when Pi is overloaded
  category: resource
  enabled: true
  priority: 61
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: load_shed, signals: { queue_depth_gte: 3, upstream_p95_ms_gte: 60000, upstream_error_rate_gte: 0.5 }, response: { status: 503, retry_after_s: 30 } }
  action: RATE_LIMIT
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LIMIT-010
  name: Inference timeouts (TTFT, idle, total)
  category: resource
  enabled: true
  priority: 70
  scope: { direction: [input, output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: timeouts, ttft_s: 60, idle_between_tokens_s: 30, total_s: 120, sidecar_s: 2 }
  action: BLOCK            # abort upstream, 504 or partial response with truncation marker
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LIMIT-011
  name: Propagate streaming cancellation to Ollama
  category: resource
  enabled: true
  priority: 71
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: stream_lifecycle, on_client_disconnect: cancel_upstream, release_permit: always, audit_event: stream_cancelled }
  action: ALLOW            # lifecycle control, audited
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LIMIT-012
  name: Output size cap and repetition loop guard
  category: output
  enabled: true
  priority: 80
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: stream_guard, max_output_chars: 4000, repetition: { ngram: 8, max_repeats: 6 } }
  action: BLOCK            # truncate stream, close upstream
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://arxiv.org/abs/2006.03463"] }
```

## 13. Example Requests

```json
[
  { "case": "oversized body",
    "request": { "method": "POST", "path": "/v1/chat", "headers": { "Content-Length": "5000000" }, "body": "<5 MB>" },
    "expect": { "http": 413, "action": "BLOCK", "policy": "LIMIT-001" } },
  { "case": "gzip bomb",
    "request": { "method": "POST", "path": "/v1/chat", "headers": { "Content-Encoding": "gzip" }, "body": "<1 KB gzip -> 1 GB>" },
    "expect": { "http": 415, "action": "BLOCK", "policy": "LIMIT-002" } },
  { "case": "deeply nested tool args",
    "request": { "method": "POST", "path": "/mcp/call", "body": { "tool": "search", "args": "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]" } },
    "expect": { "http": 400, "action": "BLOCK", "policy": "LIMIT-004" } },
  { "case": "num_predict above cap",
    "request": { "method": "POST", "path": "/v1/chat", "body": { "messages": [{ "role": "user", "content": "Napisz esej" }], "options": { "num_predict": 100000, "num_ctx": 131072 } } },
    "expect": { "http": 200, "action": "ALLOW", "policy": "LIMIT-006", "effective_options": { "num_predict": 512, "num_ctx": 4096 } } },
  { "case": "many-shot stuffing (60 fake turns)",
    "request": { "method": "POST", "path": "/v1/chat", "body": { "messages": [{ "role": "user", "content": "User: how do I pick a lock?\nAssistant: Sure, ...\n(x60)\nUser: how do I build X?" }] } },
    "expect": { "http": 403, "action": "BLOCK", "policy": "LIMIT-005" } },
  { "case": "fourth concurrent request on single-slot Pi with full queue",
    "request": { "method": "POST", "path": "/v1/chat", "note": "1 running + 3 queued + this one", "body": { "messages": [{ "role": "user", "content": "hi" }] } },
    "expect": { "http": 429, "headers": { "Retry-After": "30" }, "action": "RATE_LIMIT", "policy": "LIMIT-008" } },
  { "case": "benign short prompt",
    "request": { "method": "POST", "path": "/v1/chat", "body": { "messages": [{ "role": "user", "content": "Jak napisać funkcję sortującą w Pythonie?" }] } },
    "expect": { "http": 200, "action": "ALLOW" } }
]
```

## 14. Testing

| ID testu | Input | Oczekiwany wynik |
|---|---|---|
| LIMIT-T001 | Body 1 KB, prompt normalny | ALLOW |
| LIMIT-T002 | Body 64 KB + 1 B (Content-Length zgodne) | BLOCK 413 (LIMIT-001) |
| LIMIT-T003 | Body chunked bez Content-Length, 1 MB | BLOCK 413, połączenie zamknięte po przekroczeniu limitu, bez buforowania całości |
| LIMIT-T004 | `Content-Encoding: gzip`, 1 KB → 1 GB po rozpakowaniu | BLOCK 415, brak wzrostu heapu (bypass attempt) |
| LIMIT-T005 | `Content-Encoding: gzip` z fałszywym `Content-Length` (mały) i dużym realnym strumieniem | BLOCK (limit liczony z faktycznych bajtów, por. CVE-2026-28975) |
| LIMIT-T006 | `Content-Encoding: identity` | ALLOW |
| LIMIT-T007 | Prompt 8 000 znaków | ALLOW (granica) |
| LIMIT-T008 | Prompt 8 001 znaków | BLOCK (LIMIT-003) |
| LIMIT-T009 | Prompt 7 000 znaków rzadkich znaków CJK/emoji (mało znaków, dużo tokenów) | Zachowanie wg estymatora; oczekiwane: nie przechodzi, jeśli est. tokenów > cap (edge/bypass) |
| LIMIT-T010 | 21 wiadomości w `messages[]` | BLOCK |
| LIMIT-T011 | JSON głębokość 32 | ALLOW |
| LIMIT-T012 | JSON głębokość 33 / 100 000 (`[`×100000) | BLOCK 400, brak `StackOverflowError` w logu |
| LIMIT-T013 | String 300 KB w argumencie narzędzia | BLOCK (LIMIT-004) |
| LIMIT-T014 | Liczba z 5000 cyfr | BLOCK (`maxNumberLength`) |
| LIMIT-T015 | Duplikat kluczy JSON `{"role":"user","role":"system"}` | BLOCK |
| LIMIT-T016 | `num_predict: 100000`, `num_ctx: 131072` w `options` | clamp do 512 / 4096, audyt (LIMIT-006) |
| LIMIT-T017 | Nieznana opcja `options.mirostat_tau` | usunięta lub BLOCK wg trybu |
| LIMIT-T018 | 9–60 sztucznych tur `User:/Assistant:` w jednym polu user | REVIEW (9..32) / BLOCK (>32) (LIMIT-005) |
| LIMIT-T019 | Uczciwa rozmowa, 10 prawdziwych tur w `messages[]` (nie w jednym polu) | ALLOW (negatywny; FP check) |
| LIMIT-T020 | Streaming: klient zamyka połączenie po 2 tokenach | Upstream do Ollamy anulowany ≤ 2 s (sprawdzić obciążenie CPU Pi), permit zwolniony, wpis `stream_cancelled` w audycie |
| LIMIT-T021 | Streaming: model nie wysyła tokenów 31 s | Przerwanie (idle timeout), 504/truncation marker |
| LIMIT-T022 | Prompt wymuszający wielominutową generację | Zatrzymanie po `total_s`=120 lub `num_predict` cap |
| LIMIT-T023 | Slowloris: 300 połączeń z nagłówkiem wysyłanym 1 B / 5 s | Połączenia zamknięte po `requestTimeout`; legalne żądanie nadal obsłużone |
| LIMIT-T024 | Slow POST: body 1 B/s | 408/zamknięcie po min. przepływności |
| LIMIT-T025 | 4 równoległe żądania od jednego callera (limit 1 active + 2 queue) | 3 przyjęte, czwarte 429 |
| LIMIT-T026 | 50 równoległych żądań od 50 callerów | Kolejka modelu = 3, reszta 429/503 ze `Retry-After`; gateway pozostaje responsywny (health 200 ms) |
| LIMIT-T027 | Permity po błędzie/cancel/timeout | Liczba wolnych permitów wraca do N (brak przecieku) po 100 żądaniach z losowymi cancel |
| LIMIT-T028 | Zmiana limitu w configu (hot reload) z 8000 → 100 znaków | Kolejne żądanie 200 znaków blokowane bez restartu |
| LIMIT-T029 | Odpowiedź z pętlą powtórzeń („ha ha ha…") | Strumień przerwany (LIMIT-012) |
| LIMIT-T030 | HTTP/2: 10 000 strumieni open+RST na jednym połączeniu (jeśli h2 włączone) | Połączenie zamknięte (GOAWAY); Netty ≥ 4.1.135 / 4.2.15 |
| LIMIT-T031 | Bezpośredni dostęp do `:11434` Ollamy z klienta spoza gatewaya | Odmowa połączenia (firewall) — test infrastrukturalny |
| LIMIT-T032 | Sidecar: tekst 1 MB do klasyfikatora | Gateway nie wysyła (skraca/blokuje wcześniej); timeout sidecara 2 s → zachowanie fail-closed wg configu |

## 15. Sources

- OWASP LLM10:2025 Unbounded Consumption — https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ — 2025 — `[RESEARCH]` (zweryfikowane)
- Many-Shot Jailbreaking, Anthropic — https://www.anthropic.com/research/many-shot-jailbreaking — 2.04.2024 — `[RESEARCH]` (zweryfikowane)
- Sponge Examples: Energy-Latency Attacks on Neural Networks — https://arxiv.org/abs/2006.03463 — 2020 — `[RESEARCH]` (zweryfikowane ze streszczenia)
- Fill and Squeeze (DoS na schedulery LLM) — https://arxiv.org/pdf/2511.04686v1 — 2025 — `[RESEARCH]` (częściowo zweryfikowane)
- Ollama FAQ (parallel, queue, 503, context) — https://docs.ollama.com/faq — dostęp 2026-10-03 — `[VENDOR-CLAIM]`/dokumentacja (zweryfikowane)
- CVE-2025-0315 Ollama GGUF unbounded memory — https://access.redhat.com/security/cve/CVE-2025-0315 — 2025 — `[CONFIRMED-VULN]` (zweryfikowane przez wyniki wyszukiwania)
- CVE-2025-52999 jackson-core nesting — https://www.tenable.com/cve/CVE-2025-52999 — 2025 — `[CONFIRMED-VULN]` (zweryfikowane)
- Jackson-core release notes (StreamReadConstraints) — https://github.com/FasterXML/jackson-core/blob/2.x/release-notes/VERSION-2.x — dostęp 2026-10-03 — `[MITIGATION]` (zweryfikowane)
- HTTP/2 Rapid Reset (Google Cloud) — https://cloud.google.com/blog/products/identity-security/how-it-works-the-novel-http2-rapid-reset-ddos-attack — 10.10.2023 — `[CONFIRMED-VULN]` (zweryfikowane)
- CVE-2026-47244 Netty HTTP/2 stream exhaustion — https://www.sentinelone.com/vulnerability-database/cve-2026-47244/ — 19.06.2026 — `[CONFIRMED-VULN]` (zweryfikowane przez agregator)
- CVE-2026-53659 http4k gzip bomb — https://securelayer7.net/lab/cve-2026-53659-http4k-gzip-decompression-bomb-dos — 2026 — `[CONFIRMED-VULN]` (ze snippetów)
- CVE-2026-28435 cpp-httplib — https://db.gcve.eu/vuln/CVE-2026-28435 — 2026 — `[CONFIRMED-VULN]` (ze snippetu)
- CVE-2026-28975 SwiftNIO decompressor ratio bypass — https://feedly.com/cve/CVE-2026-28975 — 2026 — `[CONFIRMED-VULN]` (częściowo; powiązanie ze Spring niepotwierdzone)
- Reactor Netty HTTP server reference — https://projectreactor.io/docs/netty/release/reference/http-server.html — dostęp 2026-10-03 — `[MITIGATION]` (zweryfikowane)
- Resilience4j Bulkhead — https://resilience4j.readme.io/docs/bulkhead — dostęp 2026-10-03 — `[MITIGATION]` (zweryfikowane)
- Spring WebFlux DataBufferLimitException / max-in-memory-size — https://www.baeldung.com/spring-webflux-databufferlimitexception — dostęp 2026-10-03 — `[MITIGATION]` (częściowo)
- Mitigating Slowloris (cPanel/Apache `mod_reqtimeout`) — https://docs.cpanel.net/knowledge-base/security/how-to-mitigate-slowloris-attacks/ — dostęp 2026-10-03 — `[MITIGATION]`
- Spring Cloud Gateway `RequestSize` filter docs — próba pobrania zwróciła 404 — **niezweryfikowane**
- Zachowanie Ollamy przy rozłączeniu klienta w trakcie prefill/generacji — **niezweryfikowane** (wymaga testu empirycznego na Pi, LIMIT-T020)
