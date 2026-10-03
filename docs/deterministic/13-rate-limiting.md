# Rate limiting: RPM/RPS, token bucket, per-key/model/IP, 429 + Retry-After, Denial of Wallet
> **ID:** RATE-001..RATE-007  | **Kategoria:** resource | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** input (Request→AuthN→Policy), częściowo output (rozliczenie tokenów po odpowiedzi) i session

## 1. Overview
Chronimy zasób, który jest w naszym projekcie najdroższy i najmniej skalowalny: **moc inferencyjną modelu na Raspberry Pi** (Ollama, CPU-only, ok. 10-15 tok/s wg `VISION.md` §3) oraz, w scenariuszu produkcyjnym, rachunek za API (Denial of Wallet). Rate limiting odpowiada na pytanie „ile i jak szybko może wołać dany podmiot", a nie „co wolno mu wysłać". Jest to kontrola nr 4 w `VISION.md` §4.A (token bucket per klient/model) i bezpośrednio odpowiada na OWASP LLM10:2025 Unbounded Consumption.

Specyfika LLM względem klasycznego API: koszt żądania jest **silnie zmienny** (1 żądanie może zużyć 10 albo 100 000 tokenów), więc samo RPS/RPM nie wystarcza. Potrzebne są cztery osie:
1. częstość (RPS/RPM, burst),
2. współbieżność (in-flight requests, bo Ollama domyślnie przetwarza `OLLAMA_NUM_PARALLEL=1` i kolejkuje do `OLLAMA_MAX_QUEUE=512`, po czym zwraca 503) [VENDOR-CLAIM: docs.ollama.com/faq],
3. wolumen tokenów (TPM/TPD) i budżet kosztowy (granica z kontrolą nr 11 „Budget & resource governance", opisaną osobno),
4. rozmiar pojedynczego żądania (max prompt / max_tokens; osobna kontrola nr 5, tu tylko styk).

Ten dokument obejmuje osie 1-3 jako limity „krótkookresowe"; twarde dzienne/miesięczne capy kosztowe są w case'ie budżetowym, ale współdzielą liczniki (sekcja 9).

## 2. Threat / Attack
Mechanizmy ataku, krok po kroku:

1. **Flood / brute-force**: atakujący wysyła tysiące żądań/s do `/v1/chat/completions`. Przy 1 równoległym slocie Ollamy na Pi kolejka (512) zapełnia się w sekundach, legalni użytkownicy dostają 503/timeouty (DoS).
2. **Denial of Wallet (DoW)**: przy modelu płatnym za token atakujący generuje maksymalnie długie wejścia i wyjścia (`max_tokens` rzędu maksimum), co zamienia rachunek w broń. OWASP wprost wymienia DoW, „Variable-Length Input Flood", „Continuous Input Overflow" i „Resource-Intensive Queries" [RESEARCH: genai.owasp.org LLM10:2025].
3. **Sponge examples / energy-latency**: specjalnie dobrane wejścia zwiększają zużycie energii/czasu 10-200x przy tym samym „1 żądaniu" [RESEARCH: Shumailov i in., arXiv:2006.03463]. Licznik żądań tego nie widzi, potrzebny jest limit czasu/tokenów.
4. **LLMjacking**: kradzież kluczy (wycieki repo, CVE w aplikacji) i odsprzedaż dostępu przez reverse proxy; ofiara płaci, a dodatkowo traci własny limit (quota exhaustion).
5. **Obejścia limitu** (kluczowe dla projektu kontroli):
   - **rotacja kluczy / kont** (limit per-key staje się bezwartościowy, gdy darmowa rejestracja jest tania),
   - **rotacja IP / spoofing `X-Forwarded-For`** (limit per-IP z ufanym nagłówkiem: każda wartość nagłówka = nowy bucket),
   - **omijanie klucza limitu**: warianty ścieżki (`/v1/chat/completions/` vs bez slasha, wielkość liter), inny `model` alias, HTTP/2 multiplexing, wiele połączeń,
   - **tokeny poza licznikiem**: streaming i `max_tokens`/`num_predict` ustawione wysoko, bo licznik liczył tylko żądania,
   - **race condition** (równoległe żądania przed aktualizacją licznika, „check-then-act") w niepoprawnie zaimplementowanym liczniku nieatomowym,
   - **slow-loris / długie streamy** blokujące slot współbieżności.

## 3. Real-World Evidence
| Dowód | Tag | Co pokazuje | Źródło |
|---|---|---|---|
| **OWASP LLM10:2025 Unbounded Consumption**: DoW, variable-length input flood, continuous input overflow, resource-intensive queries, model extraction; mitygacje: walidacja i limity wejścia, rate limiting i user quotas, timeouty/throttling, logowanie i wykrywanie anomalii, RBAC | [RESEARCH] / [MITIGATION] | Kanoniczne ujęcie ryzyka i wymaganych kontroli | https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ |
| **Sysdig: LLMjacking** (maj 2024): kradzież danych logowania chmury przez podatny Laravel (CVE-2021-3129), narzędzia sprawdzające klucze do 10 usług LLM; szacunek kosztu dla ofiary **ponad 46 000 USD/dzień** przy maksymalnym wykorzystaniu kwot Claude 2.x na Bedrock; atakujący mogą też zablokować ofierze legalne użycie przez wyczerpanie quoty | [REAL-ATTACK] (kwota to szacunek Sysdig, nie rzeczywisty rachunek) | DoW w praktyce i skutek uboczny: DoS przez quota exhaustion | https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/ |
| **Sysdig: LLMjacking targets DeepSeek** (2025): ponad tuzin serwerów OpenAI Reverse Proxy z kradzionymi kluczami, DeepSeek-V3 dodany w kilka dni od premiery (26.12.2024), jedna instancja z 55 kluczami DeepSeek | [REAL-ATTACK] | Odsprzedaż dostępu i szybkość adaptacji atakujących; per-key limit bez anomaly detection nie wystarcza | https://www.sysdig.com/blog/llmjacking-targets-deepseek |
| **Rachunek 82 314 USD w 48 h** po kradzieży klucza Google Gemini (zgłoszenie dewelopera z Meksyku, Gemini 3 Pro) | [REAL-ATTACK] (relacja prasowa, kwoty niezależnie niezweryfikowane) | Bez hard capa kosztowego pojedynczy klucz generuje rachunek rzędu dziesiątek tysięcy USD | https://hackmag.com/news/gemini-api-key |
| **Sponge Examples** (EuroS&P 2021): inputy zwiększające energię/latencję 10-200x na CPU/GPU/ASIC, także modele językowe | [RESEARCH] | Licznik żądań jest ślepy na koszt; potrzebne limity czasu, tokenów i wyjścia | https://arxiv.org/abs/2006.03463 |
| **CVE-2025-59152 (Litestar 2.17.0, CVSS 7.5)**: `RateLimitMiddleware` bezwarunkowo ufa `X-Forwarded-For`, więc każda wartość nagłówka tworzy osobny bucket; poprawione w 2.18.0 | [CONFIRMED-VULN] | Dokładnie ten błąd, którego musimy uniknąć w `KeyResolver` | https://corgea.com/advisories/vulnerabilities/CVE-2025-59152 |
| **CVE-2026-55501 (9router)**: obejście blokady brute-force na logowaniu przez rotację `X-Forwarded-For`; fix: czytanie nagłówka tylko od zaufanych proxy, inaczej adres gniazda | [CONFIRMED-VULN] (opis z agregatora, niezależnie niezweryfikowany w NVD) | Ten sam wzorzec i ta sama poprawka | https://securelayer7.net/lab/cve-2026-55501-9router-x-forwarded-for-rate-limit-bypass |
| **OpenAI Rate Limits**: limity RPM, RPD, TPM, TPD, IPM, „którykolwiek pierwszy"; TPM liczone jako max(`max_tokens`, estymata ze znaków wejścia); 429 z `Retry-After` jako minimum + jitter; nagłówki `x-ratelimit-*` | [VENDOR-CLAIM] (dokumentacja, wzorzec referencyjny, nie dowód skuteczności) | Wzorzec projektowy: rezerwacja tokenów z góry wg `max_tokens` | https://developers.openai.com/api/docs/guides/rate-limits |
| **LiteLLM Proxy**: `rpm_limit`, `tpm_limit`, `max_parallel_requests`, `max_budget` + `budget_duration` per key/user/team/model; budżety wymagają Postgresa; rezerwacja kosztu przed żądaniem, opcja `fail_closed_budget_enforcement` | [VENDOR-CLAIM] | Najbliższy architektonicznie odpowiednik (gateway + Postgres), dobry wzór modelu danych | https://docs.litellm.ai/docs/proxy/users |

Wniosek: **brak głośnych CVE „rate limiter w gatewayu LLM przepuścił DoS"** w naszej weryfikacji; mocne dowody to (a) klasa błędów XFF/klucz limitu [CONFIRMED-VULN], (b) incydenty finansowe LLMjacking/DoW [REAL-ATTACK] i (c) wytyczne OWASP.

## 4. Deterministic Detection
Rate limiting jest z natury w pełni deterministyczny. Techniki:

**Algorytmy**
- **Token bucket** (pojemność B, uzupełnianie r/s): pozwala na burst do B, potem stała średnia r. Domyślny wybór (SCG `RedisRateLimiter`, Bucket4j). Dobry dla LLM, bo koszt można pobierać jako N tokenów (`tryConsume(n)`).
- **Leaky bucket** (kolejka z równym odpływem): wygładza ruch; dla nas naturalny na wejściu do Ollamy, bo model i tak przetwarza 1 żądanie naraz. Wada: opóźnienie zamiast odrzucenia.
- **Fixed window**: najprostszy (`INCR` + `EXPIRE`), ale pozwala na 2x limit na granicy okna.
- **Sliding window log/counter**: dokładny, kosztowniejszy pamięciowo (log) lub aproksymowany (counter). Dobry dla twardych limitów „N na godzinę".
- **Concurrency limit** (semafor in-flight per klucz i globalnie): najważniejszy dla Pi. Wymaga zwolnienia slotu przy zakończeniu/anulowaniu/timeoucie streamu (inaczej wyciek slotów).
- **Token/cost bucket**: bucket w jednostkach tokenów. Rezerwacja przed żądaniem = `prompt_tokens_est + min(max_tokens, cap)`; po odpowiedzi korekta do realnego użycia (zwrot nadwyżki).

**Wymiary klucza** (każdy wymiar to osobny bucket, wszystkie muszą przejść): `api_key_id`, `user/agent id` (z JWT `sub`), `model` (z ciała po kanonikalizacji + allowlist), `ip` (adres gniazda lub XFF od zaufanego proxy), `tool` (MCP), `global` (ochrona Pi), `tenant/team`.

**Poprawne wyznaczanie IP**: brać `RemoteAddr` gniazda; `X-Forwarded-For`/`Forwarded` czytać wyłącznie, gdy peer jest na liście zaufanych proxy, i wtedy brać **skrajnie prawy niezaufany** wpis (idąc od prawej), nie lewy. W Spring: `XForwardedRemoteAddressResolver.maxTrustedIndex(n)` (SCG) zamiast ręcznego parsowania (niezweryfikowane w tej sesji, do sprawdzenia w dokumentacji SCG przy implementacji).

**Kanonikalizacja klucza limitu**: klucz buduje się z wartości po uwierzytelnieniu (principal), nigdy z danych sterowanych przez klienta (SCG docs wprost ostrzega, że przykład z `?user=` jest niedozwolony w produkcji [VENDOR-CLAIM]). Nazwa modelu: lowercase, usunięcie tagu aliasów, mapowanie aliasów na model kanoniczny przed pobraniem tokenów.

**Anomalie (nadal deterministyczne, progowe)**: skokowy wzrost zużycia tokenów klucza względem średniej kroczącej (np. > 5x p95 z 7 dni), użycie klucza z nowego ASN/IP, równoległe użycie jednego klucza z > K adresów w oknie, wzorzec „wiele kluczy z jednego IP/ASN" (wykrywa rotację kluczy).

## 5. Detection Pipeline
Zgodnie z Request→Canonicalization→AuthN→Policy→Rules→LLM/MCP→Output→Response:

1. **Canonicalization**: normalizacja ścieżki, nagłówków, ekstrakcja `model`, `max_tokens`/`num_predict`, `stream`; estymata `prompt_tokens` (heurystyka znaki/4 albo tokenizer lokalny).
2. **AuthN**: ustalenie `api_key_id`, `principal`, `tenant`. Żądanie bez tożsamości dostaje bucket `anonymous:<ip>` z najostrzejszymi limitami (nie „bez limitu"; SCG `deny-empty-key=true` jest tu poprawną domyślną).
3. **Pre-filter (tani, in-memory)**: globalny koszyk ochronny i limit per-IP (przed jakimkolwiek zapytaniem do Postgresa, żeby flood nie obciążał bazy).
4. **Policy: rate-limit filter** (`GatewayFilterFactory`, kolejność tuż po AuthN, przed ciężkimi regułami i klasyfikatorem sidecara, bo odrzucenie ma być najtańsze):
   a. pobierz 1 token z bucketów RPS/RPM dla [key, user, model, ip],
   b. zarezerwuj token-budget (TPM) wg estymaty,
   c. zajmij slot współbieżności (per-key i globalny dla Ollamy).
   Przy braku któregokolwiek: **429** + `Retry-After` (z `nanosToWaitForRefill`, zaokrąglone w górę do sekund) + nagłówki `RateLimit-*`/`X-RateLimit-*`; wpis do audit logu (klucz zahaszowany).
5. **Reguły/LLM**: żądanie idzie dalej; `max_tokens` jest przycinane do limitu polityki (clamp, nie tylko odrzucenie).
6. **Output**: po zakończeniu (także streamu) zliczenie realnych tokenów (z `usage`/`eval_count` Ollamy), korekta rezerwacji, zwolnienie slotu współbieżności w `doFinally` (także cancel/error/timeout).
7. **Response**: nagłówki limitu; metryki do dashboardu (`rate_limited_total` per klucz/model).

Fail-mode: przy awarii magazynu liczników (Redis/Postgres) domyślnie **fail-closed dla limitów kosztowych i globalnych, fail-open z lokalnym fallbackiem in-memory dla RPS per-key** (żeby awaria Redisa nie kładła demo; zob. `fail_closed_budget_enforcement` w LiteLLM [VENDOR-CLAIM]).

## 6. Possible Actions
| Akcja | Kiedy |
|---|---|
| ALLOW | W limicie; nagłówki informacyjne dołączone |
| RATE_LIMIT | Przekroczony RPS/RPM/TPM/współbieżność: 429 + `Retry-After`. Główna akcja tej kontroli |
| BLOCK | Przekroczony twardy dzienny/miesięczny cap kosztowy (429 lub 402/403 z kodem polityki `budget.daily_cap`) albo klucz zablokowany ręcznie |
| REDACT | Nie dotyczy |
| QUARANTINE | Wykryta anomalia (skok zużycia, rotacja kluczy z jednego IP): czasowa blokada klucza i oznaczenie do przeglądu |
| REVIEW | Klucz przekroczył 80/90% budżetu lub wykryto anomalię: alert na dashboard (bez blokady) |
| CHALLENGE | Anonimowy ruch po przekroczeniu progu: wymagaj klucza/zalogowania (HTTP 401 z `WWW-Authenticate`) zamiast cichego odrzucenia |
| (clamp) | Przycięcie `max_tokens`/`num_predict`/`num_ctx` do maksimum polityki zamiast odrzucenia (łagodniejsze, przy zachowaniu audytu) |

Format odpowiedzi 429: RFC 6585 §4 mówi, że odpowiedź SHOULD zawierać opis i MAY zawierać `Retry-After`; odpowiedzi 429 nie wolno cache'ować [RFC: rfc-editor.org/rfc/rfc6585#section-4]. Dla LLM zalecamy `Retry-After` **zawsze** i JSON w formacie zgodnym z klientami OpenAI (`error.type = "rate_limit_exceeded"`), żeby SDK same robiły backoff z jitterem (OpenAI traktuje `Retry-After` jako minimum [VENDOR-CLAIM]). Nagłówki `RateLimit`/`RateLimit-Policy` są w IETF Internet-Draft `draft-ietf-httpapi-ratelimit-headers-11` (maj 2026, nadal draft, nie RFC) [RESEARCH: datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/]; wysyłamy je dodatkowo obok powszechnych `X-RateLimit-*`.

## 7. Bypass / Limitations
| Obejście / ograniczenie | Skutek | Środek zaradczy |
|---|---|---|
| Spoofing `X-Forwarded-For` | Nieograniczona liczba bucketów per-IP [CONFIRMED-VULN, CVE-2025-59152] | Ufać XFF tylko od znanych proxy; inaczej adres gniazda; limit per-IP nigdy jako jedyny |
| Rotacja kluczy / masowa rejestracja kont | Limit per-key omijany | Limit nadrzędny per-tenant/per-IP/ASN, globalny cap, rejestracja kluczy tylko przez admina (u nas klucze wydaje operator, co zamyka ten wektor), wykrywanie wielu kluczy z jednego źródła |
| Botnet / rozproszone IP | Per-IP nieskuteczny | Limity per-tożsamość i globalny cap; CAPTCHA/PoW poza zakresem offline |
| Dzielenie żądań na wiele małych / jedno żądanie gigantyczne | RPM ślepy na koszt | Osie TPM + max prompt + clamp `max_tokens` |
| Obejście przez warianty ścieżki/modelu/aliasu | Osobny bucket per wariant | Kanonikalizacja klucza po AuthN, limit globalny |
| Długie streamy, slow-loris | Zajęty slot, 503 z Ollamy | Limit współbieżności + timeout całkowity i idle, circuit breaker (kontrola nr 11) |
| Burst tokena bucketa | Chwilowo B żądań naraz | Wiele limitów na bucket (krótki + długi), Bucket4j to wspiera [VENDOR-CLAIM] |
| Rozjazd zegarów, wiele instancji gateway | Zdublowany limit lub luka | Jeden wspólny magazyn z atomowością (Lua/`SELECT FOR UPDATE`/advisory lock) |
| Awaria magazynu liczników | Fail-open = brak limitu | Fail-closed dla kosztów, lokalny fallback |
| Race condition na liczniku | Przekroczenie limitu o równoległość | Atomowe operacje (Lua w Redisie, CAS w Bucket4j) |

False positives: wspólny NAT/uczelniane Wi-Fi na hackathonie (wielu użytkowników = jedno IP): dlatego limit per-IP wysoki, a główne limity per-tożsamość. Legitymny burst (jury odpala test suite): osobny profil dla klucza testowego albo wyższy `burst`, konfigurowalny na żywo.
False negatives: powolny, „niskiego profilu" atak z wielu tożsamości, kradzież klucza używanego w normalnym tempie (to wykrywa tylko anomaly detection + hard cap).
Wydajność: bucket in-memory to O(1) i ok. mikrosekund; Redis ok. 0,2-1 ms w LAN; Postgres 1-5 ms i obciążenie WAL przy każdym żądaniu (niezweryfikowane pomiary, wartości orientacyjne).

## 8. Deterministic vs AI
**W pełni deterministyczne**: wszystkie liczniki, buckety, semafory, kanonikalizacja klucza, 429/Retry-After, caps. AI nie powinno decydować o przepuszczeniu/odrzuceniu na ścieżce gorącej.

**Wymaga semantyki / sidecara (opcjonalnie, nie MUST)**:
- klasyfikacja „kosztowności" promptu (sponge-like: prośby o powtarzanie, bardzo długie wyliczanki, pętle w agentach) i ocena prawdopodobnego rozmiaru odpowiedzi do **rezerwacji tokenów**. Deterministyczny zamiennik: zawsze rezerwuj `min(max_tokens, cap)`,
- wykrywanie model extraction (seryjne, systematyczne zapytania pokrywające przestrzeń wejść) i anomalii zachowania klucza (klasteryzacja). Deterministyczny zamiennik: progi i heurystyki z sekcji 4,
- rozróżnienie legalnego burstu od ataku przy kradzionym kluczu.

Granica: sidecar może jedynie **podnosić severity / ustawiać flagę** (np. zmniejszyć budżet klucza), a decyzja 429 zostaje deterministyczna.

## 9. Implementation Options
| Opcja | Opis | Za | Przeciw |
|---|---|---|---|
| **A. SCG `RequestRateLimiter` + `RedisRateLimiter`** | Gotowy filtr, token bucket w Lua, `replenishRate`/`burstCapacity`/`requestedTokens`, 429 domyślnie [VENDOR-CLAIM: docs Spring] | Zero kodu dla RPS per-klucz | Wymaga Redisa (w `VISION.md` tylko opcjonalnie), brak natywnego TPM i współbieżności, konfiguracja per-route (hot-reload trudniejszy) |
| **B. SCG + `Bucket4j` jako `RateLimiter`** | Dokumentacja SCG wymienia implementację Bucket4j (lokalną lub rozproszoną) obok Redis [VENDOR-CLAIM] | Backend Postgres (JDBC, advisory locks) lub in-memory (Caffeine), tryConsume(n) dla tokenów, wiele limitów na bucket, `ConsumptionProbe` daje `nanosToWaitForRefill` do `Retry-After` | Dokładne wersje/klasy integracji do sprawdzenia przy implementacji (niezweryfikowane) |
| **C. Własny `GatewayFilterFactory` + Bucket4j core** | Filtr czyta politykę z Postgresa (hot-reload), buduje buckety dynamicznie | Pełna kontrola: wymiary key/model/ip, TPM, rezerwacja i korekta tokenów, współbieżność; spójne z modelem reguł | Trochę kodu; trzeba samemu zadbać o `doFinally` zwalniające sloty |
| **D. Resilience4j `RateLimiter` / `Bulkhead`** | `AtomicRateLimiter`, cykle (`limitForPeriod`, `limitRefreshPeriod`, `timeoutDuration`) [VENDOR-CLAIM] | `Bulkhead` dobry na współbieżność do Ollamy; integracja ze Spring Boot | RateLimiter jest in-memory (dokumentacja nie opisuje trybu rozproszonego), fixed-cycle zamiast bucketa, brak dynamicznych kluczy per-key bez własnego rejestru |
| **E. Czysty Postgres** (`UPDATE ... SET tokens = ... RETURNING`) | Liczniki w bazie, ta sama baza co budżety i audit | Jedno źródło prawdy, brak nowej zależności, trwałe cap-y dzienne | Opóźnienie i obciążenie bazy przy każdym żądaniu, hot rows |
| **F. Redis** | Atomowość (Lua), TTL, niski narzut | Standard branżowy, szybki | Dodatkowy komponent do postawienia/offline (nadal lokalnie, OK z regulaminem) |

Rekomendacja (zgodna z `VISION.md` §3 „Redis tylko jeśli Postgres za wolny"): **warstwowo**. (1) in-memory Bucket4j (Caffeine) dla gorącej ścieżki RPS/RPM/concurrency per instancja gateway (jedna instancja na hackathonie, więc spójność jest trywialna); (2) Postgres jako trwały magazyn **cap-ów TPD/koszt** (flush asynchroniczny co N s lub per odpowiedź, bo to licznik i tak liczony po `usage`); (3) Redis dopiero przy skalowaniu poziomym. Dzięki temu nie dodajemy komponentu, a przy wielu instancjach zmiana to wymiana `ProxyManager` w Bucket4j (JDBC/Lettuce).

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| Bucket4j (v8.21.0 wg README; Java 17+) | https://github.com/bucket4j/bucket4j | Java | Apache-2.0 | Token bucket; backendy: Caffeine, JCache, Hazelcast, Ignite, Infinispan, Redis (Lettuce/Redisson/Jedis), JDBC (PostgreSQL, MySQL...) | Integer arithmetic, lock-free, wiele limitów na bucket, `tryConsume(n)`, async | Brak gotowego modelu „per-model/TPM" (trzeba zbudować) | Niska | Tak | **Bardzo wysoka (wybór główny)** |
| Spring Cloud Gateway `RequestRateLimiter` | https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html | Java | Apache-2.0 | Filtr z `KeyResolver`, Redis token bucket, 429 | Już w stacku, deklaratywny | Redis wymagany dla wbudowanego limitera, ograniczona logika LLM | Bardzo niska | Tak (lokalny Redis) | Wysoka (baseline / szybki start) |
| Resilience4j (RateLimiter, Bulkhead) | https://resilience4j.readme.io/docs/ratelimiter | Java | Apache-2.0 | Limiter i bulkhead w procesie | Prosty, metryki Micrometer | In-memory, fixed cycle | Niska | Tak | Średnia (Bulkhead do współbieżności) |
| LiteLLM Proxy | https://docs.litellm.ai/docs/proxy/users | Python | MIT (core; część funkcji enterprise płatna, np. per-model budgets) | Gateway LLM z RPM/TPM/budżetami per key/user/team | Gotowy model danych i semantyka | Python, osobny proces, Postgres wymagany, funkcje enterprise płatne | Średnia (jako wzorzec, nie zależność) | Tak | Średnia (inspiracja schematu) |
| Redis + Lua (własny skrypt) | https://redis.io | C/Lua | BSD/zmiany licencyjne w nowszych wersjach (niezweryfikowane) | Atomowy limiter | Szybki | Nowy komponent | Średnia | Tak | Niska-średnia (później) |

Licencje LiteLLM i Redis: niezweryfikowane w tej sesji (podane z pamięci), do sprawdzenia przed użyciem.

## 11. Proposed Control
Rodzina reguł `RATE-*` implementowana jednym filtrem `RateLimitGatewayFilterFactory` (Bucket4j) czytającym polityki z Postgresa z hot-reloadem (np. `LISTEN/NOTIFY` lub polling co 2 s; wymiana `BucketConfiguration` bez restartu).

| ID | Reguła | Wymiar | Domyślnie (Pi) | Akcja |
|---|---|---|---|---|
| RATE-001 | Globalny ochronny limit współbieżności do Ollamy | global | 2 in-flight, kolejka max 4 | RATE_LIMIT (429 lub 503 z Retry-After) |
| RATE-002 | RPS/RPM per API key (token bucket) | api_key | 30 RPM, burst 10 | RATE_LIMIT |
| RATE-003 | TPM per API key (rezerwacja `min(max_tokens, cap)` + korekta) | api_key + model | 20 000 TPM | RATE_LIMIT |
| RATE-004 | Limit per model (droższy model = niższy limit) | model | wg allowlisty | RATE_LIMIT |
| RATE-005 | Limit per IP z bezpiecznym wyznaczaniem IP (trusted proxies) + anonymous bucket | ip | 60 RPM | RATE_LIMIT / CHALLENGE |
| RATE-006 | Clamp `max_tokens`/`num_predict`/`num_ctx` do maksimum polityki | żądanie | 1024 | clamp + audyt |
| RATE-007 | Anomalia zużycia / wiele kluczy z jednego źródła | api_key, ip | > 5x p95 7d | REVIEW / QUARANTINE |

Powiązanie z kontrolą budżetową: `budget.daily_cap` (twardy cap dzienny, test z `VISION.md` §6 „repeat: 50 → block") dzieli licznik tokenów z RATE-003. Reguły RATE-* odpowiadają za krótkie okna, budżet za doby/miesiące.

Dashboard: `rate_limited_total` per reguła/klucz, top konsumenci tokenów, % budżetu, p95 czasu oczekiwania. Audit log: `caller_id` (zahaszowany klucz), `policy=RATE-002`, `action=RATE_LIMIT`, `retry_after`.

## 12. Example Configuration
```yaml
- id: RATE-001
  name: Global concurrency guard for Ollama (Raspberry Pi)
  category: resource
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: concurrency, key: global, max_in_flight: 2, max_queue: 4, queue_timeout_ms: 3000 }
  action: RATE_LIMIT
  severity: HIGH
  threshold: { max_in_flight: 2 }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://docs.ollama.com/faq"], status: 429, retry_after: dynamic }

- id: RATE-002
  name: Per-API-key token bucket (RPM)
  category: resource
  enabled: true
  priority: 30
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: token_bucket, key: api_key_id, capacity: 10, refill: { tokens: 30, per: 60s, strategy: greedy }, cost: 1 }
  action: RATE_LIMIT
  severity: MEDIUM
  threshold: { rpm: 30, burst: 10 }
  exceptions: [ { api_key_id: "key-jury-test", override: { rpm: 300, burst: 100 } } ]
  metadata: { owasp: [LLM10], references: ["https://github.com/bucket4j/bucket4j"], headers: [Retry-After, RateLimit, RateLimit-Policy, X-RateLimit-Remaining] }

- id: RATE-003
  name: Per-key token budget per minute (reserve then reconcile)
  category: resource
  enabled: true
  priority: 40
  scope: { direction: [input, output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { stream: any }
  matcher: { type: token_bucket, key: "api_key_id|model", capacity: 20000, refill: { tokens: 20000, per: 60s }, cost: "est_prompt_tokens + min(max_tokens, 1024)", reconcile: usage_after_response }
  action: RATE_LIMIT
  severity: HIGH
  threshold: { tpm: 20000 }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://developers.openai.com/api/docs/guides/rate-limits"] }

- id: RATE-005
  name: Per-IP limit with trusted-proxy-aware client IP
  category: resource
  enabled: true
  priority: 10
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { trusted_proxies: ["10.0.0.0/8", "127.0.0.1/32"], xff_policy: rightmost_untrusted }
  matcher: { type: token_bucket, key: client_ip, capacity: 20, refill: { tokens: 60, per: 60s } }
  action: RATE_LIMIT
  severity: MEDIUM
  threshold: { rpm: 60 }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://corgea.com/advisories/vulnerabilities/CVE-2025-59152"] }

- id: RATE-006
  name: Clamp max_tokens / num_predict / num_ctx
  category: resource
  enabled: true
  priority: 35
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: field_clamp, fields: ["max_tokens", "options.num_predict", "options.num_ctx"], max: { max_tokens: 1024, num_predict: 1024, num_ctx: 4096 }, missing: set_to_max }
  action: ALLOW        # rewrite + audit, nie odrzucenie
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10] }

- id: RATE-007
  name: Usage anomaly / key-rotation detection
  category: resource
  enabled: true
  priority: 90
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: anomaly_threshold, signals: [tokens_vs_p95_7d, distinct_keys_per_ip_5m, distinct_ips_per_key_5m], thresholds: { tokens_vs_p95_7d: 5, distinct_keys_per_ip_5m: 5, distinct_ips_per_key_5m: 10 } }
  action: QUARANTINE
  severity: HIGH
  threshold: { quarantine_minutes: 30 }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/"] }
```

## 13. Example Requests
Żądanie w limicie:
```json
{ "request": { "headers": { "Authorization": "Bearer key-alice" }, "body": { "model": "qwen2.5:1.5b", "messages": [{"role":"user","content":"Cześć"}], "max_tokens": 200 } },
  "expected": { "http": 200, "action": "ALLOW", "response_headers": ["RateLimit", "X-RateLimit-Remaining"] } }
```
31. żądanie w minucie z tego samego klucza:
```json
{ "request": { "headers": { "Authorization": "Bearer key-alice" }, "repeat_in_window": 31, "window": "60s" },
  "expected": { "http": 429, "action": "RATE_LIMIT", "policy": "RATE-002", "response_headers": { "Retry-After": ">=1" } } }
```
Spoofing XFF (peer niezaufany, każde żądanie z innym XFF):
```json
{ "request": { "peer": "203.0.113.9", "headers": { "X-Forwarded-For": "1.2.3.<n>" }, "repeat": 100 },
  "expected": { "final_http": 429, "policy": "RATE-005", "note": "XFF ignored, key = 203.0.113.9" } }
```
Gigantyczne `max_tokens`:
```json
{ "request": { "body": { "model": "qwen2.5:1.5b", "messages": [{"role":"user","content":"Pisz bez końca"}], "max_tokens": 1000000 } },
  "expected": { "http": 200, "action": "ALLOW", "rewrite": { "max_tokens": 1024 }, "policy": "RATE-006" } }
```
Rotacja kluczy z jednego IP:
```json
{ "request": { "peer": "198.51.100.7", "distinct_keys": 6, "window": "5m" },
  "expected": { "action": "QUARANTINE", "policy": "RATE-007" } }
```
Anonim:
```json
{ "request": { "headers": {} }, "expected": { "http": 401, "action": "CHALLENGE" } }
```

## 14. Testing
Zgodnie z `VISION.md` §6 przypadki jako dane YAML/JSON; runner: pętla `curl` + asercja statusu, nagłówka i wpisu audit.

| ID testu | Input | Oczekiwany wynik | Typ |
|---|---|---|---|
| RATE-T001 | 1 żądanie, poprawny klucz | 200, nagłówki `RateLimit-*` | positive |
| RATE-T002 | 31 żądań/60 s jednym kluczem (limit 30) | ostatnie: 429 + `Retry-After` >= 1, policy RATE-002 | positive |
| RATE-T003 | Po odczekaniu `Retry-After` | 200 (bucket uzupełniony) | positive |
| RATE-T004 | Dwa różne klucze, jeden przekracza limit | drugi klucz nadal 200 (izolacja) | negative |
| RATE-T005 | Burst 10 równolegle (capacity 10) | wszystkie 200, 11. -> 429 | edge |
| RATE-T006 | 3 równoległe długie żądania, limit in-flight 2 | trzecie czeka lub 429/503 + Retry-After (RATE-001) | positive |
| RATE-T007 | Zerwanie połączenia w trakcie streamu x100 | sloty współbieżności zwolnione (kolejne żądanie 200), brak wycieku | edge |
| RATE-T008 | `max_tokens: 1000000` | przepisane do 1024, audit RATE-006 | positive |
| RATE-T009 | Prompt o dużej estymacie tokenów przekraczający TPM | 429 policy RATE-003 | positive |
| RATE-T010 | Po odpowiedzi krótszej niż rezerwacja | zwrot nadwyżki: kolejne żądanie mieści się w TPM | edge |
| RATE-T011 | XFF rotowany, peer niezaufany, 100 żądań | 429 po limicie IP (nagłówek zignorowany) | bypass |
| RATE-T012 | XFF od zaufanego proxy (peer w trusted list) z dwoma klientami | osobne buckety per skrajnie prawy niezaufany wpis | negative |
| RATE-T013 | XFF z fałszywym lewym wpisem + prawdziwy prawy | klucz = prawy wpis, nie lewy | bypass |
| RATE-T014 | Ten sam klucz, ścieżka `/v1/chat/completions` i `/v1/chat/completions/` | wspólny bucket | bypass |
| RATE-T015 | Alias modelu `Qwen2.5:1.5B` vs `qwen2.5:1.5b` | wspólny bucket modelu | bypass |
| RATE-T016 | 6 różnych kluczy z jednego IP w 5 min | QUARANTINE, RATE-007 | bypass |
| RATE-T017 | Żądanie bez uwierzytelnienia | 401 CHALLENGE lub bucket `anonymous` ostry | negative |
| RATE-T018 | Zmiana `rpm` w polityce w trakcie działania | nowy limit obowiązuje bez restartu (<= 5 s) | hot-reload |
| RATE-T019 | Awaria magazynu liczników (symulowana) | limity kosztowe fail-closed, RPS lokalny fallback | edge |
| RATE-T020 | 50 równoległych żądań (race) na limicie 30 | dokładnie <= 30 przepuszczonych | bypass |
| RATE-T021 | Klucz testowy jury z override | wyższy limit, brak FP | negative |
| RATE-T022 | Żądanie bez `Retry-After` w odpowiedzi 429 | test FAIL (wymóg kontraktu) | contract |
| RATE-T023 | `budget.daily_cap` po N żądaniach (`repeat: 50`) | końcowo block, policy `budget.daily_cap` | positive |

## 15. Sources
- OWASP Top 10 for LLM Applications 2025, LLM10 Unbounded Consumption — https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ — 2025 (pobrane 2026-10-03) — [RESEARCH]/[MITIGATION]
- Sysdig, LLMjacking: Stolen Cloud Credentials Used in New AI Attack — https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/ — maj 2024 — [REAL-ATTACK]
- Sysdig, LLMjacking targets DeepSeek — https://www.sysdig.com/blog/llmjacking-targets-deepseek — 2025 — [REAL-ATTACK]
- HackMag, Stolen API Key Costs Developer $82,000 in Gemini Usage — https://hackmag.com/news/gemini-api-key — brak daty w pobranym wyniku — [REAL-ATTACK] (relacja, kwoty niezweryfikowane niezależnie)
- Shumailov i in., Sponge Examples: Energy-Latency Attacks on Neural Networks — https://arxiv.org/abs/2006.03463 — 2020, rev. 2021 (EuroS&P) — [RESEARCH]
- CVE-2025-59152, Litestar X-Forwarded-For rate limit bypass — https://corgea.com/advisories/vulnerabilities/CVE-2025-59152 — 2025 — [CONFIRMED-VULN]
- CVE-2026-55501, 9router rate-limit bypass via XFF — https://securelayer7.net/lab/cve-2026-55501-9router-x-forwarded-for-rate-limit-bypass — 2026 — [CONFIRMED-VULN] (źródło wtórne)
- RFC 6585 §4, 429 Too Many Requests — https://www.rfc-editor.org/rfc/rfc6585#section-4 — 2012 — standard
- IETF draft-ietf-httpapi-ratelimit-headers-11 — https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/ — 2026-05-23 — draft, nie RFC
- Spring Cloud Gateway, RequestRateLimiter GatewayFilter Factory — https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html — bieżąca — [VENDOR-CLAIM]
- Bucket4j — https://github.com/bucket4j/bucket4j oraz https://bucket4j.com/8.10.1/toc.html — bieżące — [VENDOR-CLAIM]
- Resilience4j RateLimiter — https://resilience4j.readme.io/docs/ratelimiter — bieżąca — [VENDOR-CLAIM]
- Ollama FAQ (OLLAMA_NUM_PARALLEL, OLLAMA_MAX_QUEUE=512, 503) — https://docs.ollama.com/faq — bieżąca — [VENDOR-CLAIM]
- OpenAI, Rate limits — https://developers.openai.com/api/docs/guides/rate-limits — bieżąca — [VENDOR-CLAIM]
- LiteLLM Proxy, budgets and rate limits — https://docs.litellm.ai/docs/proxy/users — bieżąca — [VENDOR-CLAIM]
- Niezweryfikowane: dokładne klasy integracji Bucket4j z SCG, API `XForwardedRemoteAddressResolver`, licencje LiteLLM/Redis, opóźnienia Redis/Postgres.
