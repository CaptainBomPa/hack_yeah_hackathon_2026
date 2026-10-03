# Deterministyczny audit log: format, niezmienność, korelacja, eksport, log injection
> **ID:** AUDIT-001..009  | **Kategoria:** output / governance (cross-cutting) | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** każdy etap pipeline (decyzja każdej kontroli) + Response (zapis końcowy) + eksport

## 1. Overview
Kryterium „Security Reporting” (20%) wymaga audytu: timestamp, caller id, polityka, akcja, **hash zredagowanego fragmentu (nigdy surowe PII)**, eksport CSV/JSON (VISION §5). Audit log to dowód, że kontrole zadziałały, źródło dashboardu i materiał do analizy incydentów. Musi być: kompletny (każda decyzja, także ALLOW), bezpieczny (bez PII/sekretów), odporny na manipulację (tamper-evident), skorelowany (trace id przez gateway/sidecar/MCP) i odporny na log injection.

## 2. Threat / Attack
1. **PII/sekrety w logach** – logowanie surowych promptów/odpowiedzi/argumentów tworzy drugi, mniej chroniony magazyn danych wrażliwych (także w OTel spans/trace'ach).
2. **Brute-forceable hash** – SHA-256 z PESEL/telefonu/e-maila jest odwracalny słownikowo (mała przestrzeń wartości) ⇒ „hash zamiast PII” bez sekretu nie jest anonimizacją.
3. **Log injection / forging (CWE-117)** – atakujący umieszcza w prompcie/nagłówku/polu `\r\n`, fałszywą linię logu (`... action=ALLOW`), sekwencje ANSI/Unicode bidi, wstrzyknięcie do CSV (formula injection `=cmd|...`) lub do HTML dashboardu (stored XSS).
4. **Manipulacja/usunięcie logu** – insider lub atakujący po przejęciu bazy usuwa/edytuje wpisy (ukrycie śladów); brak wykrywalności.
5. **Brak logowania decyzji ALLOW / błędów kontroli** – luka dowodowa; „fail-open” kontroli nie jest widoczny.
6. **Rozjazd czasu** – brak monotonicznej kolejności (Pi bez NTP), utrudniona korelacja.
7. **Przepełnienie / DoS logiem** – atakujący generuje ogromne wpisy (duże pola) lub lawinę zdarzeń ⇒ pełny dysk ⇒ kontrola degraduje się (AU-5: reakcja na awarię audytu).
8. **Wyciek przez eksport/dashboard** – eksport bez RLS/filtra tenantowego (patrz TENANT).
9. **Niska rozliczalność w delegacji** – log nie pokazuje usera i agenta (patrz AUTHN).

## 3. Real-World Evidence
- **[RESEARCH] CWE-117 Improper Output Neutralization for Logs / OWASP Logging** – nieneutralizowane dane z zewnątrz pozwalają fałszować wpisy lub ukrywać prawdziwe przez wstrzyknięcie CR/LF; zalecenie: neutralizacja/enkodowanie przed zapisem. https://best.openssf.org/Secure-Coding-Guide-for-Python/CWE-707/CWE-117 ; CodeQL (Java): https://codeql.github.com/codeql-query-help/java/java-log-injection
- **[RESEARCH] NIST SP 800-92 (Guide to Computer Security Log Management)** – planowanie i zabezpieczanie logów; integralność przez hash/podpisy, kontrola dostępu, separacja obowiązków. https://csrc.nist.gov/publications/detail/sp/800-92/final
- **[RESEARCH] NIST SP 800-53 Rev.5 rodzina AU** – AU-9 (ochrona informacji audytowej przed nieautoryzowanym dostępem, modyfikacją i usunięciem + alert), AU-10 (non-repudiation), AU-11 (retencja), AU-3 (zawartość rekordu), AU-5 (reakcja na awarię audytu), AU-8 (znaczniki czasu), AU-12 (generowanie). Katalog: https://csrc.nist.gov/projects/cprt/catalog (podsumowania z wtórnych źródeł wyszukiwarki, np. https://coralogix.com/guides/nist-sp-800-53-audit-logging-au-controls/ ; numery kontroli niezweryfikowane bezpośrednio w katalogu NIST w tej sesji).
- **[RESEARCH] OpenTelemetry GenAI semantic conventions** – przeniesione do osobnego repo `open-telemetry/semantic-conventions-genai` (spany/metryki/zdarzenia dla klientów GenAI i MCP); atrybuty m.in. `gen_ai.operation.name`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`/`output_tokens`; treść wiadomości (`gen_ai.input.messages`/`output.messages`) jest **opt-in** z powodów prywatności; status: rozwojowy/eksperymentalny. https://github.com/open-telemetry/semantic-conventions-genai (szczegóły atrybutów poza listą nazw: niezweryfikowane — strona docs została zastąpiona przekierowaniem).
- **[CONFIRMED-VULN] LiteLLM CVE-2025-0330 – wyciek kluczy Langfuse** (v1.52.1): błąd przy parsowaniu ustawień zespołu wyciekał `langfuse_secret`/`langfuse_public_key` w błędzie – dostęp do projektu przechowującego wszystkie żądania. Lekcja: system observability = skarbiec danych; sekrety i treści nie mogą przeciekać przez komunikaty błędów. https://osv.dev/vulnerability/CVE-2025-0330
- **[CONFIRMED-VULN] LiteLLM CVE-2024-5225 – SQL injection w `/global/spend/logs`** (parametr `api_key` konkatenowany do zapytania) – endpoint raportowy/audytowy jako wektor ataku; filtrowanie w eksporcie musi być parametryzowane. https://vulnerability.cert.dk/vuln/ghsa-h6m6-jj8v-94jj (numer CVE wg agregatora wyszukiwarki; zweryfikować w NVD).
- **[REAL-ATTACK] Replit 2025** – agent „ukrywał” działania i fałszował stan; niezależny, niemodyfikowalny log wywołań narzędzi (nie z perspektywy agenta) jest warunkiem wykrycia. https://www.eweek.com/news/replit-ai-coding-assistant-failure/
- **[RESEARCH] MCP Security Best Practices** – token passthrough psuje audit trail (downstream widzi inną tożsamość); „log elevation events (scope requested, granted) with correlation IDs”. https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices

## 4. Deterministic Detection (jak budować i chronić log)
**Schemat rekordu (JSON, jeden na decyzję kontroli + jeden agregujący na request):**
```json
{
  "event_id": "uuidv7",
  "ts": "2026-10-03T12:00:00.123Z",
  "seq": 18422,
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "request_id": "...",
  "tenant": "acme", "env": "dev",
  "principal": {"subject": "user-17", "actor": "agent-7", "auth_method": "api_key", "key_id": "ak_demo"},
  "stage": "input|output|tool-call",
  "rule_id": "PII-001", "policy_version": 42, "decision": "REDACT",
  "severity": "HIGH", "reason_code": "pesel_checksum_ok",
  "match": { "type": "pesel", "count": 1, "fragment_hmac": "hmac-sha256:key2:9f2c…", "offset": [12, 23] },
  "model": "qwen2.5-1.5b", "tool": null,
  "usage": {"input_tokens": 124, "output_tokens": 0},
  "latency_ms": 3,
  "prev_hash": "…", "record_hash": "…", "hash_key_id": "k1"
}
```
- **Zredagowany fragment**: `fragment_hmac = HMAC-SHA256(secret_pepper, tenant || type || normalized_value)` (nie goły SHA-256 – patrz zagrożenie 2); pozwala korelować „ten sam PESEL pojawił się 5 razy” bez ujawniania wartości; pepper w configu/vault, rotacja z `key_id`. Opcjonalnie tylko `type`, `count`, `offset` i **maskowana** forma (`***-**-**-123`) dla UI. Nigdy surowa wartość, nigdy pełny prompt (opcjonalnie tryb debug: pełna treść szyfrowana kluczem admina, krótka retencja, domyślnie OFF).
- **Hash chain / HMAC**: `record_hash = HMAC-SHA256(audit_key, prev_hash || canonical_json(record_without_hash))` (kanoniczny JSON: posortowane klucze, UTF-8, bez spacji – RFC 8785 JCS). Weryfikator offline `verify-audit` przechodzi łańcuch i wskazuje pierwszy uszkodzony `seq`. Okresowe **kotwiczenie**: co N rekordów/godzinę zapis `record_hash` do zewnętrznego miejsca (plik tylko-do-dopisywania, e-mail, wydruk w dashboardzie, git-commit) – bo atakujący z kluczem może przeliczyć cały łańcuch. Alternatywa: podpis Ed25519 (klucz prywatny poza bazą) – wspiera non-repudiation (AU-10).
- **Niezmienność w Postgres**: rola aplikacyjna tylko `INSERT`/`SELECT` (brak `UPDATE`/`DELETE`/`TRUNCATE`), trigger `BEFORE UPDATE OR DELETE` rzucający wyjątek, partycjonowanie po czasie (retencja = `DROP PARTITION` wykonywane osobną rolą z audytem), opcjonalnie replika/dodatkowy sink (plik JSONL append-only `chattr +a` na Linuksie).
- **Log injection**: nigdy konkatenacja do linii tekstowej – zapis jako **strukturalny JSON** (serializer eskejpuje `\r\n`, cudzysłowy), pola od klienta przechodzą przez `sanitize()`: usunięcie/escape znaków kontrolnych (U+0000–001F, 007F–009F), ANSI `\x1b[`, Unicode bidi (U+202A–202E, U+2066–2069), normalizacja NFC, limit długości (np. 256 znaków/pole) z flagą `truncated:true`. Eksport CSV: prefiks `'` dla komórek zaczynających się od `=`, `+`, `-`, `@`, tab, CR (CSV/formula injection). Dashboard: escape HTML (React domyślnie) + CSP.
- **Wartości kontrolowane vs niekontrolowane**: `rule_id`, `decision`, `stage` pochodzą z enumów (nie z danych użytkownika); `reason_code` enum; swobodny tekst tylko w polach oznaczonych `untrusted_*`.
- **Kompletność**: log każdej decyzji (także ALLOW — z krótką formą), błędów kontroli (`control_error`, fail-closed/fail-open jawnie), zmian polityk (kto, diff hash, wersja), logowań/odmów AuthN (bez sekretu), eksportów audytu (kto wyeksportował), startu/stopu i weryfikacji integralności.
- **Awaria audytu (AU-5)**: jeśli zapis audytu nie działa – tryb konfigurowalny: `fail_closed` (BLOCK nowych żądań, domyślnie dla prod) lub `buffer` (lokalny bufor do pliku + alert). Backpressure: asynchroniczny zapis przez kolejkę ograniczoną (Reactor `Sinks.many().unicast().onBackpressureBuffer(N)`) + licznik dropped.
- **Czas**: `ts` UTC ISO-8601 ms, dodatkowo monotoniczny `seq` per instancja; wymagać NTP/chrony na hostach (Pi!).
- **Korelacja**: W3C Trace Context (`traceparent`) – gateway generuje/propaguje `trace_id` do sidecara i MCP; w response `X-Trace-Id` (jury widzi, który wpis audytu odpowiada żądaniu).
- **OTel**: spany z `gen_ai.*` (nazwy modelu, provider, tokeny) + własne `ctrl.rule_id`, `ctrl.decision`; **treści nie eksportować do OTel** (opt-in off); atrybuty pod `ctrl.*` jako rozszerzenie; eksport OTLP do lokalnego collectora (offline).
- **Retencja**: polityka danych: np. 90 dni pełne rekordy, 1 rok zagregowane; retencja konfigurowalna w YAML; usuwanie tylko przez partycje + wpis audytowy.

## 5. Detection Pipeline
Request (generuj `trace_id`, `request_id`) → po każdej kontroli: `AuditEmitter.emit(decision)` (async, bufor) → przy zakończeniu: rekord agregujący (decyzja końcowa, lista reguł, latencja, tokeny) → warstwa `AuditSink`: Postgres (append-only + hash chain) ∥ plik JSONL ∥ OTLP (bez treści) → API `/audit` (filtry, paginacja keyset, RLS) → eksport CSV/JSON (streaming) → dashboard (agregaty z widoków/materialized views). Job `verify-audit` (cron + endpoint) alarmuje o zerwaniu łańcucha.

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| wykryte `\r\n`/ANSI/bidi w polu logowanym | sanitize + flaga `sanitized:true` (ALLOW) + reguła zdarzenia INFO |
| zerwanie łańcucha hash | alert CRITICAL, QUARANTINE segmentu logu, REVIEW |
| awaria zapisu audytu | BLOCK (fail_closed) lub buffer + alert |
| eksport audytu | ALLOW tylko dla roli `auditor`, zdarzenie `audit.export`; RATE_LIMIT eksportów |
| próba UPDATE/DELETE na audit_log | błąd DB + alert CRITICAL |
| pole za duże (>limit) | TRUNCATE + `truncated:true` |
| surowe PII wykryte w rekordzie przed zapisem (self-check) | REDACT w locie + alert (bug) |

## 7. Bypass / Limitations
- Atakujący z kluczem HMAC i dostępem do DB może przepisać cały łańcuch ⇒ kotwiczenie zewnętrzne / podpis asymetryczny z kluczem poza hostem.
- HMAC fragmentu: przy wycieku pepper możliwy słownikowy odwrót małych przestrzeni (PESEL ma ~10^9 realnych kombinacji z checksumą) ⇒ pepper traktować jak sekret, rotować.
- Hash chain wykrywa modyfikację, nie zapobiega; usunięcie *końcówki* łańcucha jest wykrywalne tylko dzięki kotwicom i liczniku `seq`.
- Pełne logowanie ALLOW zwiększa wolumen (Pi/dysk) ⇒ próbkowanie ALLOW wyłącznie dla niskiego ryzyka, nigdy dla BLOCK/REDACT/RATE_LIMIT/CHALLENGE.
- Wydajność: HMAC-SHA256 ≈ µs; łańcuch wymaga serializacji zapisu (jeden writer / sekwencer) – wąskie gardło przy wielu instancjach (rozwiązanie: łańcuch per instancja/partycja).
- Treść promptu potrzebna do analizy incydentu vs prywatność – kompromis; domyślnie brak treści.
- FP sanitizacji: legalne znaki kontrolne w treści (tabulacje) – zamieniane na escape, bez utraty informacji o wystąpieniu.
- Dane z OTel/Langfuse-podobnych narzędzi mogą ominąć nasze maskowanie, jeśli ktoś włączy capture treści ⇒ maskowanie w gateway **przed** emisją.

## 8. Deterministic vs AI
Cały audit log jest deterministyczny. AI (sidecar) dostarcza wyłącznie pola wejściowe do rekordu (`semantic.score`, `label`, `model_version`) – zapisywane jako dane, z wersją modelu dla odtwarzalności. Analiza anomalii w logach (np. wykrywanie dziwnych sekwencji) – poza zakresem MVP.

## 9. Implementation Options
- **Java**: `AuditEvent` (record), `AuditEmitter` (Reactor sink), `PostgresAuditSink` (R2DBC batch insert, tabela partycjonowana), `HashChainer` (jedna instancja `Mono` sekwencer), `AuditSanitizer`, `AuditExportController` (streaming `Flux<DataBuffer>` CSV/NDJSON). Jackson + `JsonGenerator.Feature.ESCAPE_NON_ASCII` opcjonalnie; kanoniczny JSON: biblioteka JCS (`io.github.erdtman:java-json-canonicalization`, niezweryfikowane) lub własny `ORDER_MAP_ENTRIES_BY_KEYS`.
- **Logowanie aplikacyjne**: Logback z `logstash-logback-encoder` (JSON), maskowanie wzorców; osobny appender audit.
- **OTel**: Micrometer Tracing / OpenTelemetry Java agent; atrybuty `gen_ai.*` ręcznie lub przez instrumentację; Collector lokalny (OTLP → plik/Jaeger/Tempo).
- **Python sidecar**: ten sam `trace_id` z nagłówka `traceparent`; loguje tylko `label/score/latency`, nie treść.
- **Weryfikacja**: CLI `verify-audit.sh` (jq + openssl) – jury może uruchomić jednym poleceniem.
- Dashboard: widoki SQL (`count by rule_id, decision, hour`).

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| OpenTelemetry (SDK/Collector/Java agent) | https://opentelemetry.io | Java/Go | Apache-2.0 | trace_id, metryki, `gen_ai.*` | standard, korelacja | konwencje GenAI jeszcze rozwojowe | niska | tak (lokalny collector) | WYSOKA |
| logstash-logback-encoder | https://github.com/logfellow/logstash-logback-encoder | Java | Apache-2.0 | JSON logi strukturalne | eskejpowanie, MDC | – | niska | tak | WYSOKA |
| PostgreSQL (append-only, partycje, RLS) | https://www.postgresql.org | C | PostgreSQL License | magazyn audytu | wymuszanie uprawnień | – | niska | tak | WYSOKA |
| Trillian / Sigstore Rekor (transparency log) | https://github.com/google/trillian ; https://github.com/sigstore/rekor | Go | Apache-2.0 | publiczny/zewnętrzny Merkle log | silna niezmienność | nadmiarowe dla demo | wysoka | tak | NISKA (opcja) |
| immudb | https://github.com/codenotary/immudb | Go | Apache-2.0 (niezweryfikowane; część funkcji komercyjna) | niezmienna baza z dowodami | gotowe | dodatkowy komponent | średnia | tak | NISKA |
| Langfuse | https://github.com/langfuse/langfuse | TS | MIT (rdzeń; niezweryfikowane) | obserwowalność LLM | UI | przechowuje treści (ryzyko PII), historia CVE-0330 w proxy | średnia | tak (self-host) | REFERENCJA |
| OWASP Security Logging (ESAPI/ java-security-logging) | https://owasp.org | Java | niezweryfikowane | sanitizacja logów | – | niezweryfikowane | – | tak | NISKA |

## 11. Proposed Control
- **AUDIT-001** Rekord decyzji dla każdej kontroli (także ALLOW), schemat JSON jak w §4.
- **AUDIT-002** Brak surowego PII/sekretów: `fragment_hmac` (HMAC z pepper) + typ/count/offset; self-check detektorów na rekordzie przed zapisem.
- **AUDIT-003** Hash chain HMAC/Ed25519 + kotwiczenie + `verify-audit`.
- **AUDIT-004** Append-only w DB (uprawnienia + trigger + partycje).
- **AUDIT-005** Sanitizacja pól niezaufanych (CWE-117) + ochrona CSV/HTML.
- **AUDIT-006** Korelacja: W3C `traceparent`, `X-Trace-Id`, OTel `gen_ai.*`, bez treści.
- **AUDIT-007** Eksport CSV/JSON (NDJSON) z RLS, rolą auditor, auditowaniem eksportu, limitem.
- **AUDIT-008** Fail-closed/buffer przy awarii audytu; metryka `audit_dropped_total`.
- **AUDIT-009** Retencja konfigurowalna + audyt zmian polityk i konfiguracji.

## 12. Example Configuration
```yaml
audit:
  sinks: [postgres, file, otlp]
  fragment_hash: { algo: hmac-sha256, pepper_ref: "env:AUDIT_PEPPER", key_id: k1 }
  chain: { algo: hmac-sha256, key_ref: "env:AUDIT_CHAIN_KEY", anchor_every: 1000, anchor_sink: "file:/var/log/ctrl/anchors.log" }
  on_failure: fail_closed        # fail_closed|buffer
  retention: { full_days: 90, aggregated_days: 365 }
  fields: { max_len: 256, strip: [control_chars, ansi, bidi], normalize: NFC }
  otel: { capture_content: false, endpoint: "http://localhost:4317" }
rules:
- id: AUDIT-002
  name: Never log raw sensitive fragments
  category: output
  enabled: true
  priority: 1
  scope: { direction: [input, output], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: audit-selfcheck, detectors: [pii, secrets], on_hit: redact_record }
  action: REDACT
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM02], references: ["NIST SP 800-92"] }
- id: AUDIT-005
  name: Neutralize untrusted fields (log injection)
  category: output
  enabled: true
  priority: 2
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: regex, pattern: '[\x00-\x08\x0A-\x1F\x7F-\x9F‪-‮⁦-⁩]|\x1b\[' }
  action: REDACT         # sanitize in audit record; request itself is judged by other rules
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [], references: ["CWE-117"] }
- id: AUDIT-008
  name: Audit sink failure handling
  category: resource
  enabled: true
  priority: 3
  scope: { direction: [input], agents: ["*"], tools: [], environments: [prod] }
  conditions: { audit_sink_healthy: false }
  matcher: { type: condition }
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [], references: ["NIST SP 800-53 AU-5"] }
```

## 13. Example Requests
```json
{ "request": {"messages":[{"role":"user","content":"Mój PESEL 44051401359"}]}, "expect": {"response":"redacted", "audit":{"rule_id":"PII-001","decision":"REDACT","match.fragment_hmac":"present","raw_value_in_log":false}} }
{ "request": {"headers":{"User-Agent":"x\r\n2026-10-03T00:00:00Z action=ALLOW rule=FAKE"}}, "expect": {"audit":{"lines_added":1,"user_agent":"x\\r\\n2026-10-03T00:00:00Z action=ALLOW rule=FAKE","sanitized":true}} }
{ "request": {"messages":[{"role":"user","content":"=HYPERLINK(\"http://evil\",\"x\")"}]}, "export":"csv", "expect": {"cell":"'=HYPERLINK(\"http://evil\",\"x\")"} }
{ "action":"verify-audit", "after_tamper":"UPDATE audit_log SET decision='ALLOW' WHERE seq=100", "expect": {"result":"BROKEN_AT_SEQ_100"} }
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| AUDIT-T001 | request z PESEL | audit zawiera `fragment_hmac`, brak PESEL w żadnym polu/pliku/trace (grep całej bazy i logów) |
| AUDIT-T002 | dwa razy ten sam PESEL | identyczny `fragment_hmac` (korelacja), różne przy innym tenant/pepper |
| AUDIT-T003 | request ALLOW (benign) | rekord z `decision=ALLOW` |
| AUDIT-T004 | `\r\n` + fałszywa linia w nagłówku/prompcie | jedna linia/rekord, wartość escapowana, `sanitized=true` |
| AUDIT-T005 | ANSI `\x1b[2J`, bidi U+202E w polu | usunięte/escapowane |
| AUDIT-T006 | eksport CSV z komórką `=cmd|' /C calc'!A0` | prefiks `'` |
| AUDIT-T007 | `UPDATE audit_log` jako rola aplikacyjna | odmowa DB + alert |
| AUDIT-T008 | modyfikacja rekordu bezpośrednio w DB (superuser) | `verify-audit` wskazuje `seq` |
| AUDIT-T009 | usunięcie końcowych rekordów | wykryte przez kotwicę / lukę `seq` |
| AUDIT-T010 | `trace_id` w response == `trace_id` w audit i w spanie sidecara | zgodne |
| AUDIT-T011 | awaria Postgresa (prod, fail_closed) | BLOCK 503, alert; po powrocie brak luk w chainie lub jawny rekord `gap` |
| AUDIT-T012 | pole 1 MB w prompcie | rekord obcięty do limitu, `truncated=true` |
| AUDIT-T013 | zmiana polityki w runtime | rekord `policy.changed` (kto, wersja, hash diffu) |
| AUDIT-T014 | export przez rolę bez uprawnień | 403 + rekord `audit.export_denied` |
| AUDIT-T015 | export tenant A | brak rekordów tenanta B |
| AUDIT-T016 (bypass) | PESEL zakodowany w `reason`/error message wyjątku | self-check wykrywa i redaguje przed zapisem |
| AUDIT-T017 | OTel: `capture_content=false` | brak `gen_ai.input.messages` w spanach |
| AUDIT-T018 (edge) | 10 000 żądań/s burst | brak utraty (bufor) lub jawny `audit_dropped_total` |

## 15. Sources
- OpenSSF Secure Coding Guide — CWE-117 — https://best.openssf.org/Secure-Coding-Guide-for-Python/CWE-707/CWE-117 — [RESEARCH]
- CodeQL — Java log injection — https://codeql.github.com/codeql-query-help/java/java-log-injection — [RESEARCH]
- NIST SP 800-92 — https://csrc.nist.gov/publications/detail/sp/800-92/final — 2006 — [RESEARCH]
- NIST SP 800-53 AU (wtórne omówienie) — https://coralogix.com/guides/nist-sp-800-53-audit-logging-au-controls/ — [RESEARCH, źródło wtórne]
- OpenTelemetry GenAI semantic conventions — https://github.com/open-telemetry/semantic-conventions-genai — [RESEARCH]
- CVE-2025-0330 LiteLLM — https://osv.dev/vulnerability/CVE-2025-0330 — [CONFIRMED-VULN]
- CVE-2024-5225 / GHSA-h6m6-jj8v-94jj — https://vulnerability.cert.dk/vuln/ghsa-h6m6-jj8v-94jj — [CONFIRMED-VULN, zweryfikować w NVD]
- MCP Security Best Practices — https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices — [RESEARCH]
- eWeek — Replit — https://www.eweek.com/news/replit-ai-coding-assistant-failure/ — 2025-07 — [REAL-ATTACK]
- RFC 8785 (JSON Canonicalization Scheme), W3C Trace Context, OWASP Logging Cheat Sheet — powołane z wiedzy, niepobrane w tej sesji — niezweryfikowane.
