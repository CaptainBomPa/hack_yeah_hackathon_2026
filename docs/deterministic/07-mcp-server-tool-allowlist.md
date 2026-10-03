# MCP: allowlista serwerów i narzędzi, uprawnienia per-agent, separacja read/write
> **ID:** MCP-ALLOW-001..009  | **Kategoria:** mcp / authz / supply-chain | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** tool-call (oraz tools/list, resources/read, rejestracja serwera)

## 1. Overview
Gateway stoi między agentem (LLM + klient MCP) a serwerami MCP. Chronimy:
- **które serwery MCP** w ogóle mogą być osiągnięte (rejestr zatwierdzonych serwerów, blokada „shadow MCP"),
- **które narzędzia** (`tools/call`) i **zasoby** (`resources/read`, `resources/subscribe`) są dozwolone dla danego agenta/użytkownika/środowiska,
- **separację odczyt/zapis** (narzędzia mutujące wymagają wyższych uprawnień, innego trybu akcji lub zatwierdzenia),
- **ograniczenia produkcyjne** (w `prod` np. brak narzędzi shell/filesystem-write, brak serwerów spoza rejestru),
- **URI zasobów** (schematy, ścieżki, hosty).

Jest to deterministyczna kontrola „kto + co + gdzie + jak": tablica decyzyjna oparta na danych (YAML/Postgres) z hot-reloadem (zgodnie z VISION.md §4.A.3, A.6, A.12).

## 2. Threat / Attack
Mechanizmy ataku (krok po kroku):
1. **Złośliwy/zatruty serwer MCP w łańcuchu dostaw.** Programista/agent instaluje serwer z npm/PyPI (np. kopia popularnego pakietu z dopisanym backdoorem). Serwer ma uprawnienia legalnego narzędzia, więc działa „normalnie", ale kopiuje dane (np. BCC maili). Allowlista serwerów po tożsamości (URL + pin wersji/hasha) blokuje nieznany pakiet.
2. **Shadow MCP** (OWASP MCP09): nieautoryzowany serwer uruchomiony lokalnie/w sieci, często z domyślnymi poświadczeniami, poza governance. Agent łączy się z nim bo ktoś dodał wpis do konfiguracji klienta.
3. **Nadmiarowe uprawnienia / scope creep** (OWASP MCP02): agent „czytający tickety" ma dostęp do `delete_*`, `execute_sql` z `service_role`. Prompt injection z danych (issue, ticket) kieruje agenta do narzędzi o większym zasięgu niż potrzeba (patrz MCP-INT: toxic flow).
4. **Nadużycie narzędzia mutującego z kontekstu read-only** — agent zadany jako „podsumuj", a wywołuje `send_email`/`git_push`.
5. **Resource URI abuse:** `resources/read` z `file:///etc/passwd`, `file:///home/u/.ssh/id_rsa`, `http://169.254.169.254/...`, `javascript:`/`data:`; path traversal, symlinki, prefix-matching bypass (patrz CVE-2025-53110 niżej).
6. **Confused deputy / token passthrough** — serwer MCP przyjmuje tokeny nie wystawione dla niego; gateway nie może „przekazać" tokenu klienta dalej.
7. **Różnice środowisk:** narzędzie dozwolone w `dev` trafia na `prod`.

## 3. Real-World Evidence
| Tag | Zdarzenie | Mechanizm / komponent / wpływ / zapobieganie | Źródło |
|---|---|---|---|
| `[REAL-ATTACK]` | **postmark-mcp (npm), wrzesień 2025** — Koi Security nazwał to „pierwszym zaobserwowanym złośliwym serwerem MCP in the wild" | Kopia legalnego projektu Postmark; 15 wersji czystych, w **v1.0.16** dodano jedną linię BCC wszystkich maili do `phan@giftshop.club`. ~1500 pobrań/tydz.; szacunek 3–15 tys. wycieczonych maili; autor usunął pakiet po kontakcie. Zapobieganie: allowlista serwerów po źródle/hashu, pin wersji (nie `latest`), kontrola egress/adresatów w argumentach (patrz MCP-ARG). Uwaga: ten backdoor **nie zmieniał opisów narzędzi** – wykrycie przez hash tools/list byłoby bezskuteczne; wykrywalne tylko przez pin wersji pakietu / diff kodu / analizę argumentów wyjściowych (BCC). | Koi Security: https://koi.ai/blog/postmark-mcp-npm-malicious-backdoor-email-theft (nie udało się pobrać strony – cytowane przez media); Dark Reading: https://www.darkreading.com/application-security/malicious-mcp-server-exfiltrates-secrets-bcc ; SC World: https://www.scworld.com/news/open-source-mcp-server-package-caught-stealing-emails |
| `[CONFIRMED-VULN]` | **CVE-2025-53110 / CVE-2025-53109 („EscapeRoute")** — Filesystem MCP Server (Anthropic), Cymulate, 2025 | 53110: naiwne sprawdzanie prefiksu katalogu (`/private/tmp/allowed_dir_evil` przechodzi) → odczyt/zapis poza sandboxem. 53109: symlink omija kontrolę → odczyt/zapis dowolnych plików, możliwy RCE. Naprawione w 2025.7.1 / 0.6.3. Wniosek: allowlista „czy narzędzie dozwolone" nie wystarcza – serwer sam musi egzekwować ścieżki; gateway dokłada drugą warstwę na argumentach/URI. | https://cymulate.com/blog/cve-2025-53109-53110-escaperoute-anthropic/ |
| `[CONFIRMED-VULN]` | **CVE-2025-49596** — MCP Inspector (Anthropic), CVSS 9.4, Oligo | Proxy bez uwierzytelnienia + „0.0.0.0-day" + DNS rebinding → RCE przez stdio z przeglądarki. Opublikowano 13.06.2025, fix 0.14.1 (token sesji, kontrola Host/Origin). Wniosek: lokalne serwery MCP/proxy bez authN = shadow-MCP-podobny wektor. | https://www.oligo.security/blog/critical-rce-vulnerability-in-anthropic-mcp-inspector-cve-2025-49596 |
| `[REAL-ATTACK]/[POC]` | **Supabase MCP – „lethal trifecta"**, lipiec 2025 (opis Simona Willisona; autor oryginalnego demo – niezweryfikowane) | Cursor z Supabase MCP działającym jako `service_role` (omija RLS) czyta tickety kontrolowane przez atakującego; ticket instruuje: odczytaj `integration_tokens` i dopisz do ticketu. Przyczyna: jeden serwer łączy prywatne dane + niezaufaną treść + kanał wyjściowy, a uprawnienia są nadmiarowe. Zapobieganie: least privilege (read-only, project-scoped), rozdzielenie narzędzi. | https://simonwillison.net/2025/Jul/6/ (wpis o Supabase MCP) |
| `[RESEARCH]` | **OWASP MCP Top 10 (2025, beta)** | MCP02 Scope Creep, MCP07 Insufficient AuthN/AuthZ, MCP09 Shadow MCP Servers, MCP04 Supply Chain, MCP08 Lack of Audit. Mapowanie w metadata reguł. | https://owasp.org/www-project-mcp-top-10/ |
| `[MITIGATION]` | **MCP spec – Security Best Practices** | MUST: per-client consent w proxy OAuth, **zakaz token passthrough** („MCP servers MUST NOT accept any tokens that were not explicitly issued for the MCP server"), walidacja redirect_uri exact-match, minimalizacja scope (bez `*`), walidacja schematów URL (tylko http/https), blokada prywatnych IP (SSRF), sandbox serwerów lokalnych, **nie implementować walidacji IP ręcznie** (sztuczki oktalne/hex/IPv4-mapped). | https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices |
| `[MITIGATION]` | **MCP spec – Tools / Resources** | Tools: „Servers MUST: validate all tool inputs, implement access controls, rate limit, sanitize outputs"; klient: potwierdzenia dla wrażliwych operacji, logowanie. Annotations (np. readOnlyHint) **„MUST be considered untrusted unless from trusted servers"** → nie wolno polegać na hincie read-only od serwera. Resources: „Servers MUST validate all resource URIs". | https://modelcontextprotocol.io/specification/2025-06-18/server/tools ; https://modelcontextprotocol.io/specification/2025-06-18/server/resources |
| `[REAL-ATTACK]` | **GitHub MCP toxic agent flow**, Invariant Labs, 26.05.2025 | Złośliwy issue w publicznym repo → agent z jednym tokenem obejmującym repo prywatne i publiczne wycieka dane prywatne przez PR do publicznego repo. Zapobieganie: granularne uprawnienia (jedno repo na sesję), kontrola przepływu między repozytoriami. | https://invariantlabs.ai/blog/mcp-github-vulnerability |

Niezweryfikowane: dokładna liczba serwerów-shadow w przedsiębiorstwach i statystyki z blogów dostawców (`[VENDOR-CLAIM]`) – pominięte.

## 4. Deterministic Detection
Wszystko poniżej to dopasowanie do danych, bez AI:
1. **Rejestr serwerów** (`mcp_servers`): `server_id`, kanoniczny URL/endpoint (host:port, schemat), transport, właściciel, środowiska, pin wersji/hasha (patrz MCP-INT), `trust_tier` (internal/vendor/community). Żądanie do serwera spoza rejestru → BLOCK. Porównanie po **kanonicznym** hoście (lowercase, punycode, bez trailing dot, rozwiązany IP nie może być w denylist) – nie po stringu z żądania.
2. **Macierz uprawnień** `(principal × server × tool × environment) → ALLOW|DENY|REVIEW`; domyślnie **deny-by-default**. Principal = klucz API/JWT agenta + opcjonalnie `sub` użytkownika (delegacja: uprawnienia = przecięcie uprawnień agenta i użytkownika).
3. **Klasyfikacja narzędzi** w rejestrze (dane, nie hint serwera): `access: read | write | admin | exec | network-egress`, `side_effects`, `data_class`. Reguła: w sesji oznaczonej read-only każde narzędzie `write|admin|exec` → BLOCK; w `prod` globalny deny na `exec`, `fs-write` poza katalogiem roboczym.
4. **Normalizacja nazw narzędzi** przed dopasowaniem: NFKC, odrzucenie znaków sterujących/niewidocznych i homoglifów (np. `read_fiIe` z wielkim I) – dopasowanie dokładne (exact), nie regex po prefiksie. Konflikt nazw między serwerami (dwa serwery eksportują `send_email`) → wymagany namespace `server_id.tool` (cross-server shadowing, patrz MCP-INT).
5. **Resource URI:** parsowanie RFC 3986 (parser biblioteczny), allowlista schematów (`https`, `file`, własne), `file://` → kanonizacja ścieżki (`Path.toRealPath()` przy istniejącym pliku, normalizacja `..`, `%2e%2e`, podwójne kodowanie, null byte), sprawdzenie przynależności **przez porównanie segmentów ścieżki**, nie przez `startsWith` stringów (to właśnie błąd CVE-2025-53110); odrzucenie `javascript:`, `data:`, `vbscript:`; `http(s)` → guard SSRF (prywatne/loopback/link-local, IPv4-mapped IPv6, DNS pinning) – wspólny moduł z kontrolą NET (VISION A.7).
6. **Shadow MCP:** wykrycie na poziomie gateway = ruch do hosta/portu spoza rejestru; w trybie „discovery" (pasywnym) wpisy trafiają do kolejki REVIEW zamiast BLOCK. Skanowanie sieci jest poza zakresem gatewaya (granica: inwentaryzacja = zadanie procesowe/EDR).
7. **Token passthrough:** gateway sprawdza `aud` tokenu = identyfikator gatewaya/serwera; tokeny klienta nie są forwardowane – gateway wystawia własne, krótkotrwałe poświadczenia per downstream.
8. **Rate limit per (principal, tool)** i licznik wywołań narzędzi `write` na sesję (wspólne z budżetem).

## 5. Detection Pipeline
Request → Canonicalization (JSON-RPC parse, normalizacja nazw, URI) → AuthN (klucz/JWT → principal) → **Policy: MCP-ALLOW-001 serwer w rejestrze → 002 tool dozwolony dla principal+env → 003 read/write vs tryb sesji → 004 URI resource** → Rules (MCP-ARG: schemat argumentów, MCP-INT: pin hashu) → Wywołanie serwera MCP → Output (sanityzacja wyniku, redakcja, MCP-INT skan wyników) → Response. Dodatkowo hook na `tools/list`: **filtrowanie odpowiedzi** – agent widzi tylko narzędzia, które może wywołać (zmniejsza powierzchnię prompt injection i kontekst modelu). Decyzje logowane do audit log (principal, server, tool, reguła, akcja; bez surowych argumentów z PII – hash).

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| Serwer spoza rejestru | BLOCK (HIGH), w trybie discovery REVIEW |
| Tool nie na allowliście principal | BLOCK |
| Narzędzie `write` w sesji read-only | BLOCK; opcjonalnie CHALLENGE (human-in-the-loop zgodnie ze spec) |
| Narzędzie wysokiego ryzyka (`exec`, `delete_*`, wysyłka maili) | REVIEW/CHALLENGE (zatwierdzenie człowieka) lub BLOCK w prod |
| URI poza dozwolonymi korzeniami / schematem | BLOCK |
| Anomalia wolumenu (n wywołań write/min) | RATE_LIMIT, ewentualnie QUARANTINE principal |
| Nowy serwer/narzędzie dodane w rejestrze po rug-pull | QUARANTINE do ponownego zatwierdzenia (MCP-INT) |
| Wszystko pozostałe zgodne z polityką | ALLOW (+ audit) |

## 7. Bypass / Limitations
- **Allowlista nie mówi nic o tym, co zatwierdzony serwer robi** – postmark-mcp po wejściu na listę wciąż kopiuje maile. Potrzebne: pin wersji, kontrola argumentów wyjściowych (adresaci), egress, sandbox serwera, monitoring anomalii.
- **Confused deputy wewnątrz dozwolonego narzędzia:** prompt injection potrafi użyć legalnego `read` + legalnego `send` (toxic flow). Deterministycznie ograniczamy tylko kombinacje (np. zakaz `network-egress` po `read` z `data_class=private` w jednej sesji – taint tracking uproszczony) – ale nie wszystkie przypadki.
- **Klasyfikacja read/write jest ręczna** – błędnie sklasyfikowane narzędzie (np. „read" z efektem ubocznym GET-em z mutacją) omija kontrolę. Nie ufać `annotations` serwera (spec).
- **Symlinki/TOCTOU** – kontrola ścieżki na gatewayu nie widzi systemu plików serwera; realną kontrolą jest sandbox/chroot po stronie serwera. Gateway jest warstwą dodatkową.
- **Tunelowanie przez dozwolony serwer** (serwer-proxy do dowolnych URL, np. „fetch") – wymaga allowlisty domen w argumentach (MCP-ARG).
- **FP:** zbyt restrykcyjne polityki blokują legalne workflow; mitigacja: tryb `monitor` (log-only) na start, dry-run polityki.
- **Wydajność:** lookup w mapie in-memory O(1); koszt pomijalny (<1 ms); rejestr przeładowywany z Postgres/pliku przy zmianie wersji polityki.

## 8. Deterministic vs AI
Deterministycznie: tożsamość serwera, uprawnienia, klasy narzędzi, URI, schematy, limity, tryby env. 
Do sidecara (semantyka): (a) czy sekwencja wywołań ma charakter eksfiltracji (np. read prywatnych danych → wysyłka do nowego odbiorcy) – klasyfikator zamiarów/anomalii, LLM-as-judge dla REVIEW; (b) ocena „czy opis narzędzia pasuje do jego deklarowanej funkcji" przy rejestracji (MCP-INT); (c) wykrywanie prompt injection w danych, które skłoniły agenta do wywołania. AI nie powinno być bramką dla allowlisty – to ma być twarda polityka.

## 9. Implementation Options
- **Java (Spring Cloud Gateway):** `McpAllowlistGatewayFilterFactory` – parsuje JSON-RPC (`method=tools/call|resources/read|tools/list`), konsultuje `PolicyStore` (Caffeine cache + odczyt z Postgres, nasłuch zmian/`LISTEN/NOTIFY` lub polling wersji polityki). Parser URI: `java.net.URI` + własna kanonizacja `Path.normalize()`/`toRealPath`; SSRF: `InetAddress` po rozwiązaniu + sprawdzenie `isSiteLocal/isLoopback/isLinkLocal` oraz IPv4-mapped. Autoryzacja ewentualnie przez **OPA/Rego** lub **Cedar** (sidecar/embedded) jeśli chcemy gotowy język polityk – dla hackathonu YAML + prosty ewaluator wystarcza.
- **Python sidecar:** niepotrzebny dla tej kontroli; tylko dla klasyfikacji zamiarów.
- Hot-reload: wersjonowane polityki w Postgres + endpoint `/admin/policies/reload`; jury zmienia YAML → zmiana skutkuje przy następnym żądaniu.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność integracji | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| Open Policy Agent (OPA) | https://github.com/open-policy-agent/opa | Go | Apache-2.0 | Silnik polityk (Rego) dla authz tool-calls | Dojrzały, testy polityk, dane+reguły | Nowy język (Rego), osobny proces | M | Tak | Średnia (opcja) |
| Cedar (AWS) | https://github.com/cedar-policy/cedar | Rust (+ Java bindings) | Apache-2.0 | Polityki authz (principal/action/resource) | Czytelna składnia, analizowalność | Mało przykładów dla MCP | M | Tak | Średnia (niezweryfikowano stanu bindingów Java) |
| Invariant Guardrails / Snyk Agent Scan (dawniej mcp-scan) | https://github.com/invariantlabs-ai/mcp-scan | Python | Apache-2.0 | Skan konfiguracji MCP, pinning, proxy mode | Dedykowany MCP; ma tryb proxy | Część funkcji korzysta z zewnętrznego API Invariant/Snyk (niezweryfikowane które) | M | Częściowo | Wysoka dla MCP-INT, niska dla allowlisty |
| Trail of Bits mcp-context-protector | https://github.com/trailofbits/mcp-context-protector | Python | (niezweryfikowana) | Wrapper bezpieczeństwa MCP: pinning, guardrails | Wzorzec architektoniczny dla proxy | Prototyp | M | Tak | Wzór (inspiracja) |
| Stripe Smokescreen | https://github.com/stripe/smokescreen | Go | MIT | Egress proxy blokujące SSRF (wskazane w spec MCP) | Gotowe | Dodatkowy proces | S–M | Tak | Opcjonalnie (egress) |

## 11. Proposed Control
Reguły (priorytet rośnie z liczbą):
- **MCP-ALLOW-001** Serwer MCP musi być w rejestrze (kanoniczny host, env, pin wersji). BLOCK.
- **MCP-ALLOW-002** Allowlista narzędzi per principal (+ per env), deny-by-default, nazwy w namespace `server.tool`. BLOCK.
- **MCP-ALLOW-003** Separacja read/write: narzędzia klasy `write|admin|exec` zabronione w sesji `mode=read_only`; wymagają `CHALLENGE` w `prod`. 
- **MCP-ALLOW-004** Walidacja URI zasobów (schemat, kanonizacja, korzenie, SSRF). BLOCK.
- **MCP-ALLOW-005** Filtrowanie `tools/list`/`resources/list` do widoku principala.
- **MCP-ALLOW-006** Zakaz token passthrough (walidacja `aud`, brak forwardowania nagłówka Authorization klienta).
- **MCP-ALLOW-007** Reguła toksycznego przepływu (uproszczony taint): po wywołaniu narzędzia `data_class=private` w sesji zakaz narzędzi `network-egress` do nowych odbiorców → REVIEW/BLOCK.
- **MCP-ALLOW-008** Shadow MCP: połączenie do niezarejestrowanego endpointu → REVIEW (discovery) lub BLOCK (enforce).
- **MCP-ALLOW-009** Rate limit narzędzi `write` per principal.
Dane rejestru są w tabeli Postgres `mcp_servers`/`mcp_tools`/`mcp_grants` + plik YAML jako seed; dashboard pokazuje trafienia per reguła.

## 12. Example Configuration
```yaml
- id: MCP-ALLOW-001
  name: MCP server must be registered
  category: mcp
  enabled: true
  priority: 10
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: registry_lookup, registry: mcp_servers, key: canonical_host_port }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [MCP09, MCP04, ASI04], references: ["https://owasp.org/www-project-mcp-top-10/"] }

- id: MCP-ALLOW-002
  name: Tool allowlist per principal (deny by default)
  category: mcp
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: grant_lookup, table: mcp_grants, key: [principal, server_id, tool, env], default: deny }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [MCP02, MCP07], references: [] }

- id: MCP-ALLOW-003
  name: Write/exec tools forbidden in read-only session or prod
  category: mcp
  enabled: true
  priority: 30
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["prod"] }
  conditions: { tool_class_in: [write, admin, exec] }
  matcher: { type: tool_class, source: registry }   # NIE ufamy annotations z serwera
  action: CHALLENGE
  severity: HIGH
  threshold: null
  exceptions: [{ principal: "agent-ops", tools: ["ticket.update"] }]
  metadata: { owasp: [MCP02], references: ["https://modelcontextprotocol.io/specification/2025-06-18/server/tools"] }

- id: MCP-ALLOW-004
  name: Resource URI validation
  category: mcp
  enabled: true
  priority: 25
  scope: { direction: [input], agents: ["*"], tools: ["resources/read", "resources/subscribe"], environments: ["*"] }
  conditions: {}
  matcher:
    type: uri_policy
    allowed_schemes: [https, file]
    file_roots: ["/srv/agent-workdir"]
    compare: path_segments      # nie startsWith na stringach
    ssrf_guard: true
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [MCP05], references: ["CVE-2025-53110"] }

- id: MCP-ALLOW-007
  name: No egress tool after private-data read in same session
  category: mcp
  enabled: true
  priority: 60
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: { session_taint: private_data }
  matcher: { type: tool_class, value: network-egress }
  action: REVIEW
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [MCP10, LLM06], references: ["https://invariantlabs.ai/blog/mcp-github-vulnerability"] }
```

## 13. Example Requests
```json
{ "req": {"agent":"support-bot","env":"prod","jsonrpc":"2.0","method":"tools/call","params":{"name":"crm.read_ticket","arguments":{"id":"T-1"}}}, "expect": {"action":"ALLOW"} }
{ "req": {"agent":"support-bot","env":"prod","method":"tools/call","params":{"name":"shell.exec","arguments":{"cmd":"ls"}}}, "expect": {"action":"BLOCK","rule":"MCP-ALLOW-002"} }
{ "req": {"agent":"support-bot","env":"prod","method":"resources/read","params":{"uri":"file:///srv/agent-workdir-evil/secret.txt"}}, "expect": {"action":"BLOCK","rule":"MCP-ALLOW-004"} }
{ "req": {"agent":"support-bot","env":"prod","server":"http://10.0.0.7:9000/mcp","method":"tools/call","params":{"name":"x.y"}}, "expect": {"action":"BLOCK","rule":"MCP-ALLOW-001"} }
{ "req": {"agent":"support-bot","env":"prod","method":"tools/call","params":{"name":"mail.send","arguments":{"to":"a@b.c"}}, "session":{"taint":"private_data"}}, "expect": {"action":"REVIEW","rule":"MCP-ALLOW-007"} }
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| MCP-ALLOW-T001 | `tools/call crm.read_ticket` dla agenta z grantem | ALLOW |
| MCP-ALLOW-T002 | Tool spoza grantu | BLOCK MCP-ALLOW-002 |
| MCP-ALLOW-T003 | Serwer spoza rejestru (nowy host) | BLOCK MCP-ALLOW-001 |
| MCP-ALLOW-T004 | Serwer w rejestrze, ale env=prod a wpis tylko dla dev | BLOCK |
| MCP-ALLOW-T005 | Write tool w sesji read-only | BLOCK/CHALLENGE |
| MCP-ALLOW-T006 | `resources/read file:///etc/passwd` | BLOCK 004 |
| MCP-ALLOW-T007 (bypass) | `file:///srv/agent-workdir/../../etc/passwd`, `%2e%2e%2f`, `%252e` | BLOCK |
| MCP-ALLOW-T008 (bypass) | `file:///srv/agent-workdir-evil/x` (prefiks) | BLOCK |
| MCP-ALLOW-T009 | `resources/read http://169.254.169.254/latest/meta-data/` | BLOCK |
| MCP-ALLOW-T010 (bypass) | `http://[::ffff:169.254.169.254]/`, `http://0xA9FEA9FE/`, `http://2852039166/` | BLOCK |
| MCP-ALLOW-T011 | `javascript:alert(1)`, `data:text/html,...` jako URI | BLOCK |
| MCP-ALLOW-T012 (bypass) | Nazwa `read_fiIe` (homoglif) / `read_file​` | BLOCK (nie dopasowuje do `read_file`) |
| MCP-ALLOW-T013 | Dwa serwery eksportują `send_email` bez namespace | BLOCK/odrzucenie rejestracji |
| MCP-ALLOW-T014 | Tool z `annotations.readOnlyHint=true` ale klasa `write` w rejestrze | traktowany jako write |
| MCP-ALLOW-T015 | Token z `aud` innego zasobu | BLOCK (401) |
| MCP-ALLOW-T016 (negatywny) | Zmiana YAML (usunięcie grantu) bez restartu | następne żądanie BLOCK (hot-reload) |
| MCP-ALLOW-T017 (edge) | `tools/list` zwraca tylko dozwolone narzędzia | brak ukrytych narzędzi w odpowiedzi |

## 15. Sources
- Koi Security, postmark-mcp — https://koi.ai/blog/postmark-mcp-npm-malicious-backdoor-email-theft — wrzesień 2025 — `[REAL-ATTACK]` (strona niedostępna dla fetch; treść potwierdzona przez media)
- Dark Reading — https://www.darkreading.com/application-security/malicious-mcp-server-exfiltrates-secrets-bcc — 2025 — `[REAL-ATTACK]`
- SC World — https://www.scworld.com/news/open-source-mcp-server-package-caught-stealing-emails — 2025 — `[REAL-ATTACK]`
- Cymulate EscapeRoute — https://cymulate.com/blog/cve-2025-53109-53110-escaperoute-anthropic/ — 2025 — `[CONFIRMED-VULN]`
- Oligo, CVE-2025-49596 — https://www.oligo.security/blog/critical-rce-vulnerability-in-anthropic-mcp-inspector-cve-2025-49596 — 2025-06 — `[CONFIRMED-VULN]`
- Simon Willison, Supabase MCP lethal trifecta — https://simonwillison.net/2025/Jul/6/ — 2025-07-06 — `[POC]`
- Invariant Labs, GitHub MCP — https://invariantlabs.ai/blog/mcp-github-vulnerability — 2025-05-26 — `[POC]`
- OWASP MCP Top 10 — https://owasp.org/www-project-mcp-top-10/ — beta 2025 — `[RESEARCH]`
- MCP Security Best Practices — https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices — bieżąca — `[MITIGATION]`
- MCP Tools spec — https://modelcontextprotocol.io/specification/2025-06-18/server/tools — 2025-06-18 — `[MITIGATION]`
- MCP Resources spec — https://modelcontextprotocol.io/specification/2025-06-18/server/resources — 2025-06-18 — `[MITIGATION]`
