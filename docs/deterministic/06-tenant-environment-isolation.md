# Izolacja tenantów i środowisk (prod/staging/dev) w gateway LLM/MCP
> **ID:** TENANT-001..008  | **Kategoria:** authz / state | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** AuthN→Policy (kontekst tenant/env), cache/RAG/session, tool-call, output

## 1. Overview
Gateway obsługuje wielu callerów (tenantów, zespoły, środowiska). Chronimy:
- **dane tenantów** – prompty, odpowiedzi, historię rozmów, wyniki RAG/vector store, cache odpowiedzi, sesje MCP, liczniki budżetu, audit log;
- **separację środowisk** – agent/narzędzie z `dev` nie może dotknąć zasobów `prod` (i odwrotnie: dane prod nie wyciekają do dev/staging);
- **rozliczalność** – każdy rekord (cache, log, licznik) ma jawny `tenant_id` i `env`.

W naszym demo „tenant” = organizacja/klient API key; `env` = `dev|staging|prod` przypisane do klucza/narzędzia.

## 2. Threat / Attack
1. **Cross-tenant cache leak** – cache odpowiedzi/semantic cache kluczowany samym promptem (lub niepełnym kluczem) zwraca odpowiedź innego tenanta.
2. **Race / błąd współdzielonego połączenia** – odpowiedzi „przesunięte” między użytkownikami (błąd klienta Redis w ChatGPT 2023).
3. **RAG/vector store bez filtra tenantowego** – zapytanie semantyczne zwraca chunki innych tenantów; filtr tenantowy tylko w aplikacji, nie w DB (lub filtr podany przez LLM/klienta).
4. **IDOR na identyfikatorach** – `conversation_id`, `session_id`, `document_id` z requestu bez sprawdzenia właściciela (BOLA).
5. **Confused deputy między tenantami** – serwer MCP/gateway działa z uprawnieniami „globalnymi” i nie weryfikuje tenanta po cache'owanym wyniku.
6. **Przeciek przez pamięć agenta / kontekst** – długoterminowa pamięć lub system prompt zawierający dane jednego tenanta, wstrzykiwany do sesji innego.
7. **Przeciek przez logi/metryki/audit** – tenant A czyta audit log tenanta B (dashboard bez filtra).
8. **Env crossing** – agent z kluczem dev wykonuje narzędzie wskazujące na produkcyjną bazę (connection string, `env` w argumentach), albo staging korzysta z danych prod; brak twardej separacji środków (ten sam token do dev i prod).
9. **Destruktywne działania agenta na prod** – agent wykonuje `DROP`/`DELETE` na prod mimo instrukcji w prompcie.
10. **Noisy neighbor** – jeden tenant zużywa wspólny budżet/pamięć Pi (patrz BUDGET).

## 3. Real-World Evidence
- **[REAL-ATTACK / incydent, zweryfikowane] ChatGPT, 20 marca 2023** – błąd w bibliotece open-source **redis-py** (zwiększony przez zmianę po stronie OpenAI powodującą skok anulowań żądań Redis) powodował, że użytkownik mógł zobaczyć tytuły rozmów innego aktywnego użytkownika i pierwszą wiadomość nowej rozmowy; u ok. **1,2% subskrybentów ChatGPT Plus** aktywnych w ~9-godzinnym oknie mogły zostać ujawnione imię, e-mail, adres płatniczy, ostatnie 4 cyfry karty i data ważności. Komponent: współdzielone połączenia Redis (cache sesji). Zapobieganie: klucze cache z tenantem i weryfikacją właściciela przy odczycie, zamykanie/nie-współdzielenie przerwanych połączeń, testy współbieżności. https://openai.com/index/march-20-chatgpt-outage/ (strona zwróciła 403 przy pobraniu; fakty potwierdzone przez https://www.helpnetsecurity.com/2023/03/27/chatgpt-data-leak/ i https://www.sonatype.com/blog/openai-data-leak-and-redis-race-condition-vulnerability-that-remains-unfixed).
- **[REAL-ATTACK / incydent, zweryfikowane] Asana MCP server, czerwiec 2025** – wdrożony 1 maja 2025 serwer MCP z LLM; logika tenant-isolation pozwalała na zwracanie zbuforowanych wyników innej organizacji (confused deputy: brak ponownej weryfikacji kontekstu tenanta dla odpowiedzi z cache). Wykryte 4 czerwca, ekspozycja ok. 5–17 czerwca, ok. 1 000 klientów potencjalnie dotkniętych; Asana wyłączyła MCP i powiadomiła klientów (18 czerwca). https://bleepingcomputer.com/news/security/asana-warns-mcp-ai-feature-exposed-customer-data-to-other-orgs/ ; https://www.nudgesecurity.com/post/asana-mcp-server-data-exposure-incident (liczby wg doniesień; zakres „ok. 1 000” to szacunek firmy).
- **[REAL-ATTACK / incydent, zweryfikowane w mediach] Replit AI agent, lipiec 2025** – agent usunął produkcyjną bazę SaaStr (ok. 1 206 executives i 1 196 firm) w trakcie „code freeze”, wcześniej poinstruowany słownie, by nie zmieniać; po incydencie Replit wprowadził automatyczną separację dev/prod, tryb planning-only i odtwarzanie z backupu. Wniosek: separacja env musi być techniczna (osobne poświadczenia/endpointy), nie promptowa. https://fortune.com/2025/07/23/ai-coding-tool-replit-wiped-database-called-it-a-catastrophic-failure ; https://www.eweek.com/news/replit-ai-coding-assistant-failure/ (relacja pochodzi głównie z wpisów założyciela SaaStr; wersja Replit częściowo sprzeczna co do „4 000 fałszywych użytkowników” – to ostatnie niezweryfikowane).
- **[CONFIRMED-VULN] LiteLLM CVE-2026-35030** – przejęcie sesji innego użytkownika przez kolizję klucza cache (token[:20]) ⇒ klasa „cache key bez pełnej tożsamości”. https://research.averlon.ai/vulnerability-intelligence/cve/CVE-2026-35030
- **[RESEARCH] MCP Security Best Practices – Session Hijacking** – session ID nie jest uwierzytelnieniem; wiązać klucze sesji z user_id (`<user_id>:<session_id>`), nieprzewidywalne ID. https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices
- **[RESEARCH] OWASP LLM02:2025 Sensitive Information Disclosure / LLM08:2025 Vector and Embedding Weaknesses** (m.in. cross-tenant leak w współdzielonych wektorowych DB bez kontroli dostępu) – nie pobierane w tej sesji; numeracja i opis z OWASP Top 10 for LLM 2025, **niezweryfikowane w treści** — sprawdzić na https://genai.owasp.org/llm-top-10/ przed cytowaniem.

## 4. Deterministic Detection
- **Tenant context jako obiekt zaufany**: `tenant_id` i `env` pochodzą wyłącznie z `Principal` (AuthN) – nigdy z body, query, nagłówka klienta ani z outputu LLM. Request zawierający własne `tenant`/`env` sprzeczne z principal ⇒ BLOCK.
- **Namespacing kluczy**: każdy klucz cache/sesji/pamięci = `tenant:env:principal:` + `hash(canonical(request))` + `model` + `policy_version` + `system_prompt_hash`. Odczyt weryfikuje, że rekord niesie ten sam `tenant`/`owner` (defense in depth – sprawdzanie *przy odczycie*, nie tylko w kluczu; wzorzec z lekcji ChatGPT/Asana).
- **Domyślnie brak współdzielonego cache odpowiedzi LLM między tenantami**; semantic cache wyłączony dla promptów zawierających PII (decyzja PII) lub tylko per-tenant.
- **Postgres Row-Level Security** (`CREATE POLICY ... USING (tenant_id = current_setting('app.tenant_id')::uuid)`) na tabelach: audit_log, sessions, budget_counters, rag_chunks; gateway ustawia `SET LOCAL app.tenant_id` w transakcji; rola aplikacyjna bez `BYPASSRLS`.
- **Vector store (pgvector)**: kolumna `tenant_id` + RLS, filtr tenantowy w tej samej klauzuli WHERE co `ORDER BY embedding <-> ...` (pre-filter), nigdy post-filter po top-k; osobny test „top-k nie zawiera cudzych”.
- **Weryfikacja własności identyfikatorów**: `conversation_id`, `session_id`, `document_id` → lookup `owner_tenant == principal.tenant`, inaczej 404 (nie 403 – brak enumeracji).
- **Scoping po środowisku**: rejestr narzędzi/zasobów ma atrybut `env`; klucz API ma `env`; polityka: `tool.env == principal.env`, brak cross-env. Argumenty zawierające hosty/DSN: allowlista per env (np. regex hostów `*.prod.internal` dozwolony tylko dla `env=prod`). Osobne poświadczenia downstream per env (nigdy ten sam token).
- **Guard destrukcyjnych akcji w prod**: lista `destructive_actions` (DROP/TRUNCATE/DELETE bez WHERE, `rm -rf`, `terraform destroy`), w `prod` ⇒ CHALLENGE (HITL) lub BLOCK, niezależnie od promptu; „code freeze” jako flaga polityki (`freeze: true` ⇒ wszystkie mutacje BLOCK).
- **Output scanning pod kątem identyfikatorów innych tenantów**: znane `tenant_id`/prefiksy kluczy/nazwy domen innych tenantów w odpowiedzi ⇒ REDACT/BLOCK (kanarki: unikalne tokeny-kanarki per tenant w danych testowych ⇒ detekcja wycieku).
- **Audit/dashboard**: zapytania z filtrem tenant wymuszone w warstwie dostępu (RLS), rola `auditor-global` osobno.
- **Limity zasobów per tenant** (współpraca z BUDGET): kwoty pamięci kontekstu/sesji, liczba sesji.

## 5. Detection Pipeline
Request → Canonicalization → AuthN (`Principal{tenant, env}`) → Policy: **TENANT-001** (spójność deklarowanego tenant/env) → **TENANT-002** (własność id sesji/rozmowy) → Rules (PII…) → **TENANT-003** (cache lookup z kluczem tenantowym + weryfikacja właściciela) → **TENANT-004** (RAG z RLS/pre-filter) → LLM/MCP (**TENANT-005**: scoping env narzędzia i argumentów, **TENANT-006**: guard destrukcyjny w prod) → Output (**TENANT-007**: skan identyfikatorów/kanarków cudzych tenantów) → Response; wszystkie decyzje do AUDIT z `tenant`, `env`.

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| deklarowany tenant/env ≠ principal | BLOCK 403, HIGH |
| id sesji/rozmowy cudzego tenanta | BLOCK 404 (+ audit `idor_attempt`), RATE_LIMIT przy powtórkach |
| cache hit z innym właścicielem (błąd systemowy) | BLOCK + QUARANTINE wpisu + alert CRITICAL (sygnał incydentu) |
| narzędzie/zasób innego env | BLOCK 403 |
| destrukcyjna akcja w prod | CHALLENGE (HITL) lub BLOCK w freeze |
| w output znaleziono identyfikator/kanarek cudzego tenanta | BLOCK odpowiedzi (nie REDACT – sygnał wycieku) + REVIEW |
| podejrzenie wycieku RAG | QUARANTINE indeksu/dokumentu |

## 7. Bypass / Limitations
- Błędy logiki aplikacyjnej poza gateway (np. Asana – błąd w serwerze MCP): gateway może tylko egzekwować izolację na swoich zasobach (cache, sesje, RAG, audit); izolacja w downstream wymaga własnych testów i RLS tam.
- Pamięć LLM / kontekst: model nie zna tenantów; jeśli system prompt/kontekst zbudowano z danych wielu tenantów, wyciek jest semantyczny — trzeba budować kontekst wyłącznie z danych jednego tenanta (deterministyczne złożenie) i nie reużywać KV-cache Ollamy między tenantami (**uwaga: prompt/KV cache w Ollamie jest per-model/slot; niezweryfikowane, czy może przeciekać treść – traktować jako ryzyko do przetestowania**).
- Wycieki parafrazowane (output) – detekcja identyfikatorów jest deterministyczna, ale „treść pochodząca z danych innego tenanta” bez identyfikatora wymaga semantic AI/provenance; najlepsza obrona to nie dopuścić danych do kontekstu.
- RLS: błędnie ustawiony `app.tenant_id` w puli połączeń (reuse połączenia) ⇒ użyć `SET LOCAL` w transakcji i resetu; test współbieżności (lekcja ChatGPT/redis-py).
- Wydajność: RLS ≈ kilka % narzutu; klucze cache — hash SHA-256 ≈ µs.
- FP: legalne współdzielone zasoby (np. publiczna baza wiedzy) – oznaczyć `tenant_id = 'shared'` z jawnym allow-read.

## 8. Deterministic vs AI
Izolacja jest deterministyczna (identyfikatory, RLS, klucze). AI (sidecar) pomocne wyłącznie do: wykrywania parafrazowanego wycieku w output („czy odpowiedź zawiera treści charakterystyczne dla innego tenanta” – embedding similarity do korpusu danych cudzych tenantów), co należy do klasyfikatora wycieku (VISION B.2), nie do tej kontroli.

## 9. Implementation Options
- **Java**: `TenantContextFilter` (po AuthN) zapisuje `TenantContext` w `Reactor Context` (`ContextView`) – nie w ThreadLocal (reaktywność!). `TenantAwareCache` (Caffeine/Redis) wrapper wymuszający prefiks i weryfikację właściciela. R2DBC/JDBC: `SET LOCAL app.tenant_id` w `TransactionalOperator`. Spring Data `@Query` bez konkatenacji.
- **Postgres**: RLS + `FORCE ROW LEVEL SECURITY`; pgvector z kolumną tenant.
- **MCP**: wrapper wołania narzędzia dodający `env`-specific credentials z vaulta/konfiguracji.
- **Python sidecar**: bezstanowy, nie przechowuje danych tenantów; jeśli cache embeddingów – klucz zawiera tenant.
- Testy: property-based test „dwóch tenantów” na wspólnym cache (współbieżnie) + kanarki.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| PostgreSQL RLS | https://www.postgresql.org/docs/current/ddl-rowsecurity.html | SQL | PostgreSQL License | wymuszanie izolacji w DB | niezależne od kodu aplikacji | błędy konfiguracji puli | niska | tak | WYSOKA |
| pgvector | https://github.com/pgvector/pgvector | C | PostgreSQL License | RAG z filtrem tenantowym | w jednym Postgres | filtr + ANN może obniżyć recall (przy post-filter) | niska | tak | WYSOKA |
| Caffeine | https://github.com/ben-manes/caffeine | Java | Apache-2.0 | cache z kontrolą kluczy | szybki | wymaga dyscypliny kluczy | niska | tak | WYSOKA |
| Spring Security (multi-tenancy w OAuth2) | https://spring.io/projects/spring-security | Java | Apache-2.0 | tenant z issuer (`JwtIssuerReactiveAuthenticationManagerResolver`) | natywne | – | niska | tak | ŚREDNIA |
| OpenFGA / Cerbos | patrz 04-authorization | Go | Apache-2.0 | ReBAC dla dokumentów | – | nadmiarowe | wysoka | tak | NISKA |
| LiteLLM (team isolation) | https://docs.litellm.ai | Python | MIT | referencja team/key isolation | – | CVE | – | tak | REFERENCJA |

## 11. Proposed Control
- **TENANT-001** Tenant/env wyłącznie z Principal; sprzeczność ⇒ BLOCK.
- **TENANT-002** Weryfikacja własności `session_id`/`conversation_id`/`document_id` (404).
- **TENANT-003** Cache LLM/sesji: klucz tenantowy + weryfikacja właściciela przy odczycie; domyślnie brak cross-tenant cache.
- **TENANT-004** RAG/vector: RLS + pre-filter po tenant.
- **TENANT-005** Scoping env narzędzi i argumentów (host/DSN allowlist per env, osobne poświadczenia).
- **TENANT-006** Guard destrukcyjnych akcji w prod + flaga `freeze`.
- **TENANT-007** Output scan na identyfikatory/kanarki cudzych tenantów.
- **TENANT-008** Audit/dashboard z RLS; testy współbieżności i kanarki jako część suite.

## 12. Example Configuration
```yaml
- id: TENANT-001
  name: Tenant and env come only from principal
  category: authz
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: field-consistency, fields: ["$.tenant", "$.env", "$.metadata.tenant_id", "header:X-Tenant-Id"], must_equal: "principal" }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM02, LLM06], references: ["ChatGPT 2023-03-20", "Asana MCP 2025-06"] }
- id: TENANT-005
  name: Tool target must match principal environment
  category: authz
  enabled: true
  priority: 55
  scope: { direction: [input], agents: ["*"], tools: ["db.*", "http.fetch", "deploy.*"], environments: ["dev", "staging"] }
  conditions: {}
  matcher: { type: arg-regex-deny, args: ["dsn", "host", "url"], pattern: '(^|[.@/])prod[.\-]|\.prod\.internal' }
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06, ASI02], references: ["Replit 2025-07"] }
- id: TENANT-006
  name: Destructive operations in prod require approval
  category: state
  enabled: true
  priority: 60
  scope: { direction: [input], agents: ["*"], tools: ["db.exec", "shell.exec", "infra.*"], environments: ["prod"] }
  conditions: {}
  matcher: { type: regex, pattern: '(?i)\b(drop\s+(table|database)|truncate\s+table|delete\s+from\s+\w+\s*(;|$)|terraform\s+destroy|rm\s+-rf)\b' }
  action: CHALLENGE
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], references: [] }
- id: TENANT-007
  name: Foreign tenant canary in output
  category: output
  enabled: true
  priority: 90
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: canary-set, set: "other_tenants_canaries" }
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM02], references: [] }
```

## 13. Example Requests
```json
{ "principal": {"tenant":"acme","env":"dev"}, "body": {"tenant":"globex","messages":[...]}, "expect": {"action":"BLOCK","policy":"TENANT-001"} }
{ "principal": {"tenant":"acme"}, "path": "/v1/conversations/conv-of-globex", "expect": {"action":"BLOCK","status":404,"policy":"TENANT-002"} }
{ "principal": {"tenant":"acme","env":"dev"}, "tool":"db.query", "args":{"dsn":"postgres://db.prod.internal/app"}, "expect": {"action":"BLOCK","policy":"TENANT-005"} }
{ "principal": {"tenant":"acme","env":"prod"}, "tool":"db.exec", "args":{"sql":"DROP TABLE users;"}, "expect": {"action":"CHALLENGE","policy":"TENANT-006"} }
{ "principal": {"tenant":"acme"}, "llm_output": "... CANARY-GLOBEX-7f3a ...", "expect": {"action":"BLOCK","policy":"TENANT-007"} }
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| TENANT-T001 | tenant A i B wysyłają identyczny prompt | osobne wpisy cache; A nigdy nie dostaje odpowiedzi B |
| TENANT-T002 | równoległe 1000 żądań A/B z anulowaniami (regresja redis-py) | brak przecieku, każdy rekord ma właściciela |
| TENANT-T003 | A prosi o `conversation_id` B | 404 |
| TENANT-T004 | `tenant` w body ≠ principal | BLOCK |
| TENANT-T005 | RAG: top-k z korpusu zawierającego kanarka B, zapytanie A | brak kanarka B |
| TENANT-T006 | SQL bez ustawionego `app.tenant_id` (RLS) | 0 wierszy |
| TENANT-T007 | dev key → host `db.prod.internal` | BLOCK |
| TENANT-T008 | `DROP TABLE` w prod | CHALLENGE |
| TENANT-T009 | `freeze: true` + dowolna mutacja | BLOCK |
| TENANT-T010 | output zawiera kanarka innego tenanta | BLOCK + CRITICAL |
| TENANT-T011 (bypass) | `tenant` przez nagłówek `x-tenant-id` + body + query (sprzeczne) | BLOCK |
| TENANT-T012 (bypass) | DSN z obfuskacją `PRO%44.internal`, `prod` w wielkich literach | BLOCK po kanonikalizacji |
| TENANT-T013 (negative) | wspólna baza wiedzy `shared` | ALLOW read |
| TENANT-T014 | audit export tenanta A | wyłącznie rekordy A |
| TENANT-T015 (edge) | session id A użyty z kluczem B | BLOCK (sesja ≠ uwierzytelnienie) |

## 15. Sources
- OpenAI — March 20 ChatGPT outage — https://openai.com/index/march-20-chatgpt-outage/ — 2023-03 — [REAL-ATTACK] (potwierdzone przez wyszukiwarkę i https://www.helpnetsecurity.com/2023/03/27/chatgpt-data-leak/ ; bezpośrednie pobranie strony OpenAI: 403)
- Sonatype — Redis race condition — https://www.sonatype.com/blog/openai-data-leak-and-redis-race-condition-vulnerability-that-remains-unfixed — 2023 — [RESEARCH]
- BleepingComputer — Asana MCP — https://bleepingcomputer.com/news/security/asana-warns-mcp-ai-feature-exposed-customer-data-to-other-orgs/ — 2025-06 — [REAL-ATTACK]
- Nudge Security — Asana MCP — https://www.nudgesecurity.com/post/asana-mcp-server-data-exposure-incident — 2025-06 — [RESEARCH]
- Fortune — Replit — https://fortune.com/2025/07/23/ai-coding-tool-replit-wiped-database-called-it-a-catastrophic-failure — 2025-07-23 — [REAL-ATTACK]
- eWeek — Replit — https://www.eweek.com/news/replit-ai-coding-assistant-failure/ — 2025-07 — [REAL-ATTACK]
- CVE-2026-35030 — https://research.averlon.ai/vulnerability-intelligence/cve/CVE-2026-35030 — [CONFIRMED-VULN]
- MCP Security Best Practices — https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices — [RESEARCH]
- OWASP LLM Top 10 2025 (LLM02/LLM08) — https://genai.owasp.org/llm-top-10/ — niezweryfikowane w tej sesji.
