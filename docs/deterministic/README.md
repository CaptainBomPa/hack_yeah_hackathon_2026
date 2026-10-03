# Deterministic / Non-AI Security Controls — Control Catalog

Katalog kontroli deterministycznych (bez AI) dla AI Control Layer: LLM, agenci, MCP. Każdy case to osobny plik
o wspólnej strukturze (15 sekcji: problem, atak, dowody z realnych CVE/incydentów, detekcja, pipeline, akcje,
obejścia, granica AI, implementacja, open source, propozycja, YAML, przykłady, testy, źródła).
Sekcja 12 w case files to szkic reguły; wdrożenie = klasa `Guard` wg [poradnika](how-to-write-a-rule.md).
Kontekst projektu: [`VISION.md`](../../VISION.md) (§4A to pierwotna lista kontroli deterministycznych).

> Uwaga o jakości: research wykonano przez agentów z dostępem do internetu; w każdym pliku rzeczy nieweryfikowane
> (licencje, część CVE ze źródeł wtórnych) są **jawnie oznaczone „niezweryfikowane"**. Zbiorcza lista luk: [sources.md](sources.md).
> Przed cytowaniem CVE/liczb jury sprawdź je w źródle pierwotnym.

## Dokumenty zbiorcze

| Dokument | Zawartość |
|---|---|
| [decision-model.md](decision-model.md) | Model decyzji (ALLOW/REDACT/BLOCK + RATE_LIMIT/QUARANTINE/REVIEW/CHALLENGE), format wyniku |
| [how-to-write-a-rule.md](how-to-write-a-rule.md) | Jak dopisać regułę: interfejs `Guard`, `Verdict`, włączanie w YAML, checklista, pipeline i zasady |
| [implementation-backlog.md](implementation-backlog.md) | Kolejność implementacji |
| [test-catalog.md](test-catalog.md) | Katalog testów: positive/negative/edge/bypass |
| [sources.md](sources.md) | Rejestr źródeł (OWASP, MITRE, NIST, CISA, CVE, vendor, papers, OSS) |

## Control Catalog

Złożoność: S/M/L. Priorytet: MUST / SHOULD / NICE / NOT (NOT RECOMMENDED).

| ID | Control | Category | Threat | Detection | Action | Complexity | Priority | Documentation |
|---|---|---|---|---|---|---|---|---|
| PII-001..018 | PII detection (PESEL, NIP, REGON, karty, IBAN, e-mail, tel., structured fields) | pii | Wyciek PII w prompcie, outpucie, tool-callach, logach | Kanonikalizacja → regex → checksum (PESEL/Luhn/mod-97) → kontekst → score | REDACT / BLOCK / REVIEW | M–L | MUST | [01](01-pii-detection.md) |
| SEC-001..014 | Secret detection | secrets | Wyciek kluczy, tokenów, PEM, cloud creds | Regexy z prefiksami, PEM/JWT, key-value w JSON/YAML, entropia, dekodowanie base64 | REDACT / BLOCK+QUARANTINE | M | MUST | [02](02-secret-detection.md) |
| AUTHN-001..008 | Authentication callerów/agentów | authn | Podrobiony JWT, confused deputy, spoofing nagłówków tożsamości | Hash klucza API, JWT (alg allowlist, aud/iss), strip nagłówków | BLOCK / RATE_LIMIT / CHALLENGE | M | MUST | [03](03-authentication.md) |
| AUTHZ-001..008 | Authorization (deny-by-default RBAC/ABAC) | authz | Excessive agency, eskalacja uprawnień przez prompt injection | Ewaluacja polityki, user ∩ agent | BLOCK / CHALLENGE / QUARANTINE | M | MUST | [04](04-authorization.md) |
| MODEL-001..007 | Model/endpoint allowlist + clamp parametrów | authz | Podmiana modelu, `/api/pull`, nadpisanie system promptu | Kanoniczna nazwa + digest, schemat pól | BLOCK / clamp / QUARANTINE | S | MUST | [05](05-model-allowlist.md) |
| TENANT-001..008 | Izolacja tenantów i środowisk | authz/state | Cross-tenant leak, dev→prod, destrukcyjne akcje | Klucze tenantowe, RLS, guard destrukcyjnych akcji w prod | BLOCK / CHALLENGE | M | MUST | [06](06-tenant-environment-isolation.md) |
| MCP-ALLOW-001..009 | MCP server/tool allowlist, read/write, URI | mcp/authz | Złośliwy/shadow serwer, scope creep, token passthrough | Rejestr serwerów, macierz uprawnień, kanonizacja URI | BLOCK / CHALLENGE / REVIEW | M | MUST | [07](07-mcp-server-tool-allowlist.md) |
| MCP-ARG-001..010 | Walidacja argumentów tool-calls (JSON Schema strict) | mcp/input | Argument/command injection, traversal, DoS | networknt validator, limity struktury, re2j | BLOCK / REVIEW / QUARANTINE | M | MUST | [08](08-mcp-argument-validation.md) |
| FS-001 | Ograniczenia ścieżek plików | filesystem | Path traversal, symlink escape, prefix confusion | Kanonikalizacja + `toRealPath` + `startsWith` na komponentach | BLOCK / REVIEW | M | MUST | [09](09-filesystem-path-restriction.md) |
| NET-001 | SSRF guard + domain allowlist | network | Metadata/IP prywatne, rebinding, redirecty, obfuskacja IP | Ścisły parser URL, CIDR po DNS, pinning IP | BLOCK / REDACT / REVIEW | M | MUST | [10](10-network-ssrf-domain-restriction.md) |
| CMD-001 | Command/code/SQL injection | command | Metaznaki shella, argument injection, `curl\|sh` | Allowlista binarek+opcji, argv bez shella, AST | BLOCK / REVIEW / CHALLENGE | L | MUST | [11](11-command-injection.md) |
| MCP-INT-001..009 | Integralność narzędzi MCP (pinning, skan opisów) | mcp/supply-chain | Tool poisoning, rug pull, shadowing | Hash JCS+SHA-256, regexy na niewidoczny Unicode | QUARANTINE / BLOCK / REVIEW | M | MUST | [12](12-mcp-tool-integrity.md) |
| RATE-001..007 | Rate limiting (RPM, współbieżność, TPM) | resource | Flood, Denial of Wallet, LLMjacking, spoofing XFF | Bucket4j, semafory, zaufane proxy | RATE_LIMIT / QUARANTINE | M | MUST | [13](13-rate-limiting.md) |
| BUDGET-001..008 | Budżety tokenów/kosztu | resource | Unbounded consumption, LLMjacking | Clamp `num_predict`, atomowy UPDATE w Postgres, usage z Ollamy | BLOCK / RATE_LIMIT / QUARANTINE | M | MUST | [14](14-token-budget-quotas.md) |
| CHAIN-001..008 | Limity tool calls, głębokości, fan-out | resource | Runaway agent, fork bomb agentów | Liczniki per chain/caller/sesja, depth po stronie serwera | BLOCK / RATE_LIMIT / REVIEW | M | MUST (001–004) | [15](15-tool-call-limit-chain-depth.md) |
| LOOP-001..006 | Wykrywanie pętli + circuit breaker | resource/state | Powtarzalne wywołania, ping-pong | hash(tool+args) w oknie, cykle okresu 2–4, breaker | BLOCK / QUARANTINE / REVIEW | M | MUST (001,002,005) | [16](16-runaway-agent-loop-detection.md) |
| SEQ-001..006 | Maszyna stanów sekwencji narzędzi (lethal trifecta) | state | Toxic agent flow, confused deputy | Etykiety sesji, DFA sekwencji, egress allowlist | BLOCK / REVIEW / QUARANTINE / CHALLENGE | M–L | MUST (001–003) | [17](17-tool-sequence-state-machine.md) |
| LIMIT-001..012 | Timeouty, bulkhead, limity rozmiaru | resource | Zip bomb, deep JSON, slowloris, many-shot, zalanie Pi | Liczniki bajtów, StreamReadConstraints, semafor+kolejka | BLOCK / RATE_LIMIT / clamp | M | MUST | [18](18-timeouts-concurrency-input-limits.md) |
| OUT-001..008 | Kontrola wycieków w outpucie (streaming, canary) | output | Wyciek PII/sekretów/system promptu | Hold-back buffer, canary, ponowne użycie reguł PII/SEC | REDACT / BLOCK+QUARANTINE | M (streaming L) | MUST | [19](19-output-data-leakage.md) |
| EXF-001..010 | Structured output + kanały eksfiltracji | output | Markdown/HTML image exfil, DNS exfil, niepoprawny schemat | AST markdown, allowlista hostów, JSON Schema | REDACT / BLOCK / REVIEW | M | MUST (001–004,006,008) | [20](20-output-structured-validation-and-exfil-channels.md) |
| CANON-001..008 | Kanonikalizacja wejścia | input | ASCII smuggling, homoglify, zagnieżdżone kodowania | Strict UTF-8, Unicode Tags, NFKC + skeleton, dekodowanie z limitem | BLOCK / REVIEW / REDACT | M | MUST | [21](21-input-canonicalization-encoding.md) |
| SIG-001..007 | Feed sygnatur/IOC/CVE (hot reload) | threat-intel | Znane payloady, podatne wersje | Aho-Corasick, RE2J, rejestr wersji (OSV/KEV) | BLOCK / QUARANTINE | M | SHOULD | [22](22-threat-signature-ioc-cve.md) |
| DESER-001..008 | Wykrywanie niebezpiecznej deserializacji | input | Java/pickle/YAML/SSTI | Magic bytes, opcode pickle (allowlista), tagi YAML | BLOCK / QUARANTINE | M | SHOULD | [23](23-unsafe-deserialization.md) |
| MODEL-SC-001..008 | Supply chain modeli (digest, rejestry) | supply-chain | Złośliwy pickle w modelu, Probllama | `sha256:` digest, lockfile, magic bytes, podpis | BLOCK / QUARANTINE / CHALLENGE | M | MUST | [24](24-model-supply-chain.md) |
| PKG-001..008 | Supply chain paczek/repozytoriów | supply-chain | Typosquatting, slopsquatting, npm worms | Parser komend instalacji, pinning, OSV `MAL-*` | BLOCK / REVIEW | M | SHOULD | [25](25-package-repo-supply-chain.md) |
| PI-001..009 | Deterministyczne wzorce prompt-attack (+ handoff do sidecara) | input/output | Instruction override, role spoofing, many-shot | Aho-Corasick + RE2J na widokach z CANON, hybrid score | BLOCK / handoff | S–M | SHOULD | [26](26-deterministic-prompt-attack-patterns.md) |
| AUDIT-001..009 | Audit log (HMAC, hash chain, sanityzacja) | governance | PII w logach, log injection, manipulacja logiem | HMAC fragmentów, łańcuch HMAC, append-only | REDACT / BLOCK (fail-closed) | M | MUST | [27](27-audit-logging.md) |

## MUST HAVE

Implementujemy w pierwszej kolejności — pokrywają zadanie z `CRITERIA` (PII/secrets, auth, rate/budget, MCP, SSRF, injection) i dają największy efekt w kryterium Robustness (30%).

- **PII, SEC, OUT, EXF, CANON** — dane wychodzące i wchodzące; CANON jest warunkiem skuteczności wszystkich regexów (badanie arXiv 2504.11168: char-injection obchodzi komercyjne guardraile).
- **AUTHN, AUTHZ, MODEL, TENANT** — autoryzacja musi być w Control Layer, bo decyzja LLM jest podatna na prompt injection (OWASP LLM06).
- **MCP-ALLOW, MCP-ARG, MCP-INT, FS, NET, CMD** — konkretne CVE w serwerach MCP (EscapeRoute, mcp-server-git, mcp-remote, Inspector) to klasy błędów w pełni łapane deterministycznie.
- **RATE, BUDGET, CHAIN, LOOP, LIMIT** — Pi ma małą moc, a budżet/anty-runaway jest wprost w kryteriach.
- **SEQ-001..003** — jedyna realna obrona przed „lethal trifecta" bez AI.
- **MODEL-SC, AUDIT** — tanie, a wymagane przez Security Reporting (20%).

## SHOULD HAVE

- **SIG** (hot-reloadowalny feed — wprost wymóg „live podmiana"), **DESER**, **PKG**, **PI** (jako sygnał dla hybrid scoringu; same w sobie niewystarczające — „The Attacker Moves Second", arXiv 2510.09023).
- SEQ-004/005 (value-level taint, tool pinning), CHAIN-005..007.

## NICE TO HAVE

- LOOP-006 (stagnacja semantyczna — wymaga sidecara), CHAIN-008, SEQ-006, pełne IFC w stylu CaMeL/FIDES (wymaga zmiany plannera agenta, poza zasięgiem gatewaya), integracja OSV/KEV online (w trybie offline tylko snapshot).

## NOT RECOMMENDED

| Rzecz | Uzasadnienie |
|---|---|
| Weryfikacja sekretów online (styl TruffleHog) | Łamie wymóg offline i wysyła sekret do strony trzeciej (02) |
| Zewnętrzne chmurowe DLP (Google DLP, Purview) jako bramka | Regulamin: offline, brak płatnych usług — tylko jako inspiracja |
| Samodzielny denylist komend/regex jako obrona przed command injection | Trywialne obejścia; stosujemy allowlistę + argv + sandbox (11) |
| Hash bez klucza (gołe SHA-256) PESEL/PII w logu | Odwracalny słownikowo — używamy HMAC (01, 27) |
| Deterministyczne wykrywanie imion/nazwisk w prozie | Fleksja i nazwiska = zwykłe słowa; zostaje sidecar NER (01) |
| Same frazy „ignore previous instructions" jako jedyna bramka PI | Adaptacyjni atakujący >90% ASR (26) |
| Redis/Kafka/Kubernetes na start | VISION.md: Postgres wystarczy, Redis tylko gdy liczniki za wolne |
| Ładowanie zdalnych `$ref` w JSON Schema, picklescan-denylist jako jedyna kontrola modeli | SSRF/obejścia (08, 24) |

## Które kontrole wymagają semantic AI (sidecar)

Deterministycznie nie da się: imion/adresów/danych medycznych w prozie (PII), sekretów w parafrazie (SEC), parafrazowanego wycieku (OUT), semantycznej eksfiltracji bez URL (EXF), injekcji w opisach narzędzi i wynikach (MCP-INT), intencji przy poprawnej składniowo komendzie (CMD), zmiennych-argumentami pętli (LOOP-006), parafrazy/tłumaczeń prompt injection (PI), backdoorów w wagach modeli (MODEL-SC). **Zasada:** sidecar tylko podnosi severity/dostarcza atrybut kontekstu — twarde bramki pozostają deterministyczne.
