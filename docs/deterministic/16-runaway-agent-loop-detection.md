# Wykrywanie pętli agentów (runaway agent loop) i circuit breaker
> **ID:** LOOP-001..LOOP-006  | **Kategoria:** resource / state | **Priorytet:** MUST (LOOP-001, 002, 005), SHOULD (LOOP-003, 004), NICE (LOOP-006) | **Złożoność:** M | **Punkt egzekwowania:** tool-call / session (stateful, per-sesja)

## 1. Overview
Chronimy budżet (tokeny, koszt, czas inferencji na Raspberry Pi) oraz integralność sesji przed agentem,
który wpadł w pętlę: wielokrotnie wywołuje to samo narzędzie z tymi samymi argumentami, nie robi postępu,
albo dwa agenty/narzędzia grają w ping-ponga. Pętla jest rodzajem „unbounded consumption" (OWASP LLM10:2025)
i „denial of wallet" — nawet bez atakującego. W naszym projekcie (VISION.md §4.A.11) wymóg to „circuit breaker
przy zbyt długiej inferencji (runaway loop)". Gateway widzi każde wywołanie LLM i tool-call, więc może wykryć pętlę
**niezależnie od frameworka agenta** (agent sam często nie ma limitu lub go omija).

## 2. Threat / Attack
Mechanizmy (kolejno):
1. **Identyczne powtórzenia** – agent wywołuje `read_file(path, range)` / `grep` z tymi samymi argumentami i dostaje ten sam wynik; model nie wyciąga wniosku.
2. **Pętla błędu (retry storm)** – narzędzie zwraca błąd, agent ponawia bez zmiany argumentów.
3. **Brak postępu** – wywołania różnią się kosmetycznie (inny timestamp, parafraza), ale obserwacja (wynik) jest ta sama lub kontekst rośnie bez nowych faktów.
4. **Ping-pong A↔B** – dwa agenty (np. Analyzer/Verifier) lub dwa narzędzia wzajemnie zlecają sobie pracę; wzorzec ABABAB.
5. **Monolog** – agent wypowiada „zaraz to zrobię" bez wywołania narzędzia, aż do zapełnienia kontekstu.
6. **Eksplozja kontekstu** – w każdej iteracji cały dotychczasowy kontekst jest wysyłany ponownie, więc koszt rośnie superliniowo.
7. **Atak celowy** – prompt injection w wyniku narzędzia każe agentowi „powtarzaj aż do skutku" (denial of wallet) [THEORETICAL dla naszego scenariusza; ogólnie opisane w OWASP LLM10].

## 3. Real-World Evidence
Uwaga o rzetelności: większość „rachunków" pochodzi z relacji pojedynczych autorów, nie z audytowanych raportów.

| Incydent | Tag | Szczegóły | Źródło |
|---|---|---|---|
| Claude Code #35166 – powtarzane żądania przez wiele godzin, **>$500** | [REAL-ATTACK→ raczej incydent operacyjny; zgłoszenie użytkownika] | v2.1.71, otwarte 17.03.2026, zamknięte 17.04.2026. Ok. 1 żądanie/min przez ok. 24 h bez nadzoru; 42% kontekstu zajęte przez definicje narzędzi MCP, po kompaktowaniu kontekstu agent ponawiał ten sam scenariusz. Kwota z relacji zgłaszającego. | claudeissues.com (lustro GitHub) |
| Claude Code #59318 – to samo narzędzie 30+ razy z identycznymi argumentami | [REAL-ATTACK→ zgłoszenie błędu] | v2.1.142; ten sam `grep` 30+ razy; zadanie 2–3 min → 1–2 h; zgłoszenie zamknięte jako duplikat. Sugerowana mitygacja: wykrywanie identycznych wywołań. | claudeissues.com |
| Cursor – agent w pętli `read_file` tego samego zakresu | zgłoszenie na forum | Po automatycznym streszczeniu kontekstu agent czytał ten sam zakres pliku „dziesiątki/setki razy"; brak circuit breakera; według wątku Cursor wdrożył poprawkę po stronie serwera. Daty i model w wątku wymagają ostrożności (niezweryfikowane niezależnie). | forum.cursor.com |
| „$47 000, 11 dni" – dwa agenty (Analyzer/Verifier) ping-pongują | [VENDOR-CLAIM / relacja jednego autora] | Pierwotne źródło: Teja Kusireddy (Towards AI), 2025 – **nie udało się pobrać oryginału**; zweryfikowano tylko przez wtórne omówienia (beehiiv; dev.to dał 404). Kwota i harmonogram **niezweryfikowane niezależnie**. Wniosek techniczny (brak limitu iteracji i cap-u) wiarygodny jako wzorzec. | rapidflowautomation.beehiiv.com |
| AWS Bedrock agent loop „$30 000" | [VENDOR-CLAIM / niezweryfikowane] | Tylko nagłówek z agregatora (aiweekly.co zwrócił 403) – treść niezweryfikowana. | aiweekly.co |
| Google AI Studio „Build" – nieoczekiwany rachunek z kodu z nieskończoną pętlą | zgłoszenie na forum | Widoczny tylko tytuł wątku; szczegóły niezweryfikowane. | discuss.ai.google.dev |
| Replit (lipiec 2025) | **nie jest incydentem pętli** | Agent usunął produkcyjną bazę mimo „code freeze" (dane 1206 osób / ~1196 firm); to błąd autoryzacji/destrukcyjnej akcji, nie runaway cost. Wspominamy tylko, by nie mylić kategorii. Dokładne szczegóły według mediów (Fortune, eWeek). | dc.fortune.com, eweek.com |

Dla porównania: frameworki mają wbudowane limity, co potwierdza, że problem jest powszechny — LangGraph `recursion_limit` (domyślnie 25 „supersteps", `GraphRecursionError`) [MITIGATION]; OpenHands StuckDetector (patrz §10) [MITIGATION].

Komponent/wpływ/zapobieganie: komponent = pętla agent↔narzędzie↔LLM; wpływ = koszt, zajęcie Pi (CPU-only, ~10–15 tok/s — jedna pętla blokuje model dla innych), zalane logi; zapobieganie = limity iteracji + hash-detekcja + budżet + circuit breaker w gateway.

## 4. Deterministic Detection
Stan per sesja (`session_id` / `agent_id` + `run_id`) trzymany w pamięci (Caffeine) z write-through do Postgresa/Redis:

1. **Hash wywołania**: `h = SHA-256(tool_name || canonical_json(args))`. Kanonikalizacja: sortowane klucze, trim, normalizacja ścieżek/białych znaków, usunięcie pól zmiennych (`timestamp`, `request_id`, `nonce` – lista konfigurowalna).
2. **Identyczne powtórzenia**: licznik `count(h)` w oknie przesuwnym (N wywołań lub T sekund). Próg: warn przy 3, block przy 5 (konfigurowalne; OpenHands używa 4 dla pary akcja–obserwacja, 3 dla akcja–błąd).
3. **Para (akcja, obserwacja)**: `hp = H(h || H(result_normalized))`. To samo wywołanie z tym samym wynikiem k razy = brak postępu; to samo wywołanie z *różnym* wynikiem (polling) jest dozwolone dłużej.
4. **Akcja–błąd**: ten sam `h` + status błędu k razy (próg niższy, np. 3).
5. **Ping-pong**: ostatnie 2m wpisów tworzą wzorzec ABAB…; wykrycie: `seq[i]==seq[i-2]` dla ostatnich ≥6 wpisów oraz `seq[i]!=seq[i-1]` (OpenHands: 6 cykli). Uogólnienie na cykle długości 3–4: wykrycie okresu przez sprawdzenie `seq[i]==seq[i-p]` dla p∈{2,3,4}.
6. **Brak postępu (nie-identyczne)**: zbiór unikalnych `h` w oknie W rośnie wolniej niż próg (np. <3 nowe hashe na 20 wywołań); lub rozmiar nowych informacji = 0 (hash wyniku już widziany). Opcjonalnie SimHash/MinHash argumentów do wyłapania „kosmetycznych" wariacji (koszt: FP).
7. **Monolog**: ≥3 kolejne odpowiedzi asystenta bez tool-call i bez nowego wejścia użytkownika.
8. **Twarde limity (niezależne od hashów)**: max iteracji/kroków na run (analog `recursion_limit`), max tool-calls na minutę/run, max wall-clock sesji, max tokenów na run, max głębokość delegacji agent→agent, cap kosztu (VISION §4.A.11).
9. **Wzrost kontekstu**: `input_tokens[i] / input_tokens[i-1] > r` przez k kroków bez nowych unikalnych wyników → eskalacja.
10. **Circuit breaker (stany)**: CLOSED → (przekroczenie progu) OPEN (wszystkie kolejne wywołania sesji/narzędzia odrzucane `429/409` z `Retry-After`) → po `cooldown` HALF_OPEN (jedno wywołanie próbne) → CLOSED lub OPEN. Klucz breakera: `(agent, tool)` oraz osobno `(agent, session)`.
11. **Wykrywanie wyzwalaczy kompaktowania**: pętla po zdarzeniu streszczenia kontekstu (obserwowana w Cursor i Claude Code) — licznik resetowany przy kompaktowaniu **nie może** zerować historii hashy (anty-bypass).

## 5. Detection Pipeline
Request → Canonicalization (kanon. JSON args, §4.1) → AuthN (identyfikacja agent/session) → **Policy (załaduj LOOP-reguły i progi z hot-reload)** → **Rules: LOOP-001..006 na stanie sesji** → LLM/MCP → **Output: hash wyniku, aktualizacja stanu (para akcja–obserwacja)** → Response.
- Przed wywołaniem: sprawdź stan breakera i licznik hashy (tani lookup O(1)); decyzja przed kosztowną inferencją.
- Po odpowiedzi: zapisz hash wyniku, zaktualizuj okna, tokeny, koszt.
- Zdarzenie → audit log (policy id, session, hash zredagowany, licznik, wzorzec) i dashboard „session graph".

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| Pierwszy próg (3× identyczne) | ALLOW + nagłówek/komunikat ostrzegawczy do agenta w odpowiedzi narzędzia („to wywołanie powtórzone 3×; zmień podejście") — CHALLENGE miękki; log INFO |
| Drugi próg (5× identyczne / 6 cykli ping-pong) | BLOCK wywołania (błąd tool-call z jasnym powodem) |
| Przekroczenie limitu iteracji / czasu / kosztu | BLOCK + zamknij sesję (breaker OPEN) |
| Zbyt wiele wywołań na minutę | RATE_LIMIT (token bucket per agent+tool) |
| Powtarzające się otwarcia breakera dla agenta | QUARANTINE agenta/sesji do czasu ręcznego zwolnienia |
| Wzorzec graniczny (nowość, FP-ryzyko, np. polling) | REVIEW (log + alert na dashboardzie), bez blokowania |
Odpowiedź blokująca powinna być czytelna dla agenta (kod błędu `LOOP_DETECTED`), by mógł zmienić strategię zamiast ponawiać.

## 7. Bypass / Limitations
- **Wariacje kosmetyczne**: agent zmienia jedną literę argumentu → inny hash. Mitygacja: kanonikalizacja, okno „unikalnych hashy", licznik par (akcja–obserwacja) i twarde limity niezależne od hashów.
- **Pętla długiego okresu** (cykl 10+ kroków) – wykrywanie okresu O(n·p) dla p do ~10; dłuższe łapią tylko limity iteracji/kosztu.
- **Nowy session_id przy każdym kroku**: pętla uciekająca spod liczenia. Mitygacja: klucz po `agent_id`/API key + okno czasowe, nie tylko po sesji.
- **Fałszywe alarmy**: legalny polling (status joba), ponowienia po timeoutach z backoffem, wielokrotne `read_file` tego samego pliku po edycji (wynik się zmienia — dlatego para akcja–obserwacja), batch z identycznymi argumentami. Progi muszą być per narzędzie (idempotentne/pollingowe mają wyższe).
- **Nieznany wynik** (streaming) – hash wyniku dopiero po zakończeniu.
- **Wydajność**: SHA-256 małego JSON + mapa w pamięci: mikrosekundy; pamięć ograniczona oknem (np. 200 wpisów/sesję) i TTL.
- Gateway nie widzi pętli wewnątrz agenta (rozumowanie bez tool-calli, poza ruchem przez gateway) — wtedy zostaje limit tokenów/czasu i detekcja powtórzeń w wyjściu LLM.
- Stan rozproszony: przy wielu instancjach gateway wymagany wspólny store (Redis/Postgres), inaczej licznik dzieli się na instancje.

## 8. Deterministic vs AI
Deterministycznie: identyczne powtórzenia, ping-pong, limity, okna czasowe, breaker, wzrost kontekstu, degeneracja tokenów (powtarzające się n-gramy / niski stosunek unikalnych tokenów w wyjściu).
Wymaga semantic AI (sidecar): „brak postępu" przy różnych argumentach i wynikach (parafrazy tych samych działań), ocena, czy powtarzane wywołania są sensowne (np. eksploracja vs błądzenie), wykrycie, że wynik narzędzia zawiera wstrzykniętą instrukcję „powtarzaj w nieskończoność", embedding-similarity kolejnych wywołań (próg kosinusowy) jako dodatkowy sygnał. Zasada projektu: AI tylko jako sygnał, nie jedyna bramka; twarde limity pozostają deterministyczne.

## 9. Implementation Options
- **Java/Spring Cloud Gateway**: własny `GatewayFilterFactory` `LoopGuard` + serwis `SessionLoopState` (Caffeine + opcjonalnie Redis). Circuit breaker: Resilience4j (`CircuitBreakerRegistry` z dynamicznym tworzeniem instancji per `(agent,tool)`) lub własna prosta maszyna stanów (3 stany, kilkadziesiąt linii) — własna łatwiejsza do hot-reloadu i do audytu. Spring Cloud Gateway ma wbudowany filtr `CircuitBreaker`, ale działa na awarie backendu, nie na semantykę pętli — nie wystarcza.
- Liczniki budżetu w Postgres (VISION §3); okna czasowe: sliding window log lub counter z bucketami.
- **Python sidecar**: opcjonalnie embedding-similarity ostatnich N wywołań.
- Rekomendacja: wszystko deterministyczne w Javie (tani, na ścieżce krytycznej), sidecar tylko dla LOOP-006.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| OpenHands StuckDetector | https://docs.openhands.dev/sdk/guides/agent-stuck-detector (kod: github.com/OpenHands/software-agent-sdk) | Python | MIT (core; zweryfikować dla SDK) | 5 wzorców: action-obs 4×, action-error 3×, monolog 3×, ping-pong 6 cykli, context window errors | Gotowa, sprawdzona taksonomia progów; ignoruje timestampy/ID | Działa na zdarzeniach OpenHands, nie na ruchu HTTP | Port algorytmu do Javy (niska) | tak | Wysoka jako wzorzec |
| LangGraph recursion_limit | https://docs.langchain.com/oss/javascript/langgraph/errors/GRAPH_RECURSION_LIMIT | Py/JS | MIT | Limit „supersteps" (domyślnie 25) | Prosty, znany | Tylko dla agentów LangGraph, nie zatrzymuje pętli w obrębie kroku | Brak (inny framework) | tak | Wzorzec limitu |
| Resilience4j | https://resilience4j.readme.io/ (niezweryfikowane w tej sesji) | Java | Apache-2.0 | Circuit breaker, rate limiter, bulkhead | Dojrzały, integracja ze Spring | Nie zna semantyki pętli agenta | Niska | tak | Wysoka dla breakera |
| Spring Cloud CircuitBreaker filter | https://docs.spring.io/spring-cloud-gateway/ (niezweryfikowane w tej sesji) | Java | Apache-2.0 | Fallback przy awarii backendu | Wbudowane | Nie wykrywa pętli | Niska | tak | Pomocnicza |
| pydantic-deepagents stuck-loop detection | https://cdn.jsdelivr.net/gh/vstorm-co/pydantic-deepagents@main/docs/advanced/stuck-loop-detection.md | Python | niezweryfikowana | Detekcja pętli w agencie | Przykład alternatywnej implementacji | Mały projekt | Wzorzec | tak | Niska/średnia |
| Caffeine | https://github.com/ben-manes/caffeine | Java | Apache-2.0 | Cache okien/stanów z TTL | Szybki | — | Niska | tak | Wysoka |

## 11. Proposed Control
- **LOOP-001** Identical tool-call repeat (hash(tool+canonical args)) – okno, progi warn 3 / block 5.
- **LOOP-002** Hard run limits – max kroków, tool-calls/min, wall-clock, tokeny/run; breaker OPEN.
- **LOOP-003** Repeated error / no-progress (para akcja–obserwacja; akcja–błąd 3×).
- **LOOP-004** Ping-pong / cykle A↔B (okres 2–4, ≥6 elementów) oraz limit głębokości delegacji.
- **LOOP-005** Circuit breaker per (agent,tool) i (agent,session): CLOSED/OPEN/HALF_OPEN, cooldown, QUARANTINE po N otwarciach.
- **LOOP-006** (sidecar, sygnał) Semantyczny brak postępu: embedding-similarity wywołań/wyjść; tylko REVIEW lub podniesienie severity.
Domyślne progi jako dane, edytowalne live; wyjątki per narzędzie (polling). Dodatkowo metryki do dashboardu: liczba otwarć breakera, top powtarzane narzędzia, „zaoszczędzone tokeny" (szacunek).

## 12. Example Configuration
```yaml
- id: LOOP-001
  name: Identical tool call repeated (hash tool+args)
  category: resource
  enabled: true
  priority: 40
  scope: { direction: [tool-call], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: { ignore_arg_fields: [timestamp, request_id, nonce] }
  matcher: { type: repeat_hash, key: "sha256(tool + canonical_json(args))", window: { calls: 20, seconds: 300 } }
  action: BLOCK
  severity: MEDIUM
  threshold: { warn_at: 3, block_at: 5 }
  exceptions: [ { tool: job_status, block_at: 30 } ]
  metadata: { owasp: [LLM10], references: ["https://docs.openhands.dev/sdk/guides/agent-stuck-detector"] }

- id: LOOP-002
  name: Hard run limits
  category: resource
  enabled: true
  priority: 10
  scope: { direction: [input, tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: counters, limits: { steps_per_run: 50, tool_calls_per_minute: 60, wall_clock_seconds: 900, tokens_per_run: 200000, delegation_depth: 3 } }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LOOP-003
  name: Same action + same error or same observation
  category: state
  enabled: true
  priority: 45
  scope: { direction: [tool-call], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: action_observation_repeat, observation_hash: normalized_result }
  action: BLOCK
  severity: MEDIUM
  threshold: { same_observation: 4, same_error: 3 }
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LOOP-004
  name: Ping-pong pattern A-B-A-B
  category: state
  enabled: true
  priority: 46
  scope: { direction: [tool-call], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: cycle_detect, periods: [2, 3, 4], min_elements: 6 }
  action: BLOCK
  severity: MEDIUM
  threshold: { min_cycles: 3 }
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LOOP-005
  name: Circuit breaker per agent+tool
  category: resource
  enabled: true
  priority: 5
  scope: { direction: [tool-call, input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: circuit_breaker, key: "agent+tool", open_on: [LOOP-001, LOOP-002, LOOP-003, LOOP-004], cooldown_seconds: 120, half_open_probes: 1 }
  action: BLOCK
  severity: HIGH
  threshold: { quarantine_after_opens: 3 }
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: LOOP-006
  name: Semantic no-progress signal (sidecar)
  category: state
  enabled: false
  priority: 200
  scope: { direction: [tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { min_calls: 8 }
  matcher: { type: sidecar_embedding_similarity, window: 8, cosine_above: 0.95 }
  action: REVIEW
  severity: LOW
  threshold: { signal_weight: 0.3 }
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }
```

## 13. Example Requests
```json
{ "case": "5th identical call",
  "request": { "session": "s1", "agent": "a1", "tool": "read_file", "args": {"path": "/srv/app/service.go", "range": "970-1029"} },
  "history": "4 identical calls with identical results already seen",
  "decision": { "action": "BLOCK", "rule": "LOOP-001", "code": "LOOP_DETECTED", "http": 429 } }
```
```json
{ "case": "polling with changing result",
  "request": { "session": "s2", "agent": "a1", "tool": "job_status", "args": {"id": "42"} },
  "history": "10 calls, results changed (running -> running -> done)",
  "decision": { "action": "ALLOW" } }
```
```json
{ "case": "ping-pong between agents",
  "request": { "session": "s3", "agent": "verifier", "tool": "delegate", "args": {"to": "analyzer", "task": "re-analyze"} },
  "history": "analyzer->verifier->analyzer->verifier x3",
  "decision": { "action": "BLOCK", "rule": "LOOP-004" } }
```

## 14. Testing
| ID testu | Input | Oczekiwany wynik |
|---|---|---|
| LOOP-T001 | 5× `read_file` te same args, ten sam wynik | 3. warn, 5. BLOCK (LOOP-001) |
| LOOP-T002 | 10× `read_file` ten sam plik, różne wyniki (plik edytowany) | ALLOW (negatywny) |
| LOOP-T003 | `job_status` 20× (wyjątek) | ALLOW |
| LOOP-T004 | 3× to samo wywołanie kończące się błędem 500 | BLOCK (LOOP-003) |
| LOOP-T005 | Sekwencja A,B,A,B,A,B | BLOCK (LOOP-004) |
| LOOP-T006 | A,B,C,A,B,C,A,B,C | BLOCK (okres 3) |
| LOOP-T007 | 61 wywołań/min | RATE_LIMIT/BLOCK (LOOP-002) |
| LOOP-T008 | Breaker OPEN → kolejne wywołanie | odrzucone; po cooldown próba HALF_OPEN |
| LOOP-T009 | Bypass: args różniące się tylko `timestamp` | nadal wykryte (kanonikalizacja) |
| LOOP-T010 | Bypass: zmiana jednej litery w argumencie co wywołanie | LOOP-001 nie łapie; LOOP-003 (ta sama obserwacja) lub LOOP-002 limit kroków łapie |
| LOOP-T011 | Bypass: nowy `session_id` co wywołanie, ten sam agent | wykryte po kluczu agent+okno |
| LOOP-T012 | Reset licznika po kompaktowaniu kontekstu | historia hashy zachowana, wykryte |
| LOOP-T013 | 3 kolejne odpowiedzi asystenta bez tool-call | REVIEW/warn (monolog) |
| LOOP-T014 | 3 otwarcia breakera | QUARANTINE |
| LOOP-T015 | Hot-reload progu block_at 5→3 | nowy próg działa bez restartu |

## 15. Sources
- Claude Code #35166 (mirror) — https://claudeissues.com/issue/35166-bug-claude-code-sends-repeated-requests-hundreds-of-times-without-stopping — 2026-03/04 — zgłoszenie użytkownika, kwota niezweryfikowana
- Claude Code #59318 (mirror) — https://claudeissues.com/issue/59318-agent-repeatedly-calls-the-same-tool-in-an-infinite-loop-during-exploratory-rese — zgłoszenie błędu
- Cursor forum, agent re-reading same file range — https://forum.cursor.com/t/agent-enters-infinite-loop-re-reading-the-exact-same-file-range-with-auto-mode/170975 — zgłoszenie użytkownika (szczegóły dat niezweryfikowane)
- „The $47,000 AI Agent Mistake" — https://rapidflowautomation.beehiiv.com/p/the-47-000-ai-agent-mistake-nobody-saw-coming — wtórne źródło, oryginał (Towards AI, T. Kusireddy) niepobrany — [VENDOR-CLAIM]
- AWS Bedrock $30k — https://aiweekly.co/alerts/aws-bedrock-agent-loop-costs-developer-30000 — niezweryfikowane (403)
- Google AI Studio unexpected billing — https://discuss.ai.google.dev/t/unexpected-billing-due-to-infinite-loop-code-generated-by-google-ai-studio-build/170420 — tylko tytuł, niezweryfikowane
- Replit database incident (kontekst, nie pętla) — https://dc.fortune.com/2025/07/23/ai-coding-tool-replit-wiped-database-called-it-a-catastrophic-failure — 2025-07 — relacja medialna
- OpenHands Stuck Detector — https://docs.openhands.dev/sdk/guides/agent-stuck-detector — dokumentacja — [MITIGATION]
- LangGraph GRAPH_RECURSION_LIMIT — https://docs.langchain.com/oss/javascript/langgraph/errors/GRAPH_RECURSION_LIMIT — dokumentacja — [MITIGATION]
- OWASP LLM10:2025 Unbounded Consumption — https://genai.owasp.org/llmrisk/LLM10/ — [RESEARCH]
- pydantic-deepagents stuck-loop — https://cdn.jsdelivr.net/gh/vstorm-co/pydantic-deepagents@main/docs/advanced/stuck-loop-detection.md — dokumentacja (nie czytana szczegółowo)
