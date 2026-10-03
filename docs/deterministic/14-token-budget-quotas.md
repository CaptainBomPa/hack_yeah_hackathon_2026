# Limity tokenów i budżety (token budget, quota, cost cap)
> **ID:** BUDGET-001..BUDGET-008  | **Kategoria:** resource | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** input (pre-check + rezerwacja), output (strumień/limit), session (liczniki w Postgres)

## 1. Overview
Chronimy zasób, który w naszym demo jest „pieniądzem": czas inferencji i tokeny modelu na Raspberry Pi (Ollama, ok. 10-15 tok/s wg VISION.md §3), a w wariancie produkcyjnym realny rachunek za API. Kontrola `Budget & resource governance` (VISION.md §4.A.4, §4.A.11) obejmuje:
- limity **per request**: max tokenów wejścia, `max_tokens`/`num_predict` na wyjściu, max czas inferencji,
- budżety **okresowe** (dzienny/miesięczny) w tokenach i w jednostkach kosztu, per agent / per model / per użytkownik / globalnie,
- **hard cap** (BLOCK po przekroczeniu) vs **soft cap** (alert/REVIEW przy np. 80%),
- liczenie tokenów **deterministyczne** i księgowanie kosztu,
- **atomowość** liczników w Postgres (brak wyścigów przy współbieżnych żądaniach),
- circuit breaker na runaway generation.

Kryterium „Budget & resource governance" z zadania jest jawnie wymieniane w VISION.md §6 (test `budget cap triggers after N requests`, oczekiwana polityka `budget.daily_cap`).

## 2. Threat / Attack
Rodzina: **OWASP LLM10:2025 Unbounded Consumption** (w tym *Denial of Wallet*, *Variable-Length Input Flood*, *Continuous Input Overflow*, *Resource-Intensive Queries*).

Mechanizmy krok po kroku:
1. **Denial of Wallet / Denial of Service przez długi output** - atakujący (lub zbuntowany agent) wysyła krótki prompt „napisz 50 000 słów…" bez `max_tokens`; koszt/czas = O(output). Na Pi jeden taki request blokuje model na minuty (Ollama domyślnie obsługuje mało równoległych żądań).
2. **Input flood** - maksymalnie wypełnione okno kontekstu przy każdym żądaniu (koszt prefill rośnie ze względu na długość).
3. **Pętla agentowa / fan-out** - agent wywołuje LLM rekurencyjnie (tool -> LLM -> tool), wiele sub-calli na jedno żądanie użytkownika.
4. **Kradzież klucza (LLMjacking)** - skradziony klucz/token agenta zużywany masowo i odsprzedawany; budżet per klucz ogranicza „blast radius".
5. **Omijanie liczników** - wiele równoległych żądań, które wszystkie przechodzą check „budżet < limit" zanim którekolwiek zaksięguje zużycie (race condition check-then-act); klucze/użytkownicy rotowani, aby nie trafić w limit per-caller; liczenie tokenów zaniżone (inny tokenizer) lub w ogóle pominięte dla streamu przerwanego przez klienta.
6. **Brak limitu na wyjściu w Ollamie** - `num_predict: -1` oznacza generację bez limitu (opcja zaprojektowana celowo), a klient może ją sam podać w `options`.

## 3. Real-World Evidence
| Tag | Opis | Źródło |
|---|---|---|
| `[RESEARCH]` | OWASP LLM10:2025 definiuje Unbounded Consumption, wymienia Denial of Wallet, Variable-Length Input Flood, Resource-Intensive Queries; mitygacje: limity rozmiaru wejścia, rate limiting i quota użytkowników, timeouty, throttling, logowanie i anomaly detection. | https://genai.owasp.org/llmrisk/llm10/ |
| `[REAL-ATTACK]` | **LLMjacking** (Sysdig TRT, 6 maja 2024): sprawca wykorzystał podatność Laravel (CVE-2021-3129), wykradł poświadczenia chmurowe i testował dostęp do ok. 10 usług LLM (OpenAI, Anthropic, AWS Bedrock itd.), z reverse proxy do odsprzedaży dostępu. Sysdig szacuje potencjalny koszt dla ofiary na ok. **46 080 USD/dzień** (Claude 2, maksymalne limity w wielu regionach). Mechanizm: skradziony klucz bez budżetu = nieograniczony rachunek. Zapobieganie: least-privilege, logowanie wywołań modeli, **limity/alerty kosztowe per klucz**. | https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/ |
| `[REAL-ATTACK]` | Dalszy ciąg kampanii: operatorzy LLMjacking szybko dodali klucze DeepSeek (dostęp w dniach po premierze V3, w ok. dobę po R1) - Dark Reading (doniesienie prasowe oparte na Sysdig). | https://www.darkreading.com/application-security/llm-hijackers-deepseek-api-keys |
| `[CONFIRMED-VULN]` / design | Ollama: `num_predict` domyślnie 128, `-1` = generacja bez limitu, `-2` = wypełnij kontekst (dokumentowane zachowanie, nie bug - ale klient może to ustawić, więc gateway musi nadpisywać). | https://www.ssdnodes.com/learn/ollama-num-predict-explained (wtórne; zweryfikować w dokumentacji Modelfile Ollamy) |
| `[RESEARCH]` | Race condition check-then-act w „miękkich" budżetach: wg publikacji autorów biblioteki floe-guard test 1000 równoległych żądań vs cap 500 dał nadwyżkę +113..+161 przy naiwnym check-then-act, a dokładnie 500 przy atomowym egzekwowaniu. | https://pypi.org/project/floe-guard/0.6.0/ - `[VENDOR-CLAIM]`, niezależnie niezweryfikowane (liczby traktować orientacyjnie, ale mechanizm jest standardowy). |
| `[MITIGATION]` | LiteLLM wprowadził **Budget Reservation** (domyślnie włączone): szacuje maksymalny koszt przed wysłaniem i zapobiega równoległemu przekroczeniu; flaga `fail_closed_budget_enforcement`; uwaga: modele o koszcie 0 omijają kontrolę budżetu. | https://docs.litellm.ai/docs/proxy/users |
| `[RESEARCH]` | Różnice tokenizerów: Qwen produkuje ok. 8% mniej tokenów niż `cl100k_base` (współczynnik ok. 0,92) wg narzędzia porównawczego - szacunek, nie gwarancja. | https://huggingface.co/spaces/xzuyn/Token-Count-Comparison/blob/main/app.py (niezweryfikowane niezależnie) |

Uwaga uczciwościowa: nie znalazłem w tym researchu zweryfikowanego, nazwanego incydentu typu „Denial of Wallet" w czystym LLM-owym SaaS z podaną kwotą poza LLMjacking; takie historie (rachunki na forach) są anegdotyczne - **niezweryfikowane**, nie cytuję.

## 4. Deterministic Detection
1. **Parametry żądania (pre-flight)**
   - `max_tokens` / `max_completion_tokens` / `options.num_predict` / `options.num_ctx`: jeśli brak -> **wstrzyknij** `default_max_output`; jeśli > `policy.max_output` (lub `-1`, `-2`) -> **clamp** (preferowane) albo BLOCK.
   - model allowlist (VISION §4.A.12) -> inny budżet/cena per model.
2. **Liczenie tokenów wejścia przed wywołaniem** (patrz niżej, hierarchia dokładności): `input_tokens_est`, odrzucenie > `max_input_tokens`, a także rozmiar w bajtach/znakach jako tani pierwszy filtr (zanim w ogóle tokenizujemy 50 MB body).
3. **Rezerwacja budżetu**: `reserve = input_tokens_est + max_output_effective` (worst case); zapis atomowy w Postgres; po odpowiedzi *reconcile* do `usage` rzeczywistego (zwrot nadwyżki).
4. **Liczenie wyjścia / usage**:
   - Ollama `/api/chat`, `/api/generate`: `prompt_eval_count` (wejście) i `eval_count` (wyjście) w odpowiedzi; przy streamingu **wyłącznie w ostatnim chunku z `done: true`** (https://docs.ollama.com/api/usage). Dostępne też `prompt_eval_cached_count`.
   - Endpoint zgodny z OpenAI: `stream_options: {"include_usage": true}` dodaje `usage` do ostatniego chunku (wg opisów integracji; zweryfikować na wersji Ollamy w repo).
   - Dlatego: **usage z odpowiedzi modelu jest źródłem prawdy**, a tiktoken/HF tokenizer tylko do estymaty pre-flight.
   - Strumień przerwany (klient się rozłączył, timeout): ostatni chunk nie przychodzi -> księguj **estymatę** (liczba odebranych chunków/znaków przez tokenizer) albo pełną rezerwację; nigdy 0.
5. **Hierarchia dokładności liczenia tokenów**
   | Metoda | Dokładność dla Qwen/Gemma w Ollama | Uwagi |
   |---|---|---|
   | `usage`/`eval_count` z Ollamy | dokładna (po fakcie) | prawda do rozliczeń |
   | tokenizer HF modelu (`tokenizers`, plik `tokenizer.json`) w sidecarze | dokładna dla surowego tekstu; szablon chatu dodaje tokeny specjalne | wymaga sidecara Python lub biblioteki DJL/`tokenizers` JNI w Javie |
   | tiktoken `o200k_base`/`cl100k_base` | przybliżona (rząd +-10-20%, inny słownik; dla polskiego i kodu większe rozbieżności) | OK jako górne oszacowanie z marginesem, nie do rozliczeń |
   | `ceil(chars/3)` lub bajty/2 | bardzo zgrubna, ale **konserwatywna** i O(1) | dobra jako szybki guard przed tokenizacją; polski tekst ma więcej tokenów na znak niż angielski |
   Decyzja: pre-flight = konserwatywna estymata (np. `max(ceil(chars/3), tiktoken*1.2)`), księgowanie = `usage` z Ollamy.
6. **Cost tracking**: tabela cen jako dane (YAML/DB): `price_per_1k_input`, `price_per_1k_output`, dla lokalnych modeli jednostka umowna (np. „credit" = token ważony modelem, albo koszt energii/czasu GPU). `cost = in/1000*p_in + out/1000*p_out`. Używać `BigDecimal`/`NUMERIC`, nie `double`.
7. **Circuit breaker runaway**: limit czasu wall-clock, limit tokenów wyjścia na sekundę bez postępu, wykrycie powtórzeń w streamie (np. ten sam n-gram >N razy) -> przerwij połączenie z Ollamą (cancel) i zaksięguj to, co wygenerowano.
8. **Anomalia**: zużycie w oknie 5 min > k * mediana z ostatnich dni dla danego klucza (prosty z-score/EWMA, deterministyczne) -> alert/REVIEW.

## 5. Detection Pipeline
Request -> Canonicalization (limit bajtów body, parse JSON) -> AuthN (caller_id, agent_id) -> Policy (załaduj budżety i limity dla caller/agent/model; hot-reload) -> **Rules BUDGET-001..003 (clamp parametrów, pre-flight tokeny, rezerwacja atomowa)** -> LLM/MCP (Ollama, streaming z licznikiem i breakerem BUDGET-006) -> Output (reconcile BUDGET-004: `usage` z ostatniego chunku, zwrot niewykorzystanej rezerwacji) -> Response (nagłówki `X-Budget-Remaining`, `X-RateLimit-*`, audit log z `tokens_in/out/cost`).
Zasada kolejności: tanie kontrole (bajty, parametry) przed tokenizacją, tokenizacja przed rezerwacją, rezerwacja przed wywołaniem modelu.

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| Brak `max_tokens`, klient podał `-1` | **clamp/ALLOW** z wpisem do audytu (modyfikacja żądania) |
| Wejście > `max_input_tokens` | BLOCK (HTTP 413/400, `budget.input_limit`) |
| Okno minutowe (TPM/RPM) przekroczone | RATE_LIMIT (429 + `Retry-After`) |
| Soft cap (np. 80% dziennego) | ALLOW + alert/REVIEW w dashboardzie |
| Hard cap dzienny/miesięczny | BLOCK (HTTP 429 lub 402, `budget.daily_cap`) |
| Anomalia zużycia / podejrzenie kradzieży klucza | QUARANTINE klucza (tymczasowa blokada) + REVIEW |
| Runaway generation | przerwanie streamu + księgowanie częściowego zużycia |
| Awaria Postgresa przy sprawdzaniu | **fail-closed** dla hard cap (BLOCK), konfigurowalnie fail-open dla soft |

## 7. Bypass / Limitations
- **Wyścig check-then-act**: bez atomowej rezerwacji N równoległych żądań przechodzi kontrolę (patrz §3). Mitygacja: BUDGET-003.
- **Zaniżona estymata**: inny tokenizer -> pre-flight pozwala na więcej, niż myślimy; reconcile do `usage` naprawia księgowanie, ale nie cofnie już wykonanej pracy. Dlatego rezerwacja worst-case.
- **Klienci rozłączający stream**: brak finalnego chunku -> brak `usage`. Należy księgować estymatę.
- **Model o cenie 0**: wg dokumentacji LiteLLM modele o koszcie 0 omijają kontrolę budżetu - u nas lokalny model ma cenę zerową, więc **budżet musi być też w tokenach/sekundach**, nie tylko w „USD".
- **Rotacja kluczy / wiele tożsamości**: budżet per klucz nie chroni przed atakującym tworzącym kolejne; potrzebny budżet globalny i per IP/tenant.
- **Cache prefiksu Ollamy** (`prompt_eval_cached_count`) zmniejsza realny koszt wejścia - decydujemy, czy liczyć pełne (prościej, sprawiedliwie) czy tylko nowe tokeny.
- **Ciche obcinanie kontekstu**: gdy `num_ctx` (domyślnie niski, ok. 2048-4096 zależnie od wersji) < prompt, llama.cpp może obciąć wejście, a `prompt_eval_count` pokaże mniej niż wysłano (wg opisów integracji, niezweryfikowane na naszej wersji) - wymuszać `num_ctx` w gateway i porównywać z estymatą.
- **Fałszywe pozytywy**: zbyt niski cap blokuje uczciwych użytkowników; dlatego soft cap + wyraźny komunikat i dashboard.
- **Wydajność**: tokenizacja O(n) w JVM bywa wolna dla dużych body - stąd filtr bajtowy przed nią.
- Kontrola **nie ocenia treści** - atak „tanie, ale szkodliwe" przechodzi (to domena innych kontroli).

## 8. Deterministic vs AI
Całość jest deterministyczna: limity, liczniki, ceny, rezerwacja, circuit breaker. Do sidecara (AI/ML) należy tylko:
- opcjonalnie dokładny tokenizer modelu (biblioteka, nie klasyfikator),
- wykrywanie *semantycznych* pętli/powtórzeń w streamie (poza prostym n-gram),
- klasyfikacja „prompt wymusza ekstremalnie długi output" (np. „wypisz 100000 razy") - heurystyka regex jest tania i wystarcza w MVP; model dopiero jeśli czas pozwoli.
Granica: AI nigdy nie jest bramką budżetową - liczniki muszą być twarde i audytowalne.

## 9. Implementation Options
- **Java/Spring Cloud Gateway (rekomendowane)**: `GatewayFilterFactory` `TokenBudget` + `R2dbc`/JDBC (na boundedElastic) do Postgresa. Dla streamu: dekorator `ServerHttpResponse`/`Flux<DataBuffer>` parsujący NDJSON Ollamy linia po linii i wyciągający ostatni obiekt z `done:true`.
- Rate limiting krótkookienkowy: wbudowany `RequestRateLimiter` wymaga Redisa (VISION: Redis opcjonalny) -> alternatywa: Bucket4j (in-memory, token bucket) dla RPM/TPM, Postgres tylko dla budżetów okresowych.
- **Tokenizer**: w Javie DJL `HuggingFaceTokenizer` / JTokkit (port tiktoken) do estymaty; w sidecarze Python `tokenizers`/`transformers` z `tokenizer.json` modelu.
- **Postgres - atomowość**: jedno wyrażenie `UPDATE ... SET used = used + :n WHERE used + :n <= :limit RETURNING used` jest atomowe; w trybie READ COMMITTED druga transakcja po zablokowaniu na wierszu **ponownie ewaluuje WHERE na nowej wersji wiersza** (https://www.postgresql.org/docs/current/transaction-iso.html), więc nie dojdzie do przekroczenia limitu. 0 zwróconych wierszy = brak budżetu. Wzorzec rezerwacja/rozliczenie opisany niżej. Okno okresowe: klucz `(subject, period_start)` i `INSERT ... ON CONFLICT DO UPDATE` (upsert), reset przez zmianę `period_start`, bez crona.
- Hot spot: jeden wiersz na globalny budżet to punkt kontencji (serializacja zapisów). Na skalę hackathonu wystarczy; skala: sharding licznika (N podwierszy) albo Redis `INCRBY`/Lua - zgodnie z VISION §3 tylko jeśli Postgres okaże się za wolny.

Schemat:
```sql
CREATE TABLE budget_counter (
  subject      text        NOT NULL,   -- 'user:alice' | 'agent:bot1' | 'model:qwen2.5' | 'global'
  period_start date        NOT NULL,   -- początek doby/miesiąca
  period_kind  text        NOT NULL,   -- 'day' | 'month'
  used_tokens  bigint      NOT NULL DEFAULT 0,   -- zaksięgowane
  reserved     bigint      NOT NULL DEFAULT 0,   -- w locie
  used_cost    numeric(18,6) NOT NULL DEFAULT 0,
  PRIMARY KEY (subject, period_kind, period_start)
);
-- rezerwacja (0 wierszy => BLOCK budget.*_cap):
INSERT INTO budget_counter(subject,period_kind,period_start) VALUES (:s,:k,:p) ON CONFLICT DO NOTHING;
UPDATE budget_counter SET reserved = reserved + :r
 WHERE subject=:s AND period_kind=:k AND period_start=:p
   AND used_tokens + reserved + :r <= :hard_limit
RETURNING used_tokens, reserved;
-- rozliczenie (reconcile): reserved -= :r ; used_tokens += :actual ; used_cost += :cost
```
Wiele wymiarów (user + agent + model + global) -> rezerwuj we **stałej kolejności kluczy** (unikanie deadlocków) w jednej transakcji; niepowodzenie któregokolwiek = rollback.
Osierocone rezerwacje (crash gateway): tabela `reservation(id, expires_at)` i zwalnianie po TTL.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| LiteLLM Proxy | https://docs.litellm.ai/docs/proxy/users | Python | MIT (core; funkcje enterprise osobno, np. `model_max_budget` jest enterprise) | Budżety key/user/team/global, `soft_budget`, `budget_duration`, TPM/RPM, budget reservation | Dojrzały model danych, referencja funkcji | Osobny serwer Python, wymaga własnego Postgresa, duplikuje nasz gateway; część budżetów płatna | Średnia | tak | Wzorzec/porównanie, nie zależność |
| Portkey AI Gateway | https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit | TS | MIT (gateway OSS); budget limits w planie Enterprise/Pro wg dokumentacji | Budget limits cost/tokens, alert threshold, reset | Limity w tokenach i USD | Funkcja budżetów w wersji hostowanej/płatnej | Wysoka | częściowo | Tylko porównanie |
| Bucket4j | https://github.com/bucket4j/bucket4j | Java | Apache-2.0 | Token bucket RPM/TPM in-memory/Redis/JDBC | Natywne dla JVM, backend JDBC | Nie liczy kosztów | Niska | tak | Wysoka (krótkie okna) |
| JTokkit | https://github.com/knuddelsgmbh/jtokkit | Java | MIT | tiktoken w JVM | Szybki, bez Pythona | Tylko słowniki OpenAI -> estymata | Niska | tak | Średnia (pre-flight) |
| DJL HuggingFace tokenizers | https://github.com/deepjavalibrary/djl | Java | Apache-2.0 | Dokładny tokenizer modelu z `tokenizer.json` | Dokładny dla Qwen/Gemma | Natywna zależność (JNI) | Średnia | tak | Wysoka, jeśli dokładność potrzebna |
| HF `tokenizers` | https://github.com/huggingface/tokenizers | Rust/Python | Apache-2.0 | Tokenizer w sidecarze | Dokładny | Dodatkowy hop HTTP | Niska | tak | Średnia |
| tiktoken | https://github.com/openai/tiktoken | Python/Rust | MIT | BPE OpenAI | Szybki | Zły słownik dla Qwen | Niska | tak | Niska-średnia |
Statusy licencji/projektów powyżej (poza LiteLLM/Portkey dokumentacją) pochodzą z wiedzy ogólnej, nie z fetchu - **niezweryfikowane** w tej sesji; sprawdzić przed użyciem w README.

## 11. Proposed Control
| ID | Nazwa | Opis |
|---|---|---|
| BUDGET-001 | Output cap enforcer | Wymuszenie/clamp `max_tokens`, `num_predict`, `num_ctx`; odrzucenie `-1/-2` |
| BUDGET-002 | Input size & token limit | Limity bajtów, estymata tokenów, `max_input_tokens` per model |
| BUDGET-003 | Atomic budget reservation | Rezerwacja worst-case w Postgres (dzień/miesiąc; per user/agent/model/global) |
| BUDGET-004 | Usage reconciliation & cost tracking | `eval_count`/`prompt_eval_count` z ostatniego chunku, koszt wg tabeli cen, zwrot nadwyżki |
| BUDGET-005 | Soft cap alert | Progi np. 50/80/100% -> alert/REVIEW na dashboardzie |
| BUDGET-006 | Runaway generation breaker | Timeout, tempo bez postępu, wykrycie powtórzeń; cancel żądania do Ollamy |
| BUDGET-007 | TPM/RPM rate limit | Token bucket (Bucket4j) per klucz/model, 429 + `Retry-After` |
| BUDGET-008 | Consumption anomaly / key quarantine | Skok zużycia vs baza -> QUARANTINE klucza |

Wiersz dla koordynatora: `BUDGET-001..008 | Token budget & quotas | resource | Denial of Wallet / Unbounded Consumption (OWASP LLM10), LLMjacking | limity parametrów, estymata + usage, atomowa rezerwacja w Postgres | BLOCK/RATE_LIMIT/clamp/QUARANTINE | M | MUST`

## 12. Example Configuration
```yaml
- id: BUDGET-001
  name: Enforce output token cap
  category: resource
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: param-clamp, params: [max_tokens, options.num_predict], default: 512, max: 1024, forbid_values: [-1, -2] }
  action: ALLOW            # modyfikacja żądania (clamp); BLOCK gdy on_violation: block
  severity: LOW
  threshold: { on_violation: clamp }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://genai.owasp.org/llmrisk/llm10/"] }

- id: BUDGET-002
  name: Max input size and tokens
  category: resource
  enabled: true
  priority: 15
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: token-count, max_body_bytes: 262144, max_input_tokens: 3000, estimator: conservative, margin: 1.2 }
  action: BLOCK
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: BUDGET-003
  name: Daily token hard cap per user
  category: resource
  enabled: true
  priority: 30
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: budget-reservation, subject: "user:{caller_id}", period: day, unit: tokens, reserve: "input_est + max_output" }
  action: BLOCK
  severity: HIGH
  threshold: { hard: 20000, soft_pct: 80 }
  exceptions: ["agent:admin-tools"]
  metadata: { owasp: [LLM10], policy_name: budget.daily_cap, references: [] }

- id: BUDGET-005
  name: Soft cap alert
  category: resource
  enabled: true
  priority: 31
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: budget-threshold, subject: "user:{caller_id}", period: day, pct: 80 }
  action: REVIEW
  severity: LOW
  threshold: { pct: 80 }
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: BUDGET-006
  name: Runaway generation breaker
  category: resource
  enabled: true
  priority: 40
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: stream-guard, max_wall_seconds: 120, max_output_tokens: 1024, repeat_ngram: { n: 8, max_repeats: 6 } }
  action: BLOCK            # przerwij stream, zaksięguj częściowe zużycie
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: BUDGET-007
  name: Tokens-per-minute rate limit
  category: resource
  enabled: true
  priority: 25
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: token-bucket, key: "caller_id+model", capacity_tokens: 4000, refill_per_minute: 4000, rpm: 20 }
  action: RATE_LIMIT
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM10], references: [] }

- id: BUDGET-008
  name: Consumption spike quarantine
  category: resource
  enabled: true
  priority: 50
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: baseline-spike, window_minutes: 5, factor: 10, min_tokens: 5000 }
  action: QUARANTINE
  severity: HIGH
  threshold: { factor: 10 }
  exceptions: []
  metadata: { owasp: [LLM10], references: ["https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/"] }
```
Cennik jako dane (hot-reload):
```yaml
pricing:
  qwen2.5:1.5b-instruct-q4_K_M: { unit: credit, per_1k_input: 1.0, per_1k_output: 2.0 }
  gemma3:1b:                    { unit: credit, per_1k_input: 0.7, per_1k_output: 1.4 }
```

## 13. Example Requests
```json
// 1. Brak max_tokens -> clamp do 512
{ "request": { "model": "qwen2.5:1.5b-instruct-q4_K_M", "messages": [{"role":"user","content":"Opisz historię Polski"}] },
  "expect": { "action": "ALLOW", "modified": { "options.num_predict": 512 }, "policy": "BUDGET-001" } }

// 2. Klient wymusza nieskończoną generację
{ "request": { "model": "qwen2.5:1.5b-instruct-q4_K_M", "options": { "num_predict": -1 }, "messages": [{"role":"user","content":"pisz bez końca"}] },
  "expect": { "action": "ALLOW", "modified": { "options.num_predict": 512 }, "policy": "BUDGET-001" } }

// 3. Przekroczony dzienny hard cap
{ "request": { "caller": "user:alice", "used_today": 19900, "reserve": 600 },
  "expect": { "action": "BLOCK", "http": 429, "policy": "budget.daily_cap", "rule": "BUDGET-003" } }

// 4. Wejście zbyt duże
{ "request": { "messages": [{"role":"user","content":"<20 000 słów>"}] },
  "expect": { "action": "BLOCK", "http": 413, "rule": "BUDGET-002" } }

// 5. 50 równoległych żądań vs cap na 10 rezerwacji
{ "request": { "parallel": 50, "reserve_each": 1000, "cap": 10000 },
  "expect": { "allowed": 10, "blocked": 40, "overshoot": 0, "rule": "BUDGET-003" } }
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| BUDGET-T001 | Request bez `max_tokens` | `num_predict` ustawione na default, ALLOW |
| BUDGET-T002 | `num_predict: -1` | clamp do max (lub BLOCK wg konfiguracji) |
| BUDGET-T003 | `max_tokens: 10_000_000` | clamp do `max` |
| BUDGET-T004 | Prompt > `max_input_tokens` | BLOCK 413 |
| BUDGET-T005 | N+1-sze żądanie po wyczerpaniu dziennego capu (`repeat: 50`) | ostatnie BLOCK `budget.daily_cap` (test z VISION §6) |
| BUDGET-T006 | 50 równoległych żądań, cap na 10 | dokładnie 10 przechodzi, zero nadwyżki (atomowość) |
| BUDGET-T007 | Stream z `done:true` i `eval_count=120` | licznik rośnie o rzeczywiste 120 + wejście, rezerwacja zwrócona |
| BUDGET-T008 | Klient zrywa stream w połowie | zaksięgowana estymata/rezerwacja, nie 0 |
| BUDGET-T009 | Zmiana limitu w YAML w trakcie działania | nowe żądanie respektuje nowy limit bez restartu |
| BUDGET-T010 | Reset okna po zmianie doby (mock zegara 23:59 -> 00:00) | licznik od zera, stary wiersz zachowany w audycie |
| BUDGET-T011 | Soft cap 80% | ALLOW + wpis REVIEW/alert w audit log |
| BUDGET-T012 | Dwa różne subjecty (user A wyczerpany, user B nie) | A BLOCK, B ALLOW |
| BUDGET-T013 | Model o cenie 0 w cenniku | budżet w tokenach nadal egzekwowany |
| BUDGET-T014 | Awaria Postgresa przy hard cap | fail-closed BLOCK (konfig) |
| BUDGET-T015 | Pętla powtórzeń w streamie | breaker przerywa, częściowe zużycie zaksięgowane |
| BUDGET-T016 | Skok 10x zużycia w 5 min | QUARANTINE klucza |
| BUDGET-T017 (bypass) | Polski tekst z emoji/kodem, estymata vs rzeczywiste `prompt_eval_count` | estymata >= rzeczywiste (konserwatywna) |
| BUDGET-T018 (bypass) | Wiele kluczy tego samego tenanta | budżet globalny/tenantowy zatrzymuje |

## 15. Sources
- OWASP LLM10:2025 Unbounded Consumption — https://genai.owasp.org/llmrisk/llm10/ — 2025 — `[RESEARCH]` (pobrane)
- Sysdig, LLMjacking: Stolen Cloud Credentials Used in New AI Attack — https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/ — 2024-05-06 — `[REAL-ATTACK]` (pobrane)
- Dark Reading, LLM Hijackers Quickly Incorporate DeepSeek API Keys — https://www.darkreading.com/application-security/llm-hijackers-deepseek-api-keys — 2025 — `[REAL-ATTACK]` (tylko wynik wyszukiwania, treść niepobrana)
- Ollama API usage metrics — https://docs.ollama.com/api/usage — b.d. — dokumentacja (pobrane)
- Ollama `num_predict` explained (SSD Nodes) — https://www.ssdnodes.com/learn/ollama-num-predict-explained — b.d. — źródło wtórne, `[RESEARCH]`
- LiteLLM Budgets, Rate Limits — https://docs.litellm.ai/docs/proxy/users — b.d. — `[MITIGATION]` / dokumentacja (pobrane)
- Portkey, Enforce budget and rate limit — https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit — b.d. — `[VENDOR-CLAIM]` (wynik wyszukiwania)
- PostgreSQL, Transaction Isolation (Read Committed) — https://www.postgresql.org/docs/current/transaction-iso.html — b.d. — dokumentacja (pobrane)
- floe-guard (test przekroczenia capu przy check-then-act) — https://pypi.org/project/floe-guard/0.6.0/ — b.d. — `[VENDOR-CLAIM]`, niezweryfikowane
- Token Count Comparison (Qwen vs GPT) — https://huggingface.co/spaces/xzuyn/Token-Count-Comparison/blob/main/app.py — b.d. — `[RESEARCH]`, niezweryfikowane
- Opisy `stream_options.include_usage` w Ollama OpenAI-compat (wtórne) — https://ollama.readthedocs.io/en/openai/ — b.d. — niezweryfikowane na naszej wersji
