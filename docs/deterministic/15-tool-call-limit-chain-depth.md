# Limity tool calls, głębokości łańcucha, fan-out i delegacji między agentami
> **ID:** CHAIN-001..CHAIN-008  | **Kategoria:** resource | **Priorytet:** MUST (CHAIN-001..004), SHOULD (CHAIN-005..007), NICE (CHAIN-008) | **Złożoność:** M | **Punkt egzekwowania:** tool-call / session / input (żądania z historią tool-calli)

## 1. Overview

Agent LLM działa w pętli: model -> `tool_call` -> wynik narzędzia -> model -> ... Każda iteracja kosztuje tokeny,
czas inferencji (u nas: Raspberry Pi, ~10-15 tok/s), wywołania downstream (MCP, HTTP) i potencjalnie wywołuje skutki
uboczne. Jeśli nic nie ogranicza liczby iteracji, głębokości delegacji (agent -> sub-agent -> sub-agent) ani
szerokości rozgałęzienia (fan-out), to jedna pomyłka modelu, jedno złośliwe narzędzie lub jeden prompt injection
zamienia się w pętlę nieskończoną, "fork bomb" agentów albo w nieograniczone zużycie budżetu.

Co chronimy:
- **budżet i dostępność** (tokeny, CPU/RAM Pi, kwoty API downstream) - VISION.md §4.A.11 "Budget & resource governance",
  "circuit breaker przy zbyt długiej inferencji (runaway loop)";
- **blast radius** - każde dodatkowe wywołanie narzędzia z efektami ubocznymi (e-mail, zapis, płatność) to dodatkowa
  szkoda; limit liczby wywołań ogranicza skutki *excessive agency*;
- **czytelność audytu** - łańcuch wywołań ma mieć identyfikator, głębokość i licznik, żeby dashboard mógł pokazać
  "session graph" (placeholder w `frontend/`).

Kontrola dotyczy kryterium zadania "Budget & Resource Governance" oraz agentowych aspektów MCP. Jest to kontrola
**czysto deterministyczna** (liczniki, progi, grafy), z jednym wyjątkiem opisanym w §8 (ocena, czy powtórzenia są
"sensowną pracą", czy pętlą).

## 2. Threat / Attack

Warianty zagrożenia (od najbardziej "niewinnych" do złośliwych):

1. **Przypadkowa pętla (retry storm).** Narzędzie zwraca błąd/timeout, model ponawia bez końca. Typowy objaw: te same
   `tool_name` + te same `arguments` N razy z rzędu.
2. **Pętla indukowana (malfunction amplification).** Atakujący umieszcza w treści (strona WWW, dokument, odpowiedź
   narzędzia, opis narzędzia MCP) instrukcje typu "zawsze sprawdź wynik ponownie i powtórz krok 1" - agent wpada
   w nieskończone lub bardzo długie powtarzanie akcji.
3. **Rekurencyjne planowanie / recursive tool use.** Plan -> wykonanie -> replan bez warunku stopu. Narzędzie, które samo
   wywołuje model (np. "ask_llm"), a ten znów wywołuje narzędzie.
4. **Fan-out / "fork bomb" agentów.** Agent rozbija zadanie na K pod-agentów, każdy z nich na K kolejnych: liczba agentów
   rośnie jak K^głębokość. Limit samej głębokości *bez limitu szerokości* nie wystarcza.
5. **Nieograniczona delegacja między agentami (multi-agent / A2A).** Agent A deleguje do B, B do C, C z powrotem do A
   (cykl). Brak ograniczenia głębokości i brak wykrywania cykli = kaskadowa awaria (OWASP ASI08).
6. **Równoległy burst tool-calli** w jednej odpowiedzi modelu (model zwraca 200 `tool_calls` naraz) lub w wielu
   równoległych sesjach tego samego klucza - omija limity "per request".
7. **Atak ekonomiczny (denial-of-wallet).** Złośliwy użytkownik celowo zadaje zadania wymagające długich łańcuchów, by
   spalić budżet (OWASP LLM10 Unbounded Consumption).
8. **Samopropagacja (robak).** Prompt, który każe agentowi skopiować siebie do wyjścia i przesłać dalej do kolejnych agentów
   (Morris II) - limit hop-count / głębokości propagacji ogranicza zasięg (nie zastępuje klasyfikatora).

Mechanizm krok po kroku (wariant 2+4): pośredni prompt injection w dokumencie -> agent wywołuje `fetch(url)` -> strona
zwraca instrukcję "dla każdego linku wywołaj `spawn_agent`" -> każdy pod-agent dziedziczy narzędzie `spawn_agent` ->
wykładniczy wzrost liczby agentów i tokenów -> wyczerpany budżet / zawieszony host.

## 3. Real-World Evidence

Uwaga o tagach: brief nie ma tagu "incydent"; zgłoszenia użytkowników i raporty z produkcji oznaczam
`[REAL-ATTACK]` tylko gdy to atak, a awarie nieadwersaryjne opisuję jako `[RESEARCH]`/`[CONFIRMED-VULN]` z adnotacją.
Nie znalazłem (ani nie podaję) numeru CVE dedykowanego "brak limitu tool calls" - klasa jest ujęta w OWASP jako
ryzyko projektowe, nie jako CVE.

| # | Dowód | Tag | Mechanizm / komponent / wpływ / zapobieganie |
|---|---|---|---|
| 1 | OWASP Top 10 for Agentic Applications 2026: **ASI02 Tool Misuse**, **ASI07 Insecure Inter-Agent Communication**, **ASI08 Cascading Failures** (błąd w jednym agencie propaguje się do kolejnych "szybciej niż człowiek zdąży zareagować"; zalecane: rate limits, circuit breakers, blast-radius caps). | [RESEARCH] | Klasyfikacja ryzyka. Zapobieganie: limity głębokości/szerokości, circuit breaker, cap budżetu. Źródło 1, 2. |
| 2 | OWASP Agentic AI Threats & Mitigations (taksonomia towarzysząca): **T4 Resource Overload** (loop amplification, runaway API consumption), T2 Tool Misuse, T16 Insecure Inter-Agent Protocol Abuse. Mapowanie ASI02 -> T2/T4/T16 podaje źródło wtórne (modulos/Giskard-like glosariusze); samej taksonomii T4 nie weryfikowałem w oryginale. | [RESEARCH] (mapowanie wtórne) | Źródło 3. |
| 3 | OWASP LLM06:2025 **Excessive Agency** - przyczyny: excessive functionality / permissions / autonomy. Zalecenia: minimalny zakres narzędzi, brak narzędzi otwartych, human approval dla akcji o dużym wpływie, autoryzacja downstream. | [MITIGATION] | Limit liczby wywołań jest dodatkową warstwą ograniczającą skutki (blast radius). Źródło 4. |
| 4 | OWASP LLM10:2025 **Unbounded Consumption** - nadmierne i niekontrolowane inferencje -> DoS, straty ekonomiczne. Mitigacje: rate limiting, kwoty, timeouty, throttling, logowanie i anomaly detection, sandbox. | [MITIGATION] | Źródło 5. |
| 5 | Pillar Security SAIL 5.11 "Runaway Agent / Reasoning Loop DoS": scenariusze "10 000-call retry loop", rekurencyjne spawnowanie sub-agentów wyczerpujące kwotę API. Mitigacje: capy na tool invocations, głębokość rekurencji sub-agentów, tokeny; loop detection; circuit breakery; rate limit per agent i per tenant. Mapowanie na ASI08, LLM10. Strona nie podaje datowanych incydentów. | [RESEARCH]/[MITIGATION] | Źródło 6. |
| 6 | Zhang i in., *Breaking Agents: Compromising Autonomous LLM Agents Through Malfunction Amplification* (arXiv 2407.20859, 30.07.2024; EMNLP 2025). Atak wprowadza agenta w **nieskończone pętle** lub błędne wywołania funkcji; skuteczność >80% w kilku scenariuszach; autorzy proponują detekcję przez self-examination (czyli semantyczną - słabo działa deterministycznie). | [RESEARCH] [POC] | Realny wektor "pętla indukowana". Zapobieganie: twarde capy niezależne od modelu. Źródło 7. |
| 7 | Cohen i in., *Here Comes The AI Worm* (Morris II, arXiv 2403.02817): samopowielający się prompt propaguje się przez łańcuch agentów/aplikacji GenAI (e-mail assistants, RAG); zależność od liczby "hopów". Obrona "Virtual Donkey" (TPR 1.0, FPR 0.015 wg autorów - własny pomiar). | [RESEARCH] [POC] | Limit hop-count / depth ogranicza zasięg, ale detekcja replikacji wymaga klasyfikatora. Źródło 8. |
| 8 | Anthropic Claude Code, issue "General-purpose sub-agents recursively spawn unbounded child agents" (otwarte 13.06.2026; wprowadzone w v2.1.172, nadal w v2.1.216): rekurencyjne delegowanie z limitem głębokości (5), ale **bez limitu szerokości**; zgłaszane: ~1,5 mln tokenów w sesji, 905 agentów i ~700 USD w 30 min (relacja użytkownika), 99 agentów w 5 poziomach. Pokrewne zgłoszenia mówią o 50+ poziomach głębokości i o domyślnych `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS=20` oraz `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH=3` (wartości z wyników wyszukiwania, niezweryfikowane w dokumentacji). Zgłoszenia społeczności, nie niezależnie potwierdzone przez vendora. | [CONFIRMED-VULN] (awaria projektowa, zgłoszenia użytkowników; niezweryfikowane niezależnie) | Lekcja: **limit głębokości bez limitu szerokości i bez globalnego budżetu nie chroni**; dziecko dziedziczące narzędzie "spawn" to wzorzec ryzykowny; po przekroczeniu limitu lepiej odrzucić jawnie niż ukryć narzędzie. Źródło 9. |
| 9 | Raport "400 retries in 5 minutes": zespół supportu, narzędzie do wyszukiwania zamówień zwracało timeouty, agent ponawiał 400 razy w 5 min (relacja z bloga HackerNoon, anonimowy "internal incident report", wczesny 2026). Powtarzana w internecie liczba "47 000 USD / 11 dni" **nie została przeze mnie zweryfikowana** (artykuł jej nie zawiera) - nie używać. | [RESEARCH] (anegdotyczne, niezweryfikowane) | Mitigacje z artykułu: twarde capy czasu i iteracji, stall detection ("ostatnie N akcji identyczne"), ustrukturyzowane błędy z narzędzi. Źródło 10. |
| 10 | Cursor forum: agent w pętli czytający ten sam zakres pliku dziesiątki/setki razy (Auto mode). | [CONFIRMED-VULN] (zgłoszenie użytkownika, nie sprawdzone dalej) | Ten sam wzorzec "identyczne wywołanie powtarzane". Źródło 11. |

Domyślne limity w popularnych frameworkach (to jest dowód, że branża uznaje limit za wymagany, i dostarcza wartości
referencyjne dla naszych domyślnych):

| Framework | Parametr | Domyślnie | Zachowanie po przekroczeniu | Źródło |
|---|---|---|---|---|
| LangGraph | `recursion_limit` | 25 kroków (liczba z wyników wyszukiwania dokumentacji; strona błędu sama wartości nie podaje) | `GraphRecursionError` (kod GRAPH_RECURSION_LIMIT) | 12 |
| LangChain `AgentExecutor` | `max_iterations`, `max_execution_time` | 15 iteracji | zatrzymanie / "early stopping" | 13 |
| CrewAI | `max_iter`, `max_rpm`, `max_execution_time` | `max_iter` = 25; `max_rpm` = brak (None) | agent próbuje dać najlepszą odpowiedź przy zbliżaniu się do limitu | 14 |
| OpenAI Agents SDK | `max_turns` w `Runner.run*` | nie podane w dokumentacji, którą pobrałem (nie przyjmuję wartości); można wyłączyć `max_turns=None` | wyjątek `MaxTurnsExceeded`; `error_handlers={"max_turns": ...}`; osobne timeouty modelu i narzędzi, `max_function_tool_concurrency` | 15 |
| AutoGen AgentChat | warunki terminacji: `MaxMessageTermination`, `TokenUsageTermination`, `TimeoutTermination`, `FunctionCallTermination`, `HandoffTermination` i in. (11 wbudowanych), łączone `&` / `|` | brak domyślnego limitu - trzeba skonfigurować | warunek sprawdzany **po każdej odpowiedzi agenta**, nie w środku jej generowania | 16 |
| Claude Code | max depth / max concurrent subagents | patrz wiersz 8 wyżej (niezweryfikowane) | agent po przekroczeniu "nie widzi" narzędzia spawn | 9 |

Wniosek z tabeli: limity frameworkowe żyją **w procesie agenta**, który może być zbuggowany, zmodyfikowany lub
skompromitowany. Gateway jest jedynym miejscem, w którym limit można wyegzekwować niezależnie od frameworka
(defense in depth). Jednocześnie gateway widzi tylko to, co przechodzi przez niego - patrz §7.

## 4. Deterministic Detection

Wszystko poniżej to liczniki i proste struktury danych. Klucz (scope) licznika: `chain_id` (jedno zadanie użytkownika),
`session_id`, `caller_id` (klucz API/JWT), opcjonalnie `tool_name`.

1. **Licznik tool-calli na żądanie (per request).** Liczba elementów `tool_calls` w odpowiedzi modelu / liczba wywołań
   `tools/call` MCP w batchu JSON-RPC. Próg: `max_tool_calls_per_response` (np. 8).
2. **Licznik tool-calli na łańcuch/sesję.** Inkrementacja przy każdym przejściu przez gateway; próg
   `max_tool_calls_per_chain` (np. 25, zbieżne z domyślnymi CrewAI/LangGraph) i `max_tool_calls_per_session`
   (np. 200 / 24 h).
3. **Licznik tur modelu (model turns / iteracji pętli)** na `chain_id`: liczba kolejnych wywołań `/chat/completions`,
   w których poprzednia wiadomość to wynik `tool`. Próg `max_turns_per_chain` (np. 15, jak LangChain).
4. **Głębokość łańcucha delegacji.** Nagłówki propagowane przez agentów (nasz kontrakt): `X-Chain-Id`,
   `X-Chain-Depth` (liczba całkowita), `X-Parent-Request-Id`, `X-Agent-Id`. Gateway **nie ufa** wartości od klienta:
   przy pierwszym wejściu przypisuje `chain_id` i depth = 0, a przy każdym następnym przeskoku sam zwiększa depth
   (zapisane w store po stronie serwera, klucz: `parent_request_id` -> `chain_id`/depth). Próg `max_chain_depth`
   (np. 3, hard ceiling 8).
5. **Fan-out (szerokość).** Liczba dzieci na rodzica: `children[parent_request_id]++`; próg `max_children_per_parent`
   (np. 5). Oraz **globalna liczba aktywnych agentów w łańcuchu** `max_active_agents_per_chain` (np. 20) - ponieważ sama
   szerokość i sama głębokość nie wystarczają, trzeba też całkowity budżet drzewa (liczba węzłów).
6. **Wykrywanie cykli delegacji.** Ścieżka `agent_path = [A, B, C, ...]` w store; jeśli `agent_id` już występuje w ścieżce
   (A -> B -> A) -> cykl. Opcja: dozwolone tylko jeśli polityka jawnie zezwala na dany krawędzi graf (allowlista krawędzi
   `A -> B`).
7. **Wykrywanie powtórzeń (stall / loop detection).** Hash `H = sha256(tool_name || canonical_json(arguments))`
   (kanonikalizacja: posortowane klucze, bez białych znaków, normalizacja liczb). Metryki w oknie przesuwnym (ostatnie N=10
   wywołań w łańcuchu):
   - **identyczne wywołanie powtórzone >= K razy** (K=3 dla narzędzi z efektami ubocznymi, K=5 dla read-only);
   - **cykl okresowy** (A,B,A,B,A,B): wykrywanie okresu 2-3 w sekwencji hashy;
   - **to samo narzędzie + ten sam błąd** w wynikach >= K razy.
8. **Budżet czasu ściennego łańcucha** (`max_chain_duration_s`) i **tokenów** (`max_tokens_per_chain`), sumowane z usage
   zwracanego przez Ollama (`prompt_eval_count` + `eval_count`).
9. **Współbieżność.** Semafor `max_concurrent_tool_calls_per_chain` i `max_concurrent_chains_per_caller` (limit
   równoległych sesji tego samego klucza - zamyka obejście przez wiele łańcuchów).
10. **Zasada "monotonicznie malejącego budżetu"** (rekomendacja z dyskusji o fork bombach agentów, [THEORETICAL]/praktyka):
    dziecko dostaje budżet = fragment budżetu rodzica (np. depth_left - 1, tool_calls_left / liczba dzieci), więc suma
    po drzewie nie przekroczy budżetu korzenia.

Algorytm okna powtórzeń (pseudokod):
```
window = lastN(chain.calls, 10)
if count(window, h) >= K(tool.sideEffects): action = BLOCK  (reason: identical_call_repeat)
if hasPeriod(window.hashes, p in 2..3, repeats>=3): action = BLOCK (reason: oscillation)
```

## 5. Detection Pipeline

Flow zgodny z briefem: Request -> Canonicalization -> AuthN -> Policy -> Rules -> LLM/MCP -> Output -> Response.

1. **Request** - żądanie przychodzi do gateway (chat completion z historią wiadomości `tool`, albo `tools/call` MCP).
2. **Canonicalization** - parsowanie JSON, kanonikalizacja `arguments`, obliczenie `H`; odczyt nagłówków łańcucha;
   zliczenie `tool_calls` w historii wiadomości (dodatkowe źródło prawdy: historia w ciele żądania, nie tylko nagłówki).
3. **AuthN** - ustalenie `caller_id` i `agent_id` (z klucza/JWT, nie z nagłówka). `chain_id`/depth przypisywane po stronie serwera.
4. **Policy** - pobranie limitów dla `caller_id`/agenta/narzędzia z polityki (Postgres, hot-reload); limity mogą być per rola.
5. **Rules (CHAIN-001..008)** - atomowa inkrementacja liczników w store (Caffeine/in-memory + Postgres dla trwałości;
   Redis opcjonalnie wg VISION §3) **przed** przekazaniem żądania dalej. Kolejność: najtańsze najpierw (depth, liczniki),
   potem okno powtórzeń.
6. **LLM/MCP** - dopiero po przejściu reguł wywołanie Ollamy / serwera MCP.
7. **Output** - po odpowiedzi modelu: sprawdzenie liczby `tool_calls` w odpowiedzi (CHAIN-001), rozliczenie tokenów z usage;
   nadwyżka -> przycięcie listy `tool_calls` lub BLOCK całej odpowiedzi (patrz §6).
8. **Response** - nagłówki diagnostyczne (`X-Chain-Remaining-Calls`, `X-Chain-Depth`), kod `429`/`403` z kodem powodu,
   wpis do audit logu (chain_id, reguła, licznik, próg; bez surowych argumentów jeśli zawierają PII - tylko hash `H`).

Punkt egzekwowania: `GatewayFilterFactory` o nazwie np. `ChainLimitGatewayFilterFactory`, uruchamiany *po* AuthN i
*przed* routingiem do modelu; mała część (CHAIN-001 po stronie odpowiedzi) jako filtr post.

## 6. Possible Actions

| Sytuacja | Akcja | Uzasadnienie |
|---|---|---|
| Przekroczono `max_tool_calls_per_chain` / `max_turns_per_chain` | **BLOCK** (HTTP 429/409, kod `CHAIN_LIMIT_EXCEEDED`) + zakończenie łańcucha | Twardy limit; powrót do klienta z jasnym powodem pozwala agentowi zakończyć się łagodnie (jak `MaxTurnsExceeded`) |
| Przekroczono `max_chain_depth` | **BLOCK** (403) | Delegacja głębiej nie może być "naprawiona" retry |
| Fan-out > `max_children_per_parent` | **BLOCK** nadmiarowych dzieci, pierwsze N dozwolone | Pozwala zachować część pracy |
| Cykl delegacji A->B->A | **BLOCK** + severity HIGH | Prawie zawsze błąd/atak |
| Identyczne wywołanie >= K razy | **BLOCK** wywołania; przy narzędziach read-only opcjonalnie **RATE_LIMIT** (backoff) przy K-1 | Rozróżnienie: read-only retry jest częściej uzasadnione |
| Burst (> `rate` / s) bez przekroczenia sumarycznego limitu | **RATE_LIMIT** (429 + `Retry-After`) | Chroni downstream, bez przerywania zadania |
| Próg miękki (80% limitu) | **ALLOW** + ostrzeżenie w nagłówku/audycie | Daje agentowi szansę na podsumowanie |
| Narzędzie krytyczne (płatność, usuwanie) po N-tym wywołaniu | **REVIEW** (human approval) lub **CHALLENGE** | Zgodne z zaleceniem OWASP LLM06 (human-in-the-loop) |
| Powtarzające się naruszenia przez ten sam `caller_id` | **QUARANTINE** (czasowa blokada klucza) | Sygnał skompromitowanego lub złośliwego agenta |
| Nadmiarowe `tool_calls` w odpowiedzi modelu | **REDACT** (przycięcie listy do limitu) albo BLOCK, zależnie od polityki | Przycięcie może zmienić semantykę - domyślnie BLOCK dla narzędzi z efektami ubocznymi |

Zasada: po przekroczeniu limitu zwracamy **jawny błąd z kodem**, a nie "cichą" zmianę zachowania (wniosek z incydentu 8:
ukrycie narzędzia bez wyjątku utrudnia diagnozę).

## 7. Bypass / Limitations

- **Gateway widzi tylko to, co przez niego przechodzi.** Jeśli pętla agenta odbywa się w całości w procesie klienta bez
  wołania gateway (lokalne narzędzia, wewnętrzne wywołania frameworka), nie zobaczymy jej. Zakres kontroli: wszystko, co
  woła model lub MCP przez gateway. To trzeba jasno opisać w dokumentacji i audycie.
- **Podszywanie nagłówków łańcucha.** Klient może pominąć `X-Chain-Id` i otwierać nowy łańcuch przy każdym kroku (reset
  liczników). Obrona: licznik zbiorczy per `caller_id` (CHAIN-006) + wnioskowanie łańcucha z historii wiadomości w ciele
  żądania + limit równoległych sesji; nie ufać nagłówkom.
- **Rozbicie na wiele kluczy/sesji** (Sybil). Limity per klucz nie widzą skoordynowanego ataku wielu kluczy -> globalny cap
  per tenant/IP.
- **Obejście detekcji powtórzeń przez drobną mutację argumentów** (dodanie licznika, losowego parametru, zmiana kolejności).
  Obrona częściowa: kanonikalizacja, ignorowanie pól oznaczonych jako "nonce" w polityce; pełna obrona wymaga limitu
  sumarycznego (twardy cap niezależny od treści) - dlatego sam hash nie jest główną bramką.
- **False positives:** legalne zadania wymagające wielu wywołań (przeszukiwanie 40 plików, paginacja, batch-owe operacje);
  legalne polling-i. Mitigacja: limity konfigurowalne per rola/narzędzie, `exceptions` na narzędzia idempotentne,
  próg miękki z ostrzeżeniem. LangGraph sam dokumentuje, że złożone grafy mogą naturalnie przekroczyć domyślny limit.
- **False negatives:** pętla "semantycznie powtarzalna" (różne argumenty, ten sam cel), wolna pętla poniżej progów czasu.
  Wolna, ale nieskończona pętla jest łapana tylko limitem sumarycznym (liczba wywołań/tokeny/czas na dobę).
- **Stan i wydajność:** liczniki wymagają współdzielonego stanu (przy jednej instancji gateway - in-memory/Caffeine +
  zapis w Postgres; przy wielu instancjach potrzebny Redis lub atomowe `UPDATE ... RETURNING` w Postgres). Koszt: O(1) na
  żądanie, okno N=10 to pomijalne mikrosekundy. Race conditions: bez atomowej inkrementacji równoległy burst przekroczy limit.
- **Streaming (SSE):** liczba `tool_calls` w odpowiedzi strumieniowej znana jest dopiero po zakończeniu strumienia; wymaga
  buforowania lub zliczania delt w locie. Dla wersji MVP: egzekwuj limit przy *następnym* żądaniu łańcucha.
- **Limit w pętli, którą model kontroluje** (np. model sam "zapomina" o limicie) - dlatego limit musi być po stronie gateway,
  a nie w prompcie systemowym.

## 8. Deterministic vs AI

**Deterministycznie (Java, gateway):** wszystkie liczniki, głębokość, fan-out, cykle, hash-powtórzenia, okno oscylacji,
budżety tokenów/czasu, współbieżność. To jest 95% wartości i jest odporne na adversarial input (nie da się "przekonać"
licznika).

**Czego nie da się deterministycznie:**
- odróżnić **sensowną długą pracę** od **zapętlenia semantycznego** (różne argumenty, ten sam bezproduktywny cel);
- ocenić, czy kolejny krok "przybliża do celu" (progress detection) - autorzy *Breaking Agents* proponują do tego
  self-examination (LLM-as-judge);
- wykryć **samopowielający się prompt** (Morris II) - wymaga klasyfikatora / embedding similarity do wejścia
  (sidecar, VISION §4.B.3);
- ocenić, czy decyzja o delegacji jest uzasadniona (czy sub-agent jest potrzebny).

**Sidecar (opcjonalnie, NICE):** endpoint `POST /v1/progress-check` przyjmujący skrót ostatnich N kroków i zwracający
ocenę "stagnacja/postęp"; używany tylko jako sygnał obok licznika, nigdy jako jedyna bramka (zgodnie z CLAUDE.md o
klasyfikatorach). Na Pi z małym modelem (0.5-1.5B) taka ocena będzie niepewna - dlatego deterministyczne capy zostają
zawsze nadrzędne.

## 9. Implementation Options

| Opcja | Opis | Za | Przeciw |
|---|---|---|---|
| **A. Własny `GatewayFilterFactory` (Java) + store** | Filtr czyta/aktualizuje liczniki w Caffeine (hot) i Postgres (trwałość/audyt) | Zgodne z VISION §3; pełna kontrola; brak nowych zależności; hot-reload polityk | Trzeba samemu napisać atomowość i okna |
| **B. Spring Cloud Gateway `RequestRateLimiter` + Redis** | Gotowy token bucket (`RedisRateLimiter`) z kluczem z `KeyResolver` (np. `caller_id:chain_id`) | Dojrzałe, gotowe | Redis nie jest w stacku (VISION: tylko jeśli Postgres za wolny); limituje tempo, nie głębokość/cykle/powtórzenia |
| **C. Resilience4j (RateLimiter/Bulkhead/CircuitBreaker)** | Bulkhead dla współbieżności, circuit breaker dla downstream | Biblioteka w Javie, offline | Nie zna pojęć łańcucha/agenta; tylko element układanki |
| **D. Bucket4j** | Token bucket w JVM, opcjonalnie z JDBC/Redis backendem | Czysto in-process, Apache-2.0 | Tylko rate/budżet, nie graf delegacji |
| **E. Sidecar Python** | Logika grafu i oceny postępu | Łatwiej o heurystyki | Dodatkowy hop; niepotrzebny dla liczników |

Rekomendacja: **A** (+ Bucket4j lub Resilience4j do tempa i bulkheadu). Struktura store:
`chain(chain_id, caller_id, root_request_id, started_at, tool_calls, turns, tokens, active_agents, status)`
oraz `chain_call(chain_id, seq, agent_id, parent_request_id, tool, args_hash, outcome, ts)` - to dodatkowo zasila
"session graph" w froncie i audit log. Wszystkie progi jako dane w tabeli polityk (§12), nie w kodzie.

## 10. Existing Open Source

Nie badałem kodu źródłowego tych projektów; opis oparty na dokumentacji/wynikach wyszukiwania. Licencje i zakres, które nie
zostały zweryfikowane, oznaczam "?".

| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| agentgateway | https://agentgateway.dev/docs/kubernetes/latest/mcp/rate-limit/ | Rust? | ? | Gateway MCP/LLM; lokalny token bucket i globalny rate limit per narzędzie (CEL po `body.params.name`) | Gotowy wzorzec "limit per tool"; liczy sesje, nie HTTP | Rate, nie głębokość/cykle; zależny od Envoy RL + Redis dla trybu globalnego | Średnia (osobny proces) | tak (self-hosted) | Wzorzec projektowy, nie zależność |
| Bucket4j | https://github.com/bucket4j/bucket4j | Java | Apache-2.0 (niezweryfikowane tu) | Token bucket in-process | Lekki, bez Redisa | Tylko tempo | Niska | tak | Wysoka dla CHAIN-006 |
| Resilience4j | https://github.com/resilience4j/resilience4j | Java | Apache-2.0 (niezweryfikowane tu) | Rate limiter, bulkhead, circuit breaker | Dojrzały, integracja ze Spring | Brak pojęcia łańcucha | Niska | tak | Wysoka (bulkhead, breaker) |
| Spring Cloud Gateway RequestRateLimiter | https://docs.spring.io/spring-cloud-gateway/reference/ | Java | Apache-2.0 (niezweryfikowane tu) | Gotowy filtr rate limit (Redis) | Wbudowany | Wymaga Redisa | Niska | tak | Średnia |
| LangGraph `recursion_limit` | https://docs.langchain.com/oss/python/langgraph/GRAPH_RECURSION_LIMIT | Python | MIT (niezweryfikowane tu) | Limit kroków grafu agenta | Wzorzec domyślnej wartości i błędu | Po stronie agenta, nie gateway | n/d | tak | Referencja |
| AutoGen termination conditions | https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html | Python | MIT/CC-BY (niezweryfikowane tu) | Złożone warunki stopu (`&`, `|`) | Dobry model komponowania warunków (max messages + tokens + timeout) | Po stronie agenta | n/d | tak | Referencja do DSL warunków |
| OpenAI Agents SDK | https://openai.github.io/openai-agents-python/running_agents/ | Python | MIT (niezweryfikowane tu) | `max_turns`, `error_handlers`, timeouty | Dobry wzór łagodnego kończenia | Po stronie agenta | n/d | tak (poza modelem) | Referencja |

## 11. Proposed Control

Zestaw reguł `CHAIN-*` (kategoria `resource`), wszystkie konfigurowalne i hot-reloadowalne:

| ID | Nazwa | Co robi | Domyślna akcja | Priorytet |
|---|---|---|---|---|
| CHAIN-001 | Max tool calls per response | Limit liczby `tool_calls` w jednej odpowiedzi/batchu MCP | BLOCK | MUST |
| CHAIN-002 | Max tool calls / turns per chain | Suma wywołań i tur w `chain_id` | BLOCK | MUST |
| CHAIN-003 | Max chain depth | Limit głębokości delegacji (domyślnie 3, hard 8) | BLOCK | MUST |
| CHAIN-004 | Max fan-out + active agents | Dzieci na rodzica (5) i węzły w drzewie (20) | BLOCK | MUST |
| CHAIN-005 | Delegation cycle / edge allowlist | Cykl w `agent_path` lub nieautoryzowana krawędź | BLOCK | SHOULD |
| CHAIN-006 | Per-caller session + concurrency cap | Dzienny limit wywołań i równoległych łańcuchów per klucz | RATE_LIMIT | SHOULD |
| CHAIN-007 | Repeated-call / oscillation detector | Identyczny hash wywołania >= K lub okres 2-3 w oknie N | BLOCK (read-only: RATE_LIMIT) | SHOULD |
| CHAIN-008 | Progress check (sidecar, opcjonalnie) | Ocena stagnacji semantycznej jako dodatkowy sygnał | REVIEW | NICE |

Dodatkowo: wspólna kolumna w audycie (`chain_id`, `depth`, `calls`, `threshold`, `rule_id`), pole w dashboardzie "top łańcuchy
wg liczby wywołań", oraz powiązanie z budżetem tokenów (VISION §4.A.11). Każda reguła ma własny test case YAML (§14).
Domyślne wartości (25 / 15 / 3 / 5 / 20) są propozycją opartą na domyślnych wartościach frameworków z §3 - do skalibrowania
na realnym ruchu demo.

## 12. Example Configuration

```yaml
- id: CHAIN-001
  name: Max tool calls in a single model response / MCP batch
  category: resource
  enabled: true
  priority: 40
  scope: { direction: [output, tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: counter, metric: tool_calls_in_response }
  action: BLOCK
  severity: MEDIUM
  threshold: { max: 8 }
  exceptions: []
  metadata: { owasp: [LLM06, LLM10, ASI02], references: ["https://genai.owasp.org/llmrisk/llm062025-excessive-agency/"] }

- id: CHAIN-002
  name: Max tool calls and model turns per chain
  category: resource
  enabled: true
  priority: 41
  scope: { direction: [input, tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { has_chain: true }
  matcher: { type: counter, metrics: { tool_calls: chain, turns: chain }, window: chain_lifetime }
  action: BLOCK
  severity: HIGH
  threshold: { tool_calls: 25, turns: 15, soft_warn_ratio: 0.8 }
  exceptions:
    - { agents: ["batch-indexer"], threshold: { tool_calls: 200, turns: 50 } }
  metadata: { owasp: [LLM10, ASI08], references: ["https://www.pillar.security/sail/runaway-agent-reasoning-loop-dos"] }

- id: CHAIN-003
  name: Max delegation depth
  category: resource
  enabled: true
  priority: 30
  scope: { direction: [input, tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: counter, metric: chain_depth, source: server_side }   # nigdy z nagłówka klienta
  action: BLOCK
  severity: HIGH
  threshold: { max: 3, hard_ceiling: 8 }
  exceptions: []
  metadata: { owasp: [ASI07, ASI08], references: [] }

- id: CHAIN-004
  name: Max fan-out per parent and active agents per chain
  category: resource
  enabled: true
  priority: 31
  scope: { direction: [input, tool-call], agents: ["*"], tools: ["spawn_agent", "delegate*"], environments: ["*"] }
  conditions: {}
  matcher: { type: counter, metrics: { children_per_parent: parent_request_id, active_agents: chain } }
  action: BLOCK
  severity: HIGH
  threshold: { children_per_parent: 5, active_agents: 20 }
  exceptions: []
  metadata: { owasp: [ASI08], references: ["https://claudeissues.com/issue/68110-general-purpose-sub-agents-recursively-spawn-unbounded-child-agents-causing-expo"] }

- id: CHAIN-005
  name: Delegation cycle and edge allowlist
  category: resource
  enabled: true
  priority: 32
  scope: { direction: [input, tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: graph, check: [cycle_in_agent_path, edge_in_allowlist], allowed_edges: ["orchestrator->researcher", "orchestrator->coder"] }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [ASI07, ASI08], references: [] }

- id: CHAIN-006
  name: Per-caller daily calls and concurrent chains
  category: resource
  enabled: true
  priority: 50
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: token_bucket, key: caller_id, capacity: 60, refill_per_minute: 30, max_concurrent_chains: 3, daily_tool_calls: 1000 }
  action: RATE_LIMIT
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/"] }

- id: CHAIN-007
  name: Repeated identical tool call / oscillation
  category: resource
  enabled: true
  priority: 45
  scope: { direction: [tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: sliding_window, window: 10, key: "sha256(tool+canonical_args)", ignore_arg_fields: ["request_id", "nonce"], identical_repeats: { read_only: 5, side_effects: 3 }, oscillation_period: [2, 3], oscillation_repeats: 3 }
  action: BLOCK
  severity: MEDIUM
  threshold: null
  exceptions:
    - { tools: ["poll_job_status"], identical_repeats: 20 }
  metadata: { owasp: [LLM10, ASI08], references: ["https://arxiv.org/abs/2407.20859"] }

- id: CHAIN-008
  name: Semantic progress check (optional, sidecar signal)
  category: resource
  enabled: false
  priority: 90
  scope: { direction: [tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { min_calls_in_chain: 10 }
  matcher: { type: sidecar, endpoint: "/v1/progress-check", timeout_ms: 1500, fail_mode: open }
  action: REVIEW
  severity: LOW
  threshold: { stagnation_score: 0.85 }
  exceptions: []
  metadata: { owasp: [ASI08], references: ["https://arxiv.org/abs/2407.20859"] }
```

## 13. Example Requests

Przykład 1 - przekroczony limit tool calls w łańcuchu (CHAIN-002):
```json
{
  "request": {
    "method": "POST", "path": "/v1/chat/completions",
    "headers": { "Authorization": "Bearer agent-key-7", "X-Chain-Id": "c-1042" },
    "body": { "model": "qwen2.5:1.5b", "messages": ["...", {"role": "tool", "name": "fetch", "content": "..."}] },
    "gateway_state": { "chain_id": "c-1042", "tool_calls": 25, "turns": 15 }
  },
  "expected": { "action": "BLOCK", "http": 429, "rule": "CHAIN-002", "reason": "CHAIN_LIMIT_EXCEEDED", "audit": { "threshold": 25, "observed": 25 } }
}
```

Przykład 2 - fan-out (CHAIN-004):
```json
{
  "request": { "tool": "spawn_agent", "args": { "task": "analyze doc 6" },
    "gateway_state": { "parent_request_id": "r-77", "children_of_parent": 5, "active_agents": 12 } },
  "expected": { "action": "BLOCK", "http": 403, "rule": "CHAIN-004", "reason": "FANOUT_EXCEEDED" }
}
```

Przykład 3 - cykl delegacji (CHAIN-005):
```json
{
  "request": { "tool": "delegate", "args": { "to": "orchestrator" }, "gateway_state": { "agent_path": ["orchestrator", "researcher"] } },
  "expected": { "action": "BLOCK", "rule": "CHAIN-005", "reason": "DELEGATION_CYCLE" }
}
```

Przykład 4 - powtórzone identyczne wywołanie (CHAIN-007):
```json
{
  "request": { "tool": "send_email", "args": { "to": "a@example.com", "body": "Hi" },
    "gateway_state": { "identical_in_window": 3, "side_effects": true } },
  "expected": { "action": "BLOCK", "rule": "CHAIN-007", "reason": "IDENTICAL_CALL_REPEAT" }
}
```

Przykład 5 - zwykłe żądanie (negatywny):
```json
{
  "request": { "body": { "messages": [{"role": "user", "content": "Jak napisać funkcję sortującą w Pythonie?"}] }, "gateway_state": { "tool_calls": 0, "depth": 0 } },
  "expected": { "action": "ALLOW" }
}
```

## 14. Testing

Testy jako dane YAML (VISION §6), uruchamiane przez `./run-tests.sh`. Pole `repeat` zgodne z przykładem "budget cap triggers after N requests".

| ID testu | Typ | Input / scenariusz | Oczekiwany wynik |
|---|---|---|---|
| CHAIN-T001 | positive | Model zwraca 9 `tool_calls` w jednej odpowiedzi (limit 8) | BLOCK, CHAIN-001 |
| CHAIN-T002 | negative | Model zwraca 3 `tool_calls` | ALLOW |
| CHAIN-T003 | positive | 26. wywołanie narzędzia w tym samym `chain_id` (limit 25) | BLOCK, CHAIN-002 |
| CHAIN-T004 | negative | 25 wywołań w łańcuchu | ALLOW (ostatnie z ostrzeżeniem miękkim od 20.) |
| CHAIN-T005 | positive | `repeat: 16` tur modelu (limit 15) | final: BLOCK, CHAIN-002 |
| CHAIN-T006 | positive | Delegacja na głębokość 4 (limit 3) | BLOCK, CHAIN-003 |
| CHAIN-T007 | negative | Delegacja na głębokość 3 | ALLOW |
| CHAIN-T008 | positive | 6. dziecko jednego rodzica (limit 5) | BLOCK, CHAIN-004 |
| CHAIN-T009 | positive | Drzewo agentów: 21. aktywny agent | BLOCK, CHAIN-004 |
| CHAIN-T010 | positive | Ścieżka orchestrator -> researcher -> orchestrator | BLOCK, CHAIN-005 |
| CHAIN-T011 | negative | orchestrator -> researcher -> coder (krawędzie z allowlisty) | ALLOW |
| CHAIN-T012 | positive | 3x identyczne `send_email` z tymi samymi argumentami | BLOCK, CHAIN-007 |
| CHAIN-T013 | negative | 3x `read_file` z różnymi ścieżkami | ALLOW |
| CHAIN-T014 | edge | 5x identyczne read-only `search("x")` | BLOCK/RATE_LIMIT wg konfiguracji, 4x -> ALLOW |
| CHAIN-T015 | edge | `poll_job_status` 15x (wyjątek do 20) | ALLOW |
| CHAIN-T016 | bypass | Pętla A,B,A,B,A,B (oscylacja okresu 2) | BLOCK, CHAIN-007 |
| CHAIN-T017 | bypass | Te same wywołania z dodatkowym polem `nonce` zmienianym co raz | BLOCK (nonce ignorowany po kanonikalizacji) |
| CHAIN-T018 | bypass | Te same wywołania z kolejnością kluczy JSON zmienianą | BLOCK (kanonikalizacja) |
| CHAIN-T019 | bypass | Klient pomija `X-Chain-Id` w każdym żądaniu (reset licznika) | Wnioskowanie z historii wiadomości / limit per `caller_id` -> BLOCK lub RATE_LIMIT, CHAIN-006 |
| CHAIN-T020 | bypass | Klient wysyła `X-Chain-Depth: 0` mimo realnej głębokości 5 | Wartość z nagłówka ignorowana; BLOCK, CHAIN-003 |
| CHAIN-T021 | bypass | 10 równoległych łańcuchów jednego klucza (limit 3) | RATE_LIMIT/BLOCK, CHAIN-006 |
| CHAIN-T022 | edge | Dwa równoległe żądania w tej samej milisekundzie przy liczniku = limit-1 | Dokładnie jedno ALLOW (atomowa inkrementacja) |
| CHAIN-T023 | edge | Limit zmieniony w polityce z 25 na 5 w trakcie działania | Nowy limit obowiązuje bez restartu (hot-reload) |
| CHAIN-T024 | negative | Zwykłe pytanie bez narzędzi | ALLOW |
| CHAIN-T025 | edge | Przekroczenie w odpowiedzi strumieniowej (SSE) | Egzekwowane przy następnym żądaniu łańcucha, wpis audytu `deferred` |
| CHAIN-T026 | audit | Każdy BLOCK z powyższych | Wpis audytu: rule_id, chain_id, threshold, observed; brak surowych argumentów |

## 15. Sources

Wszystkie URL-e zostały pobrane lub wyszukane w trakcie researchu (październik 2026); daty publikacji podaję tylko tam,
gdzie je zweryfikowałem.

1. OWASP Top 10 for Agentic Applications for 2026 - https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026 - data publikacji niezweryfikowana (edycja 2026) - [RESEARCH]
2. Modulos, mapowanie OWASP Agentic Top 10 (nazwy ASI01-ASI10, opis ASI07/ASI08) - https://docs.modulos.ai/frameworks/owasp-top-10-agentic - bd - [RESEARCH] (źródło wtórne)
3. Mapowanie ASI02 -> T2/T4/T16 (Tool Misuse, Resource Overload, Inter-Agent Protocol Abuse) - https://galileo.ai/blog/owasp-agentic-ai-asi02-tool-misuse oraz https://www.giskard.ai/glossary/owasp-asi02-tool-misuse-and-exploitation-nu0lr - bd - [RESEARCH] (wtórne, nie sprawdzone w oryginale taksonomii T1-T15+)
4. OWASP LLM06:2025 Excessive Agency - https://genai.owasp.org/llmrisk/llm062025-excessive-agency/ - 2025 - [MITIGATION]
5. OWASP LLM10:2025 Unbounded Consumption - https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ - 2025 - [MITIGATION]
6. Pillar Security, SAIL 5.11 Runaway Agent / Reasoning Loop DoS - https://www.pillar.security/sail/runaway-agent-reasoning-loop-dos - bd - [RESEARCH]/[MITIGATION]
7. Zhang i in., Breaking Agents: Compromising Autonomous LLM Agents Through Malfunction Amplification - https://arxiv.org/abs/2407.20859 - 30.07.2024 (EMNLP 2025) - [RESEARCH] [POC]
8. Cohen i in., Here Comes The AI Worm (Morris II) - https://arxiv.org/abs/2403.02817 - 2024 - [RESEARCH] [POC]
9. Claude Code, issue o rekurencyjnym spawnowaniu sub-agentów - https://claudeissues.com/issue/68110-general-purpose-sub-agents-recursively-spawn-unbounded-child-agents-causing-expo (otwarte 13.06.2026); powiązane: https://github.com/anthropics/claude-code/issues/68619, https://dev.to/agentiknet/an-agent-that-can-spawn-agents-is-a-fork-bomb-with-good-intentions-2kjh - 2026 - [CONFIRMED-VULN] (zgłoszenia użytkowników; agregator claudeissues.com, wartości liczbowe niezweryfikowane niezależnie)
10. HackerNoon, "Your Agent Is Not Stuck, It Is Looping" - https://hackernoon.com/your-agent-is-not-stuck-it-is-looping-there-is-a-difference-and-it-costs-you-either-way - wczesny 2026 - [RESEARCH] (anegdotyczne; liczby 47 000 USD / 11 dni z innych wyników nie zweryfikowane i nie użyte)
11. Cursor forum, agent w pętli czytającej ten sam zakres pliku - https://forum.cursor.com/t/agent-enters-infinite-loop-re-reading-the-exact-same-file-range-with-auto-mode/170975 - bd - [CONFIRMED-VULN] (zgłoszenie użytkownika; nie czytałem pełnej treści)
12. LangGraph GRAPH_RECURSION_LIMIT - https://docs.langchain.com/oss/python/langgraph/GRAPH_RECURSION_LIMIT - bd - [MITIGATION] (domyślne 25 z wyników wyszukiwania, nie ze strony błędu)
13. LangChain AgentExecutor `max_iterations` - https://reference.langchain.com/python/langchain-classic/agents/agent/AgentExecutor/max_iterations - bd - [MITIGATION] (domyślne 15 z wyników wyszukiwania)
14. CrewAI, Customizing Agents (`max_iter`=25, `max_rpm`, `max_execution_time`) - https://docs.crewai.com/en/learn/customizing-agents - bd - [MITIGATION]
15. OpenAI Agents SDK, Running agents (`max_turns`, `MaxTurnsExceeded`, timeouty, `max_function_tool_concurrency`) - https://openai.github.io/openai-agents-python/running_agents/ - bd - [MITIGATION]
16. AutoGen AgentChat, Termination Conditions - https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html - bd - [MITIGATION]
17. agentgateway, MCP rate limit (per-tool CEL descriptors, liczenie sesji) - https://agentgateway.dev/docs/kubernetes/latest/mcp/rate-limit/ - bd - [MITIGATION]
