# Architektura deterministic engine

Zbiorcza propozycja wynikająca z case files 01–27. Zgodna z `VISION.md` (Spring Cloud Gateway + sidecar FastAPI + Postgres).
Kontrole = osobne, testowalne `GatewayFilterFactory`; reguły = dane (YAML/Postgres), hot reload bez restartu.

## 1. Pipeline

```
Request
  ↓
[0] Edge limits            body size, Content-Encoding reject, read timeout, bulkhead     (LIMIT 18)
  ↓
[1] Canonicalization       strict UTF-8, NFKC, Unicode Tags/zero-width/bidi, bounded decode (CANON 21)
  ↓                         → widoki: canonical, decoded, hidden_ascii
[2] Authentication         API key / JWT, strip nagłówków tożsamości od klienta             (AUTHN 03)
  ↓
[3] Policy Resolution      principal + tenant + env + model → zestaw reguł (scope)          (AUTHZ 04, TENANT 06)
  ↓
[4] Deterministic Rules    kolejność wg priority:
  ↓                          a. AuthZ/model/tool allowlist (05, 07)
  ↓                          b. rate / budget / concurrency (13, 14)  → rezerwacja
  ↓                          c. data: PII/SEC (01, 02) · signatures (22, 26) · deser (23)
  ↓                          d. tool-call: schema (08), path (09), URL (10), cmd (11), integrity (12)
  ↓                          e. session: chain/loop/sequence (15–17)
  ↓                          f. opcjonalnie → Sidecar semantyczny (sygnał, nie bramka)
[5] Decision               agregacja wyników (deny-overrides), patrz decision-model.md
  ↓
LLM (Ollama @ Pi) / Agent / MCP server
  ↓
[6] Output Controls        streaming hold-back: PII/SEC (19), markdown/URL exfil (20), schema, canary
  ↓
[7] Decision               REDACT / BLOCK / QUARANTINE
  ↓
Response  (+ rozliczenie budżetu z usage Ollamy, korekta rezerwacji)
```
Audit i telemetria pojawiają się na każdym kroku (§6).

## 2. Komponenty

| Komponent | Rola | Technologia | Case |
|---|---|---|---|
| Canonicalizer | Jedno wejście dla wszystkich matcherów; reguły nigdy nie widzą surowego tekstu | Java (ICU4J, java.text.Normalizer) | 21 |
| Principal resolver | Tożsamość user/agent/tenant z tokenu, nigdy z body | Spring Security (reactive) | 03 |
| Policy resolver | Wybór reguł po `scope`; deny-by-default | własny ewaluator YAML (jCasbin jako plan B) | 04 |
| Rule engine | Rejestr matcherów (`regex`, `validator`, `schema`, `ast`, `counter`, `dfa`), deny-overrides | Java, RE2J | rule-model.md |
| Matcher library | Walidatory (Luhn, PESEL, mod-97), Aho-Corasick, JSON Schema | RE2J, networknt, własne | 01,02,08 |
| Session state store | Etykiety taint, historia hashy wywołań, liczniki chain/depth | In-memory (Caffeine) + zapis w Postgres | 15–17 |
| Counters / budgets | Atomowy `UPDATE … WHERE used+n <= limit RETURNING`; Bucket4j dla RPM | Postgres + Bucket4j | 13,14 |
| Threat intel | Feed sygnatur/IOC/wersji, snapshot offline, wersjonowanie | plik/endpoint + watcher | 22 |
| Sidecar client | Timeouty, circuit breaker; błąd sidecara ≠ otwarcie bramki | WebClient + Resilience4j | 26 |
| Audit sink | Append-only, HMAC chain, redakcja przed zapisem | Postgres | 27 |
| Telemetry | Metryki per reguła/decyzja, latencje p50/p95/p99, OTel GenAI | Micrometer | 27 |
| Config manager | Walidacja → atomowa podmiana snapshotu reguł | Postgres + LISTEN/NOTIFY lub file watch | §5 |

## 3. Zasady projektowe (z researchu)

1. **Canonicalize first.** Każdy regex działa na widokach z CANON; bez tego char-injection obchodzi guardraile do ~100% (arXiv 2504.11168).
2. **Allowlist > denylist** dla komend, rejestrów, importów pickle, narzędzi MCP (CMD, MODEL-SC, MCP-ALLOW).
3. **Deny-by-default i deny-overrides.** Nieznane narzędzie traktowane jak egress (SEQ).
4. **Tożsamość i klucze limitów z principala po AuthN**, nigdy z danych klienta; `X-Forwarded-*` tylko od zaufanych proxy (CVE-2025-41235 w SCG).
5. **Liczniki atomowo w bazie**, rezerwacja worst-case przed wywołaniem, korekta po odpowiedzi (BUDGET).
6. **Fail-mode jawny per reguła:** koszty/bezpieczeństwo → fail-closed; RPS → fail-open z lokalnym fallbackiem.
7. **Sidecar = sygnał.** Twarda bramka zawsze deterministyczna (PI, MCP-INT).
8. **Streaming:** hold-back buffer ≥ najdłuższy wzorzec CRITICAL (OUT-001); wysłanego tekstu nie cofniesz.
9. **Raspberry Pi:** bulkhead + krótka kolejka (2–4) + 429/503 z `Retry-After`; gateway ustawia `num_predict`/`num_ctx` (LIMIT, BUDGET). Ollama dostępna tylko przez gateway; `/api/pull|create|push|copy|delete` zablokowane (MODEL, MODEL-SC).
10. **Audit bez surowych danych:** HMAC z kluczem serwera, nie gołe SHA-256 (przestrzeń PESEL jest mała).

## 4. Dwie ścieżki

- **Chat/LLM path** (`/v1/chat/completions`, `/api/chat`): kroki 0–7.
- **Tool-call/MCP path** (`tools/call`, `resources/read`, `tools/list`): dodatkowo schema, ścieżki, URL, komendy, integralność definicji (pinning hash), sekwencja sesji.
`tools/list` jest kontrolowane przy każdej zmianie (hash vs pin → QUARANTINE).

## 5. Hot reload konfiguracji

1. Nowa wersja polityki zapisana w Postgres (`policy_versions`, niemutowalna) lub plik YAML.
2. Walidacja: schemat reguły, kompilacja regexów RE2J (budżet złożoności), dry-run na zestawie testów (test-catalog).
3. Atomowa podmiana referencji `volatile RuleSnapshot`; trwające requesty kończą na starym snapshotcie.
4. Błędna konfiguracja → odrzucona, stary snapshot zostaje, alert w audit (AUTHZ-008: fail-closed na błędnej polityce).
5. Każda decyzja w audit niesie `policy_version`.

## 6. Audit i telemetria

Wpis: `ts, trace_id, caller_id, tenant, policy_version, stage, rule_id, decision, severity, latency_ms, hmac(fragment)`.
Hash chain (HMAC poprzedniego wpisu), sanityzacja pól przeciw log injection, eksport CSV/JSON. Dashboard: blokady/redakcje,
zużycie budżetu %, trafienia per reguła, percentyle latencji (wymogi `VISION.md` §5).

## 7. Budżet opóźnień (cel)

Deterministyczna ścieżka <10 ms p95 poza streamingiem (regexy RE2J liniowe, walidatory O(n)); dodatkowa latencja hold-back ≈ 1,5–2,5 s przy 10–15 tok/s na Pi (szacunek, nie pomiar — zmierzyć w LIMIT-T/OUT testach). Sidecar wołany warunkowo (tylko gdy score graniczny), z twardym timeoutem.

## 8. Mapowanie na Spring Cloud Gateway

**Stan faktyczny (zastępuje wcześniejszą propozycję `GatewayFilterFactory`):** `/v1/chat/completions` to zwykły kontroler WebFlux
(patrz `VISION.md` §7), a każda kontrola to bean `Guard` (pakiet `pl.hackyeah.controllayer.guard`) spięty w `GuardChain`
i wołany z kontrolera na etapach `INPUT` / `OUTPUT` (później `TOOL_CALL`). Włączanie i parametry: `control-layer.guards` w `application.yml`.
Jak dopisać regułę: [how-to-write-a-rule.md](how-to-write-a-rule.md). Uproszczenia względem [rule-model.md](rule-model.md):
reguła to klasa Javy, nie dane z `matcher.type`; brak jeszcze `mode`, `scope`, `exceptions`, hot-reloadu snapshotu, decyzji RATE_LIMIT/QUARANTINE.
Filtry output w streamingu mogą używać dekoratora `ServerHttpResponse`. Rate limiter: własny mechanizm z Bucket4j (wbudowany `RequestRateLimiter` wymaga Redis i nie liczy tokenów).
