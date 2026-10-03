# Implementation Backlog

Kolejność implementacji (P0 = najpierw). Kryterium: wartość dla oceny (Robustness 30%, Reporting 20%, Self-test 15%) ÷ koszt, z uwzględnieniem zależności.
Status: `TODO` (wszystko w momencie powstania dokumentu; `backend/` jest pusty).

| Priority | Control | Description | Dependencies | Complexity | Test Required | Status |
|---|---|---|---|---|---|---|
| P0-1 | Szkielet `Guard`/`GuardChain` + konfiguracja YAML (`enabled/order/params`) | Interfejs `Guard`, `Verdict` (Allow/Redact/Block), łańcuch INPUT/OUTPUT, walidacja kluczy przy starcie. Hot reload, `mode: monitor`, RATE_LIMIT/QUARANTINE – później | decision-model.md | M | Tak (unit) | DONE |
| P0-2 | Audit log (AUDIT) | Tabela append-only, HMAC fragmentów, trace/policy_version; eksport CSV/JSON | P0-1, Postgres | M | AUDIT-T* | TODO |
| P0-3 | AuthN (AUTHN-001..004) | API key (hash), JWT (alg allowlist, aud/iss), strip nagłówków tożsamości | P0-1 | M | AUTHN-T* | TODO |
| P0-4 | Canonicalization (CANON-001..005) | Strict UTF-8, NFKC, Tags/zero-width/bidi, bounded decode → widoki | P0-1 | M | CANON-T* | TODO |
| P0-5 | PII (PII-001..012) | E-mail, tel., PESEL/NIP/REGON/dowód, Luhn, IBAN mod-97, kontekst, REDACT | P0-4 | M | PII-T* | TODO |
| P0-6 | Secrets (SEC-001..010) | Prefiksowe regexy (z Gitleaks), PEM/JWT, key-value, entropia, base64 | P0-4 | M | SEC-T* | TODO |
| P0-7 | Rate limit + concurrency (RATE-001..004, LIMIT bulkhead) | Bucket4j per principal, semafor+kolejka dla Ollamy, 429+Retry-After | P0-3 | M | RATE-T*, LIMIT-T* | TODO |
| P0-8 | Budget (BUDGET-001..005) | Clamp `num_predict`/`max_tokens`, atomowy UPDATE, usage z Ollamy, dzienny cap | P0-3, Postgres | M | BUDGET-T* | TODO |
| P0-9 | Model allowlist (MODEL-001..005) | Allowlista modeli, blokada `/api/pull|create…`, clamp parametrów | P0-3 | S | MODEL-T* | TODO |
| P0-10 | AuthZ (AUTHZ-001..005) | Deny-by-default; model/tool permissions; user ∩ agent | P0-3, P0-9 | M | AUTHZ-T* | TODO |
| P0-11 | Edge limits (LIMIT-001..006) | Max body, reject Content-Encoding, JSON depth, max prompt chars, timeouty | P0-1 | S | LIMIT-T* | TODO |
| P1-1 | Output redaction + streaming (OUT-001..006) | Hold-back buffer, reuse PII/SEC, canary w system prompcie | P0-5, P0-6 | L | OUT-T* | TODO |
| P1-2 | Exfil guard (EXF-001..004, 006) | commonmark AST, allowlista hostów obrazów/linków, JSON Schema strict outputu | P1-1 | M | EXF-T* | TODO |
| P1-3 | MCP allowlist (MCP-ALLOW-001..006) | Rejestr serwerów, macierz tool×role, read/write z rejestru, zakaz token passthrough | P0-10 | M | MCP-ALLOW-T* | TODO |
| P1-4 | MCP argument validation (MCP-ARG-001..008) | JSON Schema strict per tool, limity struktury, re2j, brak zdalnych `$ref` | P0-11, P1-3 | M | MCP-ARG-T* | TODO |
| P1-5 | SSRF guard (NET-001) | Ścisły parser URL, CIDR po DNS, pinning IP, redirecty, allowlista domen | P1-4 | M | NET-T* | TODO |
| P1-6 | Path restriction (FS-001) | Kanonikalizacja, `toRealPath`, roots r/w, deny-globs, brak symlinków | P1-4 | M | FS-T* | TODO |
| P1-7 | Command policy (CMD-001) | Allowlista binarek+opcji, argv bez shella; SQL/SSTI wzorce; AST jako druga warstwa | P1-4 | L | CMD-T* | TODO |
| P1-8 | Tool chain/loop limits (CHAIN-001..004, LOOP-001..002,005) | Liczniki per chain/caller, depth serwerowy, repeat_hash, circuit breaker | P0-7, session store | M | CHAIN-T*, LOOP-T* | TODO |
| P1-9 | Signature feed (SIG-001..004) | Plik YAML, Aho-Corasick/RE2J, hot reload, wersjonowanie | P0-1, P0-4 | M | SIG-T* | TODO |
| P1-10 | Prompt-attack patterns (PI-001..005) | Frazy/role tokens na widokach CANON; hybrid scoring + handoff do sidecara | P0-4, P1-9 | S–M | PI-T* | TODO |
| P1-11 | Deserialization (DESER-001..005) | Magic bytes Java/pickle, YAML tagi, base64-wrapped | P0-4, P1-9 | M | DESER-T* | TODO |
| P1-12 | MCP integrity (MCP-INT-001..006) | Pinning hash `tools/list` (JCS+SHA-256), skan opisów, QUARANTINE | P1-3 | M | MCP-INT-T* | TODO |
| P2-1 | Sequence/taint (SEQ-001..003) | Etykiety sesji, trifecta guard, zakazane sekwencje, egress allowlist | P1-3, P1-8 | M–L | SEQ-T* | TODO |
| P2-2 | Tenant/env isolation (TENANT-001..005) | Klucze tenantowe w cache, RLS, guard `DROP/DELETE` w prod | P0-3, P0-10 | M | TENANT-T* | TODO |
| P2-3 | Model supply chain (MODEL-SC-001..005) | `sha256:` digest, lockfile modeli, magic bytes GGUF/pickle | P0-9 | M | MODEL-SC-T* | TODO |
| P2-4 | Package/repo supply chain (PKG-001..005) | Parser komend instalacji, pinning, snapshot OSV `MAL-*` | P1-7, P1-9 | M | PKG-T* | TODO |
| P2-5 | Quarantine registry + REVIEW/CHALLENGE UI | Tabela kwarantanny, zwolnienie z dashboardu, kolejka zatwierdzeń | P0-2, front | M | Tak (e2e) | TODO |
| P2-6 | Hash chain audit + kotwiczenie | HMAC łańcuch, podpis/anchoring | P0-2 | S | AUDIT-T* | TODO |
| P3-1 | Rozszerzenia: LOOP-003/004, CHAIN-005..007, SEQ-004/005, SIG-005..007, OUT-007/008, EXF-005/007/009 | Zgodnie z plikami case | wcześniejsze | M | odpowiednie T* | TODO |
| P3-2 | NICE: LOOP-006, CHAIN-008, SEQ-006 (wymagają sidecara/IFC) | Sygnały semantyczne | sidecar | L | TBD | TODO |
| P-X | Runner testów (`./run-tests.sh`) + ładowanie test-catalog jako YAML | Wspólna infrastruktura; generuje sekrety testowe z fragmentów | P0-1 | M | — | TODO |

## Uwagi do kolejności
1. P0-4 (CANON) przed każdym regexem — inaczej testy bypass (zero-width, fullwidth, base64) będą czerwone.
2. P0-7/P0-8 wcześnie: chronią Raspberry Pi podczas demo/live testów jury.
3. Zakres numerów reguł (np. PII-001..012) wynika z plików case; reguły oznaczone tam jako SHOULD/NICE trafiają do P3.
4. Runner testów (P-X) rozwijać równolegle z P0 — jury uruchamia go samodzielnie, bez przygotowania.
5. Sidecar (poza zakresem tego katalogu) jest integrowany dopiero po P1-10 jako dodatkowy sygnał.
