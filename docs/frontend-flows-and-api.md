# Frontend: przepływy E2E i kontrakt API (DRAFT do uzgodnienia z backendem)

Ten plik jest szczegółem technicznym do [`VISION.md`](../VISION.md) i nie zmienia jego decyzji.
W razie rozbieżności obowiązuje `VISION.md`, a nad nim
[`CRITERIA AI Control Layer.pdf`](../project-spec/CRITERIA%20AI%20Control%20Layer.pdf).
Backlog frontu: [`frontend-user-stories.md`](frontend-user-stories.md).

Status: `POST /v1/chat/completions` **działa** w backendzie (allowlista modeli + wywołanie Ollamy,
VISION §7). Reszta endpointów to propozycja. Zmiany względem tego, co już jest, oznaczam jako
*rozszerzenie* i są one addytywne (nie psują obecnego kontraktu).

## 1. Co jury robi z UI (to wyznacza zakres)

| Jury… | Źródło | Ekran |
|---|---|---|
| wpisuje własne prompty i patrzy na reakcję | CRITERIA §6 | **Playground** + **X-ray** (wyróżnik E) |
| sprawdza mierzalną odporność na korpusie ataków | VISION §5 B, §8 | **Red Team Arena** (wyróżnik B) |
| zmienia config (reguły, progi, wyłączenie kontroli) i patrzy na reakcję | CRITERIA §6, VISION §4 | **Polityka** + baner wersji |
| ogląda postawę bezpieczeństwa, blokady, zużycie | CRITERIA §3.3, §4.5 | **Przegląd** |
| analizuje i eksportuje logi | CRITERIA §4.5, VISION §6 | **Zdarzenia** + eksport |
| patrzy na telemetrię wydajności | CRITERIA §6 | Przegląd (p50/p95), Arena (narzut) |

## 2. Mapa ekranów i priorytety

Priorytety wynikają z VISION §1 i §5: **B i E to główne MVP**, A/C/D są opcjonalne,
pełny MCP i rozbudowane role nie są warunkiem MVP.

| # | Ścieżka | Ekran | Prio | Główne API |
|---|---|---|---|---|
| 0 | `/login` | Logowanie przez dostawcę tożsamości | P0 | `/api/me`, `/api/auth/providers` |
| 1 | `/playground` | Czat przez gateway + **X-ray** decyzji | P0 (E) | `/v1/chat/completions`, `/api/models` |
| 2 | `/arena` | **Red Team Arena**: korpus ataków bez ochrony vs przez Control Layer | P0 (B) | `/api/arena/*` |
| 3 | `/overview` | KPI, trend decyzji, stan kontrolek, budżet, p50/p95 | P0 | `/api/metrics/*`, `/api/health` |
| 4 | `/events` | Audyt: filtry, szczegóły (X-ray), eksport CSV/JSON | P0 | `/api/events*` |
| 5 | `/policy` | Polityka: kontrolki (tryb/próg/stan), edytor YAML, walidacja, wersje | P0 | `/api/policy*`, `/api/controls` |
| 5a | `/policy` | Shadow nowej wersji polityki | P1 | `/api/policy/shadow` |
| 6 | `/budgets` | Zużycie tokenów vs limity (kafelek na Przeglądzie jest P0) | P1 | `/api/budgets` |
| 7 | `/sessions` | Data-Flow Firewall, graf sesji (wyróżnik A) | opcja | `/api/sessions*` |
| 8 | `/policy/replay` | Policy Time Machine (wyróżnik C) | opcja | `/api/policy/replay` |
| 9 | szuflada w Zdarzeniach | Policy Copilot (wyróżnik D) | opcja | `/api/policy/suggest` |

Stały element layoutu: **pasek statusu** z aktywną wersją polityki (`v13 · a1b2c3d`), stanem
komponentów (gateway, provider semantyczny, Ollama, baza), wskaźnikiem live i menu użytkownika.

## 3. Przepływy E2E

### F0. Logowanie
Opisane w §4a.

### F1. Prompt ad hoc w Playground + X-ray (P0, wyróżnik E)
1. Wejście: `GET /api/models` wypełnia dropdown modeli.
2. Wysłanie: `POST /v1/chat/completions` z `{ model, messages }` i nagłówkiem `X-Session-Id`.
3. Odpowiedź to `GuardedChatResponse` (§5.1), **także przy błędach** (`400`, `403`, `502`, docelowo `429`).
   Front nie zgaduje po statusie HTTP, tylko czyta `action`:
   - `allow` / `monitor`: odpowiedź modelu (przy `monitor` dodatkowo ostrzeżenie „wykryto, nie zablokowano”);
   - `redact`: odpowiedź z podświetlonymi `[REDACTED:…]`;
   - `require_approval`: „wstrzymane” (bez approval flow działa jak `block`, VISION §4);
   - `block`: komunikat z `blockedBy`.
4. **X-ray** rysuje się od razu z `trace` w odpowiedzi: ścieżka kontroli, sygnały, akcje, latencja
   każdej kontroli, wersja polityki, status techniczny (`ok/degraded/error`). Bez dodatkowego requestu.
5. Zdarzenie trafia do audytu, SSE `request.completed` odświeża Przegląd i Zdarzenia.

### F2. Red Team Arena (P0, wyróżnik B)
1. `/arena` → `GET /api/arena/corpora` (korpusy: nazwa, liczba przypadków ataków i benign, kategorie, języki PL/EN).
2. Wybór korpusu i modelu → `POST /api/arena/runs` → `{ runId }`.
   Backend wysyła każdy przypadek **dwiema ścieżkami**: bezpośrednio do modelu (bez ochrony) i przez Control Layer.
3. Postęp na żywo: SSE `arena.progress` (licznik) i `arena.case` (wynik przypadku), albo polling `GET /api/arena/runs/{id}`.
4. Wynik:
   - porównanie: ataki, które przeszły bez ochrony vs przez Control Layer;
   - **benign-block rate** (fałszywe alarmy na nieszkodliwych promptach);
   - narzut latencji p50/p95;
   - rozbicie per kategoria i język;
   - lista przypadków, kliknięcie → X-ray tego żądania.
5. Historia przebiegów: `GET /api/arena/runs`, żeby pokazać poprawę między wersjami polityki.

### F3. Zmiana polityki w pliku (P0)
1. Gateway przeładowuje politykę atomowo (VISION §4).
   - poprawna: SSE `policy.activated { version, hash, source: "file", changedControls[] }`;
   - błędna: SSE `policy.rejected { errors[], activeVersion }`; aktywna zostaje ostatnia poprawna.
2. Baner na każdym ekranie: zielony „Załadowano v14” albo czerwony „Odrzucono: błąd w linii 12, aktywna v13”.
3. Kolejny prompt w Playground ma w X-ray `policyVersion: v14`, więc widać, że zmiana działa.

### F4. Zmiana polityki w UI (P0, shadow P1)
1. `GET /api/policy` → YAML, wersja, hash, profil (`permissive | balanced | strict`).
2. Walidacja podczas pisania: `POST /api/policy/validate` (błędy z numerami linii).
3. „Zastosuj”: `PUT /api/policy { raw, baseVersion }`; `409` przy konflikcie z równoległą zmianą pliku.
4. Szybka zmiana trybu/progu kontrolki z katalogu: `PATCH /api/controls/{id}` → nowa wersja tej samej polityki.
5. (P1) „Uruchom jako shadow”: `POST /api/policy/shadow { raw }`. Decyzje shadow są raportowane obok aktywnych,
   X-ray pokazuje „aktywna: allow / shadow: block”, a Przegląd licznik rozbieżności. `DELETE /api/policy/shadow` kończy shadow.

### F5. Audyt i eksport (P0)
1. `/events` → `GET /api/events?action&policy&sessionId&model&from&to&cursor&limit` (filtry w URL).
2. Kliknięcie → `GET /api/events/{requestId}` → ten sam komponent X-ray co w Playground.
3. Eksport: `GET /api/events/export?format=csv|json&<filtry>`.
4. **Audyt nie przechowuje pełnych promptów ani surowego PII** (VISION §6), więc X-ray w Zdarzeniach
   pokazuje tylko zamaskowane fragmenty i hashe, nie oryginalny tekst. Pełny tekst jest tylko w Playground,
   bo tam wpisał go użytkownik.
5. Treść renderowana jako tekst (nigdy `dangerouslySetInnerHTML`): logi mogą zawierać wstrzyknięty HTML.

### F6. Zdrowie i degradacja (P0)
`GET /api/health` przy starcie, potem SSE `health.changed`. Gdy provider semantyczny nie działa
(brak klucza, timeout, limit), pasek statusu pokazuje `degraded`, a kontrolki semantyczne w X-ray mają
status `degraded/error`, **nigdy „czysto”** (VISION §4, tooling.md).

### F7. Budżet (kafelek P0, strona P1)
`GET /api/budgets`; przekroczenie → odpowiedź `block` z `blockedBy: "budget.*"`; SSE `budget.updated`.

## 4. Konwencje kontraktu

- `/v1/*`: endpoint dla klientów LLM; wejście w kształcie OpenAI, **odpowiedź w kształcie `GuardedChatResponse`** (tak działa backend).
- `/api/*`: API dashboardu; JSON camelCase, czasy ISO-8601 UTC, ID jako stringi.
- Błędy `/api/*`: `{ "error": { "code", "message", "details"? } }`. Paginacja: `{ items, nextCursor }`.
- Live: **SSE** `GET /api/stream` (jeden strumień, `event:` = typ).
- Nigdy surowe PII, sekrety ani pełne prompty w `/api/*` (VISION §6).
- Enumy (VISION §4):
  - `Action = allow | monitor | redact | require_approval | block`
  - `ControlMode = off | monitor | redact | require_approval | block`
  - `TechStatus = ok | degraded | error`
  - `ControlKind = deterministic | semantic`
  - `Profile = permissive | balanced | strict`

## 4a. Logowanie do dashboardu

| Kto → do kogo | Mechanizm |
|---|---|
| Człowiek → UI | konto lokalne (`backend/config/users.yaml` → baza), `POST /api/auth/login` → ciasteczko `SESSION` |
| Agent, SDK, runner testów → `/v1/*` | HTTP Basic przy każdym żądaniu, bez sesji |
| Gateway → Ollama / provider semantyczny | adres i poświadczenia z env gatewaya; nigdy w przeglądarce |

(Wcześniejszy wariant z OAuth2/Google odrzucony na rzecz `docs/auth/` — działa offline, bez redirectów.)

- Ciasteczko `SESSION`: HttpOnly, SameSite=Lax, `Secure` z `AUTH_COOKIE_SECURE`. Front nie trzyma tokenu.
- CSRF wyłączony świadomie: SameSite=Lax + endpointy przyjmujące tylko JSON (uzasadnienie w `SecurityConfig.java`).
- 401 bez `WWW-Authenticate` (brak natywnego okienka przeglądarki). Limit 5 nieudanych prób/min na login → 429.
- Role z `policy.yaml`: `admin` widzi wszystko; `chat` tylko Playground (front ukrywa i przekierowuje, backend zwraca 403).
- Sesje w pamięci backendu: restart backendu wylogowuje wszystkich.

Przepływ F0:
1. Start SPA → `GET /api/auth/me`: `200` aplikacja, `401` ekran logowania (na dowolnej ścieżce).
2. Formularz → `POST /api/auth/login { login, password }` → `200 CurrentUser` + ciasteczko; zostajemy na tej samej ścieżce.
3. `401` w trakcie pracy (wygasła sesja, restart backendu) → ekran logowania z komunikatem „Sesja wygasła”.
4. „Wyloguj” → `POST /api/auth/logout` → ekran logowania.

| Metoda | Ścieżka | Odpowiedź |
|---|---|---|
| POST | `/api/auth/login` | `{ login, password }` → `200 { login, role }` / `401 { error: { code: "invalid_credentials" } }` / `429 { error: { code: "too_many_attempts" } }` |
| GET | `/api/auth/me` | `200 { login, role }` / `401` |
| POST | `/api/auth/logout` | `204` |

## 5. Endpointy

### 5.1 `POST /v1/chat/completions` (działa w backendzie)

Request: `{ model, messages: [{ role, content }] }`. `model` to dokładny tag z allowlisty
(`control-layer.models` w `application.yml`). Nagłówek `X-Session-Id` (*rozszerzenie*, opcjonalny).

Response, **stan obecny** (`GuardedChatResponse.java`):

```json
{
  "requestId": "6f1c…",
  "action": "allow",
  "message": { "role": "assistant", "content": "…" },
  "blockedBy": null,
  "trace": [
    { "policy": "model.allowlist", "kind": "deterministic", "action": "allow", "latencyMs": 3, "detail": null }
  ],
  "usage": { "promptTokens": 18, "completionTokens": 40 }
}
```

Statusy HTTP dziś: `200` allow, `400` walidacja (`blockedBy: "request.validation"`),
`403` model spoza allowlisty (`model.allowlist`), `502` awaria/timeout modelu (`upstream-error`).

**Rozszerzenia proponowane pod X-ray** (wszystkie opcjonalne, addytywne):

```jsonc
{
  "policyVersion": "v13", "policyHash": "a1b2c3d",
  "status": "ok",                       // TechStatus: czy któraś zależność była degraded/error
  "latency": { "totalMs": 412, "upstreamMs": 350 },
  "trace": [{
    "policy": "semantic.injection", "kind": "semantic", "action": "block", "latencyMs": 41, "detail": "…",
    "stage": "input",                   // input | output
    "mode": "block",                    // ControlMode z polityki
    "confidence": 0.93,                 // ControlResult.confidence (VISION §4)
    "threshold": 0.8,
    "status": "ok",                     // TechStatus tej kontroli
    "provider": "laya-local",           // dla kontroli semantycznych
    "spans": [{ "start": 12, "end": 31, "label": "PII:PESEL" }]   // fragmenty w tekście użytkownika do podświetlenia
  }],
  "shadow": { "policyVersion": "v14", "action": "block", "blockedBy": "…" }   // P1, gdy działa shadow
}
```

Propozycja: budżet → status `429` z `blockedBy: "budget.*"`.

### 5.2 Modele

| Metoda | Ścieżka | Odpowiedź |
|---|---|---|
| GET | `/api/models` | `[{ tag, provider: "ollama", enabled, status: "available"\|"unavailable" }]` (z `ModelCatalog` + ping providera) |

Odrzucenie modelu w `/v1/*` celowo nie rozróżnia „wyłączony” od „nieznany” (`ModelCatalog.java`).
Dashboard (zalogowany) może pokazać wyłączone modele, żeby zademonstrować allowlistę.

### 5.3 Red Team Arena

| Metoda | Ścieżka | Odpowiedź |
|---|---|---|
| GET | `/api/arena/corpora` | `[{ id, name, attackCases, benignCases, categories[], languages[] }]` |
| POST | `/api/arena/runs` | `{ corpusId, model }` → `202 { runId }` |
| GET | `/api/arena/runs` | `[{ runId, corpusId, model, policyVersion, startedAt, status, summary }]` |
| GET | `/api/arena/runs/{id}` | `ArenaRun` |

```ts
ArenaRun = {
  runId, corpusId, model, policyVersion, startedAt, finishedAt?,
  status: "running" | "done" | "failed", progress: { done, total },
  summary: {
    unprotected: { attacksSucceeded, attacksTotal, p50Ms, p95Ms },
    protected:   { attacksSucceeded, attacksBlocked, attacksTotal, benignBlocked, benignTotal, p50Ms, p95Ms },
    overheadMs:  { p50, p95 }
  },
  byCategory: [{ category, language, attacksTotal, blockedProtected, succeededUnprotected }],
  cases: [{ caseId, category, language, label: "attack" | "benign",
            unprotected: { outcome: "succeeded" | "failed" | "error", latencyMs },
            protected:   { action, blockedBy?, requestId, latencyMs, outcome } }]
}
```

`outcome` dla ścieżki bez ochrony wymaga sposobu oceny, czy atak „się udał”
(np. canary w treści przypadku, oczekiwany wzorzec w odpowiedzi). Patrz pytania w §7.

### 5.4 Metryki i zdrowie

| Metoda | Ścieżka | Odpowiedź |
|---|---|---|
| GET | `/api/health` | `{ status, components: [{ name: "gateway"\|"semantic-provider"\|"ollama"\|"database", status: "ok"\|"degraded"\|"error", detail?, provider? }], policy: { version, hash, loadedAt, shadowVersion? } }` |
| GET | `/api/metrics/summary?window=1h\|24h` | `{ totalRequests, byAction: { allow, monitor, redact, require_approval, block }, byStatus: { ok, degraded, error }, topPolicies: [{ policy, hits }], tokens: { used, cap? }, latency: { p50, p95 }, controls: { enforced, monitor, off, degraded }, shadowDisagreements? }` |
| GET | `/api/metrics/timeseries?window&bucket` | `[{ ts, allow, monitor, redact, require_approval, block }]` |

### 5.5 Audyt

| Metoda | Ścieżka | Odpowiedź |
|---|---|---|
| GET | `/api/audit/events?action&principal&model&blockedBy&sessionId&from&to&before&limit` | `{ items: AuditEvent[], nextCursor }` (✅). `action/principal/model/blockedBy` wielowartościowe (powtórzony parametr = OR), `sessionId` = zawiera |
| GET | `/api/audit/facets` | `{ actions[], principals[], models[], blockedBy[] }` — wartości do list wyboru (✅) |
| GET | `/api/audit/events/{requestId}` | `AuditEvent` z pełnym `trace` (✅) |
| GET | `/api/audit/verify` | `{ valid, checked, brokenAtSeq, reason }` (✅) |
| GET | `/api/audit/export?format=csv\|json&<filtry>` | plik (✅) |

```ts
AuditEvent = { requestId, timestamp, callerId?, sessionId?, model, action, blockedBy?, status,
  policyVersion, latencyMs, usage?, trace: Trace[],            // jak w §5.1, bez surowego tekstu
  safeFragments?: [{ policy, masked: "9001****1234", hash }] } // zamaskowane fragmenty zamiast promptu
```

### 5.6 Polityka i kontrolki

| Metoda | Ścieżka | Body / odpowiedź |
|---|---|---|
| GET | `/api/policy` | `{ version, hash, source: "file"\|"ui", loadedAt, profile, raw }` |
| POST | `/api/policy/validate` | `{ raw }` → `{ valid, errors: [{ line?, path, message }] }` |
| PUT | `/api/policy` | `{ raw, baseVersion }` → `200 Policy` / `409` / `422 { errors }` |
| GET | `/api/policy/versions` | `[{ version, hash, source, author?, createdAt }]` |
| GET | `/api/controls` | `[{ id, name, kind, stage, mode, threshold?, status: TechStatus, failMode: "open"\|"closed", hits24h, p95Ms }]` |
| PATCH | `/api/controls/{id}` | `{ mode?, threshold? }` → nowa wersja polityki |
| POST / DELETE | `/api/policy/shadow` (P1) | `{ raw }` → `{ shadowVersion }` / koniec shadow |

### 5.7 Budżety (P1, kafelek P0)

`GET /api/budgets` → `[{ id, scope: "global"|"caller"|"model", scopeRef?, window, metric: "tokens"|"requests", used, cap, status: "ok"|"warning"|"exhausted" }]`.

### 5.8 Strumień live `GET /api/stream` (SSE)

| `event:` | `data:` |
|---|---|
| `request.completed` | skrót `AuditEvent` |
| `policy.activated` / `policy.rejected` | `{ version, hash, source, changedControls[] }` / `{ errors[], activeVersion }` |
| `health.changed` | `{ component, status }` |
| `arena.progress` / `arena.case` | `{ runId, done, total }` / `{ runId, case }` |
| `budget.updated` | `Budget` |

Heartbeat co 15 s. Po zerwaniu połączenia: „live: rozłączono” i odświeżenie danych po reconnect.

### 5.9 Opcjonalne wyróżniki (A, C, D)

Kontrakt dopiero, gdy zespół weźmie któryś z nich. Szkice:
- A, Data-Flow Firewall: `GET /api/sessions`, `GET /api/sessions/{id}/graph` (węzły/krawędzie z etykietami taint), SSE `session.*`.
- C, Policy Time Machine: `POST /api/policy/replay { raw, window }` → zmienione decyzje.
- D, Policy Copilot: `POST /api/policy/suggest { requestId }` → propozycja reguły do zatwierdzenia przez człowieka.

## 6. Kolejność prac na froncie

1. Warstwa API pod obecny `GuardedChatResponse` + rozszerzenia, mocki, SSE.
2. Logowanie (F0).
3. Playground + **X-ray** (F1); podłączenie do żywego `/v1/chat/completions` od razu, bo działa.
4. **Red Team Arena** (F2).
5. Pasek statusu + baner polityki (F3, F6).
6. Zdarzenia + eksport (F5), Przegląd.
7. Polityka (F4).
8. P1: shadow, budżety, role. Opcje: A, C, D.

## 7. Otwarte pytania do backendu

1. Czy przyjmujecie rozszerzenia `GuardedChatResponse` z §5.1 (`policyVersion`, `status`, `stage`, `mode`, `confidence`, `spans`, `shadow`)?
2. Red Team Arena: jak oceniamy, czy atak na ścieżce **bez ochrony** się udał (canary, wzorzec oczekiwanej odpowiedzi, LLM-as-judge)?
   I czy przebieg robi backend (`/api/arena/runs`), czy ten sam runner co `./run-tests.sh`, a backend tylko serwuje wyniki?
3. Skąd korpus: pliki YAML w repo, wspólne z test suite (VISION §8)?
4. Identyfikacja wywołującego na `/v1/*` (krok 1 pipeline'u): klucz API, nagłówek, czy na razie brak?
5. Keycloak w compose czy tylko Google?
6. SSE (`Flux<ServerSentEvent>`) zamiast WebSocket: OK?
7. Czy `PATCH /api/controls/{id}` zapisuje do `policy.yaml` (jedno źródło prawdy)?
