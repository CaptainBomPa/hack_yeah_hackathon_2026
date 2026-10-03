# Uwierzytelnianie callerów (API key / JWT / OAuth2 / mTLS), tożsamość użytkownika vs agenta, delegacja
> **ID:** AUTHN-001..008  | **Kategoria:** authn | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** Request → Canonicalization → **AuthN** (pierwsza kontrola po kanonikalizacji, przed Policy/Rules); dodatkowo tool-call (delegacja do MCP)

## 1. Overview
Control Layer jest jedynym punktem wejścia do chronionego LLM (Ollama na Pi nie ma uwierzytelniania — patrz §3) i do narzędzi MCP. Każda reguła w pozostałych kontrolach (budżety, authz, tenant isolation, audit) zakłada, że wiemy **kto** woła: musimy zbudować zaufany obiekt `Principal` zanim jakakolwiek polityka zostanie ewaluowana. Co chronimy:
- sam gateway (brak anonimowego dostępu, brak podszywania się pod innego callera),
- poświadczenia downstream (tokeny do MCP/upstream API, klucze providerów),
- rozliczalność (audit log musi wskazywać callera, a przy delegacji — również użytkownika, w imieniu którego działa agent).

Rozróżniamy typy podmiotów: `user` (człowiek, z UI), `agent` (autonomiczny klient/LLM-agent z własną tożsamością), `service` (system-system). Agent działający *w imieniu* usera niesie **dwie** tożsamości (`sub` = user, `act`/`azp` = agent) — nigdy nie wolno ich zlewać w jedną.

## 2. Threat / Attack
1. **Brak/słabe uwierzytelnienie** – wystawiony endpoint LLM/gateway bez auth; zgadywanie/wyciek kluczy API (klucze w repo, logach, historii promptów).
2. **Fałszowanie JWT** – `alg=none`, algorithm confusion (RS256→HS256 z kluczem publicznym jako sekretem), podmiana `kid`/`jku`/`x5u` na URL atakującego, brak sprawdzenia `exp`/`nbf`/`iss`/`aud`, kryptograficzne błędy bibliotek (psychic signatures).
3. **Cache/lookup collision** – tożsamość wyprowadzona ze skróconego/nieunikalnego klucza cache (CVE-2026-35030 w LiteLLM).
4. **Confused deputy / token passthrough** – gateway/serwer MCP przyjmuje token wystawiony dla innego zasobu i przekazuje go dalej (lub używa własnych, szerszych uprawnień w imieniu callera z niższymi uprawnieniami).
5. **Token passthrough jako obejście kontroli** – klient dostaje token do downstream API i omija gateway (rate-limit, audit).
6. **Session hijacking** – ID sesji MCP/chatu traktowane jako uwierzytelnienie.
7. **Zlanie tożsamości agenta i usera** – agent działa „jako user” z pełnymi prawami usera, prompt injection ⇒ eskalacja (OWASP ASI03 Identity & Privilege Abuse).
8. **Wstrzyknięcie nagłówków tożsamości** – klient sam wysyła `X-User-Id`/`X-Forwarded-For`, a downstream im ufa (CVE-2025-41235 / CVE-2026-47825 w Spring Cloud Gateway — nasz własny stack!).
9. **Brute-force / credential stuffing** na endpoint logowania i kluczy.

## 3. Real-World Evidence
- **[CONFIRMED-VULN] LiteLLM CVE-2026-35030** – auth bypass/privilege escalation: przy `enable_jwt_auth` cache userinfo OIDC kluczowany `token[:20]`; JWT z tym samym algorytmem mają identyczny prefiks nagłówka → atakujący kuje token trafiający w cache innego usera i dziedziczy jego sesję. Fix: klucz = `sha256(token)`, v1.83.0. Komponent: proxy LiteLLM (gateway LLM). Zapobieganie: nigdy nie kluczować tożsamości skrótem/prefiksem; weryfikować podpis *przed* użyciem cache. Źródło: https://research.averlon.ai/vulnerability-intelligence/cve/CVE-2026-35030 , https://docs.litellm.ai/blog/security-hardening-april-2026
- **[CONFIRMED-VULN] LiteLLM CVE-2026-42208 (GHSA-r75f-5x8p-qvmc, CVSS 9.3)** – SQL injection w ścieżce weryfikacji Bearer tokena (wartość klucza wklejana do zapytania). Pre-auth dostęp do tabel z virtual keys, kredencjałami providerów i configiem; obserwowane próby exploitacji ~36 h po publikacji advisory. Fix: 1.83.7. Lekcja: ścieżka AuthN to najbardziej krytyczny kod – parametryzowane zapytania, klucze porównywane po hashu. Źródło: https://labs.cloudsecurityalliance.org/research/csa-research-note-litellm-pre-auth-sqli-20260428/
- **[CONFIRMED-VULN] CVE-2025-49596 MCP Inspector (CVSS 9.4)** – interfejs/proxy na localhost bez uwierzytelniania ⇒ RCE przez „NeighborJacking”/CSRF z przeglądarki; fix w 0.14.1. Źródło: https://asec.ahnlab.com/en/88812/ , https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html (zbiorczo z CVE-2025-6514).
- **[CONFIRMED-VULN] CVE-2025-6514 mcp-remote (CVSS 9.6)** – OS command execution przy połączeniu z niezaufanym serwerem MCP (URL autoryzacji nie był sanitowany). Źródło: https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html (lipiec 2025).
- **[CONFIRMED-VULN] Spring Cloud Gateway CVE-2025-41235 / CVE-2026-47825** – gateway przekazuje `X-Forwarded-For`/`Forwarded` od niezaufanych klientów downstream (fałszowanie IP/hosta/protokołu → bypass reguł opartych na IP). Fixy: 4.2.3 (41235); 3.1.13/4.1.13/4.2.9/4.3.5/5.0.2 (47825). Dotyczy bezpośrednio naszego stacku. Źródło: https://advisories.gitlab.com/maven/org.springframework.cloud/spring-cloud-gateway-server/CVE-2025-41235/ , https://www.herodevs.com/vulnerability-directory/cve-2026-47825 (szczegóły 47825 z agregatora – zweryfikuj w spring.io/security przed cytowaniem).
- **[CONFIRMED-VULN] CVE-2015-9235 (jsonwebtoken <4.2.2)** – algorithm confusion RS→HS. Źródło: https://nvd.nist.gov/vuln/detail/CVE-2015-9235
- **[CONFIRMED-VULN] CVE-2022-21449 „Psychic Signatures” (Java 15–18)** – ECDSA akceptuje podpis (r=0,s=0) ⇒ forgowanie JWT/SAML/OIDC; patch kwiecień 2022. Istotne, bo backend jest w Javie: aktualna JVM + allowlista algorytmów. Źródło: https://neilmadden.blog/2022/04/19/cve-2022-21449-psychic-signatures-in-java/
- **[MITIGATION] RFC 8725 (JWT BCP)** – jawna allowlista algorytmów, walidacja `iss`/`aud`, odrzucanie `none`, jednoznaczne typowanie tokenów. https://datatracker.ietf.org/doc/html/rfc8725
- **[RESEARCH] MCP Authorization spec (2025-06-18)** – serwer MCP = OAuth 2.1 resource server; MUST walidować, że token wystawiono *dla niego* (aud, RFC 8707 `resource`), MUST NOT akceptować/przekazywać innych tokenów (token passthrough zabroniony), PKCE, krótkie tokeny, rotacja refresh tokenów dla public clients, tokeny tylko w nagłówku `Authorization`, nie w query. https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization
- **[RESEARCH] MCP Security Best Practices** – opis confused deputy w proxy MCP (statyczny client_id + dynamic client registration + cookie zgody ⇒ kradzież kodu), token passthrough (ominięcie kontroli, zepsuty audit trail), session hijacking („MUST NOT use sessions for authentication”, wiązanie `<user_id>:<session_id>`), scope minimization. https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices
- **[REAL-ATTACK/ekspozycja] Ollama bez auth** – Ollama „nie wspiera uwierzytelniania out-of-the-box”, wiele instancji wystawionych do Internetu; CVE-2024-37032 „Probllama” (path traversal w `/api/pull` → RCE, fix 0.1.34). Wniosek dla nas: Pi/Ollama musi być osiągalny wyłącznie z gateway (firewall/VLAN), nigdy z klientów. Źródło: https://wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032
- **[RESEARCH] OWASP Agentic Top 10 2026: ASI03 Identity & Privilege Abuse** (agenty dziedziczą cache'owane/ludzkie poświadczenia i eskalują). https://www.giskard.ai/knowledge/owasp-top-10-for-agentic-application-2026 (streszczenie wtórne – oryginał na genai.owasp.org).

## 4. Deterministic Detection
Uwierzytelnianie jest w 100% deterministyczne. Techniki:
- **API key**: format `ak_<keyId>_<secret>`; w DB tylko `keyId` + `sha256(secret)` (lub HMAC z pepperem); porównanie constant-time (`MessageDigest.isEqual`). Wysoka entropia (≥128 bit) ⇒ szybki hash wystarcza (bcrypt niepotrzebny dla losowych kluczy). Status: `active|rotating|revoked`, `expires_at`, `scopes`, `tenant`, `env`.
- **JWT**: parser z **allowlistą algorytmów per issuer** (np. tylko `RS256`/`ES256`/`EdDSA`; HS256 tylko dla kluczy lokalnych i nigdy na tym samym issuerze co RS); odrzucenie `alg=none`, nagłówków `jku`/`x5u`/`jwk` (ignorować, klucze tylko z skonfigurowanego JWKS); `kid` jako ścisły lookup w mapie (żadnych konkatenacji do ścieżek/SQL); `iss` exact-match, `aud` musi zawierać identyfikator gateway, `exp`/`nbf` z tolerancją ≤60 s, `iat` rozsądny, `typ`/`token_use` (access vs id token), `jti` + krótki cache replay dla jednorazowych tokenów, limit rozmiaru nagłówka (np. 8 KB).
- **JWKS**: cache z TTL i limitem odświeżeń (nieznany `kid` ⇒ max 1 refetch/min, żeby nie dać DoS); w trybie offline JWKS z pliku.
- **mTLS**: terminacja w Spring (`server.ssl.client-auth=need`) lub proxy; mapowanie `SAN/CN` → `callerId` z allowlisty; opcjonalnie binding tokena do certyfikatu (RFC 8705, `cnf.x5t#S256`).
- **Nagłówki tożsamości**: gateway **usuwa** wszystkie przychodzące `X-Principal-*`, `X-User-*`, `X-Forwarded-*` (poza zaufanym proxy) i ustawia własne po AuthN; `RemoveRequestHeader`/własny filtr. Trusted proxies jawnie skonfigurowane.
- **Delegacja**: `Principal{ subject(user), actor(agent), tenant, env, scopes, authMethod, tokenId }`; z JWT: `sub`=user, `act.sub` lub `azp`/`client_id`=agent (RFC 8693). Efektywne uprawnienia = **przecięcie** uprawnień usera i agenta (nigdy suma).
- **Token exchange zamiast passthrough**: do MCP/downstream gateway używa *własnego* tokena z `aud`=serwer docelowy (client credentials lub RFC 8693 token exchange); przychodzący token klienta nigdy nie opuszcza gateway. Deterministyczny test: żaden nagłówek `Authorization` z requestu nie może trafić do requestu upstream (filtr `RemoveRequestHeader=Authorization` + podmiana).
- **Anty-brute-force**: licznik nieudanych prób per IP/keyId, opóźnienie wykładnicze, blokada; odpowiedzi 401 jednolite (brak enumeracji keyId).
- **Rotacja**: dwa aktywne klucze na caller (overlap), `last_used_at`, alert na użycie klucza `revoked`/stary po rotacji; sekrety skanowane przez kontrolę SECRETS w promptach (klucz API gateway w treści promptu ⇒ REDACT + zdarzenie).

## 5. Detection Pipeline
1. Request → Canonicalization (normalizacja nagłówków, usunięcie duplikatów `Authorization`, odrzucenie wielokrotnych nagłówków tożsamości).
2. **Strip** nagłówków tożsamości pochodzących od klienta.
3. Ekstrakcja poświadczenia (`Authorization: Bearer|ApiKey`, `X-API-Key`, cert klienta). Tokeny w query-string ⇒ BLOCK (MCP spec).
4. Walidacja wg metody (kroki z §4) ⇒ `Principal` lub `401` (+ `WWW-Authenticate`, w trybie MCP z `resource_metadata`).
5. Wzbogacenie: tenant, env, role, budżety (z Postgres, cache z hot-reload).
6. Zapis `Principal` w `ServerWebExchange` attributes (jedyne źródło tożsamości dla dalszych filtrów) + trace id do audit logu.
7. Dalej: Policy (AUTHZ) → Rules → LLM/MCP. Przy wywołaniu MCP/downstream: token exchange (kroki w §4).

## 6. Possible Actions
| Sytuacja | Akcja |
|---|---|
| brak/niepoprawne poświadczenie | BLOCK 401 (audit: `authn.failed`, bez sekretu) |
| poprawny token, zły `aud`/`iss` | BLOCK 401, severity HIGH (próba reuse tokenu) |
| `alg=none`/`jku`/podejrzany `kid` | BLOCK 401 + QUARANTINE źródła (CRITICAL) |
| N nieudanych prób | RATE_LIMIT / czasowy BLOCK IP+keyId |
| klucz po terminie / revoked | BLOCK 401 + alert |
| delegacja bez `act`, agent bez zgody usera | CHALLENGE (wymagana re-autoryzacja) lub BLOCK 403 |
| operacje wysokiego ryzyka (zmiana polityki) | CHALLENGE (step-up: mTLS/MFA admina) |
| klucz gateway wykryty w treści promptu | REDACT + REVIEW + wymuszona rotacja |

## 7. Bypass / Limitations
- Kradzież ważnego klucza/tokena – AuthN tego nie wykryje; łagodzenie: krótkie TTL, wiązanie z IP/cert (RFC 8705), anomalie użycia (częściowo deterministyczne: nowy ASN/geo, nagły skok), rotacja.
- Błędy w bibliotece JWT/JVM (psychic signatures) – aktualizacje, testy regresyjne z znanymi złośliwymi tokenami.
- Statyczne klucze API w demo są ryzykiem – ale jury testuje offline; trzymać `bootstrap` klucze w configu z hashem, nie plaintext.
- FP: skew zegara (Pi bez NTP!) ⇒ legalne tokeny odrzucone; wymagać NTP/chrony i tolerancji.
- Wydajność: weryfikacja podpisu RSA ≈ 0,05–0,2 ms; JWKS cache; hash klucza ≈ µs – pomijalne.
- Delegacja `on-behalf-of` opiera się na zaufaniu do wystawcy tokenów; gateway nie może sprawdzić, czy agent *naprawdę* uzyskał zgodę usera poza tokenem.

## 8. Deterministic vs AI
AuthN = wyłącznie deterministyczne. AI nie powinno nigdy decydować o tożsamości. Sidecar może co najwyżej dostarczyć sygnał anomalii zachowania (np. typ promptów nietypowy dla agenta) jako wejście do CHALLENGE – nigdy jako zastępstwo walidacji.

## 9. Implementation Options
- **Spring Security OAuth2 Resource Server (reactive)** w Spring Cloud Gateway: `NimbusReactiveJwtDecoder` z `JwtValidators` (issuer + audience validator + algorytmy w `NimbusJwtDecoder.withJwkSetUri(..).jwsAlgorithm(RS256)`); `ServerHttpSecurity` + `SecurityWebFilterChain`. Uwaga: domyślnie Spring nie weryfikuje `aud` – dodać `JwtClaimValidator`.
- Własny `GatewayFilterFactory` `ApiKeyAuthFilter` (order −100, po `RemoveRequestHeader`), lookup w Postgres z cache Caffeine (TTL 30 s, invalidacja przy hot-reload), klucz cache = **pełny** `sha256(key)`.
- mTLS: `spring.ssl.bundle` + `client-auth: want/need`; mapowanie certów z YAML.
- Python sidecar: nie powinien samodzielnie uwierzytelniać userów; zaufanie przez wspólny sekret/mTLS gateway↔sidecar, nasłuch tylko na localhost/sieci docker.
- Pi/Ollama: firewall wpuszczający tylko IP gateway (+ opcjonalnie reverse-proxy z kluczem).

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| Spring Security OAuth2 Resource Server | https://spring.io/projects/spring-security | Java | Apache-2.0 | JWT/opaque token w gateway | natywne, dojrzałe | domyślnie brak `aud` | niska | tak (JWKS z pliku) | WYSOKA |
| Nimbus JOSE+JWT | https://connect2id.com/products/nimbus-jose-jwt | Java | Apache-2.0 | parsowanie/weryfikacja JWT | allowlista alg, JWKS | – | niska | tak | WYSOKA |
| Keycloak | https://www.keycloak.org | Java | Apache-2.0 | IdP (OIDC, token exchange, mTLS) | pełny IdP, RFC 8693 | ciężki na hackathon | średnia | tak | ŚREDNIA (opcjonalnie) |
| Ory Hydra/Oathkeeper | https://www.ory.sh | Go | Apache-2.0 | OAuth2 server / identity proxy | lekkie | dodatkowy komponent | średnia | tak | NISKA |
| LiteLLM virtual keys | https://docs.litellm.ai | Python | MIT (core) | referencyjny model kluczy per team | gotowe zarządzanie kluczami | historia CVE (patrz §3) | – | tak | REFERENCJA |
| mcp-auth / MCP SDK auth helpers | https://modelcontextprotocol.io | różne | MIT | referencja OAuth dla MCP | zgodność ze specyfikacją | niezweryfikowana dojrzałość | – | tak | REFERENCJA |

## 11. Proposed Control
Reguły (kolejność = priorytet):
- **AUTHN-001** Wymagane uwierzytelnienie (deny anonymous) na wszystkich ścieżkach poza `/health`.
- **AUTHN-002** API key: hash+constant-time, status/expiry, rotacja z overlap.
- **AUTHN-003** JWT: allowlista alg, iss/aud/exp/nbf, zakaz `none`/`jku`/`x5u`/`jwk`, ścisły `kid`.
- **AUTHN-004** Strip nagłówków tożsamości i `Forwarded` od niezaufanych; zaufane proxy z listy.
- **AUTHN-005** Zakaz token passthrough: `Authorization` klienta nie trafia do upstream; token exchange/własne poświadczenie z `aud`.
- **AUTHN-006** Model `Principal` z rozdzieleniem `user`/`agent`, uprawnienia = przecięcie.
- **AUTHN-007** Anty-brute-force / lockout / jednolite 401.
- **AUTHN-008** Wykrycie klucza gateway/tokena w promptach i odpowiedziach (współdzielone z SECRETS).
Hot-reload: tabela kluczy i issuerów w Postgres + `@RefreshScope`/watcher; zmiana „revoked” działa natychmiast (cache-invalidation po zdarzeniu).

## 12. Example Configuration
```yaml
- id: AUTHN-003
  name: JWT strict validation
  category: authn
  enabled: true
  priority: 10
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: { auth_method: jwt }
  matcher:
    type: jwt-validator
    allowed_algs: [RS256, ES256]
    forbid_headers: [jku, x5u, jwk]
    issuers:
      - iss: https://idp.local/realms/ctrl
        jwks: file:/etc/ctrl/jwks.json
        audience: ai-control-layer
    clock_skew_seconds: 60
  action: BLOCK            # 401
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06, ASI03], references: [RFC8725, CVE-2026-35030] }
- id: AUTHN-005
  name: No token passthrough to upstream
  category: authn
  enabled: true
  priority: 20
  scope: { direction: [input], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: header-policy, strip_to_upstream: [Authorization, Cookie], inject: upstream_token_exchange }
  action: ALLOW
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [ASI03], references: ["MCP spec 2025-06-18 authorization"] }
- id: AUTHN-007
  name: Failed-auth throttling
  category: authn
  enabled: true
  priority: 15
  scope: { direction: [input], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: counter, key: "ip+keyId", event: authn.failed }
  action: RATE_LIMIT
  severity: MEDIUM
  threshold: { count: 5, window_seconds: 60, lockout_seconds: 300 }
  exceptions: []
  metadata: { owasp: [], references: [] }
```

## 13. Example Requests
```json
{ "req": {"path":"/v1/chat/completions","headers":{}}, "expect": {"action":"BLOCK","status":401,"policy":"AUTHN-001"} }
{ "req": {"headers":{"Authorization":"Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9."}}, "expect": {"action":"BLOCK","status":401,"policy":"AUTHN-003"} }
{ "req": {"headers":{"Authorization":"Bearer <RS256 JWT aud=other-service>"}}, "expect": {"action":"BLOCK","status":401,"policy":"AUTHN-003"} }
{ "req": {"headers":{"X-Api-Key":"ak_demo_valid","X-User-Id":"admin"}}, "expect": {"action":"ALLOW","note":"X-User-Id stripped; principal=demo"} }
{ "req": {"headers":{"Authorization":"Bearer <valid>"},"tool":"mcp.files.read"}, "expect": {"action":"ALLOW","upstream_authorization":"gateway-issued, aud=mcp-files"} }
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| AUTHN-T001 | brak nagłówka auth | 401, AUTHN-001 |
| AUTHN-T002 | poprawny API key | ALLOW, principal w audit |
| AUTHN-T003 | revoked API key | 401, alert |
| AUTHN-T004 | JWT `alg=none` | 401 CRITICAL |
| AUTHN-T005 | JWT RS256 przepisany na HS256 (klucz publiczny jako sekret) | 401 |
| AUTHN-T006 | JWT z `jku` wskazującym na URL atakującego | 401, brak fetch (sprawdzić brak ruchu wychodzącego) |
| AUTHN-T007 | JWT wygasły / `nbf` w przyszłości (poza skew) | 401 |
| AUTHN-T008 | JWT `aud` innego zasobu | 401 |
| AUTHN-T009 | dwa tokeny z identycznym 20-znakowym prefiksem, drugi z błędnym podpisem (regresja CVE-2026-35030) | drugi 401 |
| AUTHN-T010 | klucz API `' OR 1=1--` (regresja CVE-2026-42208) | 401, brak błędu SQL |
| AUTHN-T011 | `X-Forwarded-For: 127.0.0.1` od klienta | nagłówek usunięty/nadpisany, reguły IP nie ominięte |
| AUTHN-T012 | token w query `?access_token=` | BLOCK |
| AUTHN-T013 | 6 błędnych kluczy w 60 s | 429/lockout |
| AUTHN-T014 | request tool-call z tokenem klienta | upstream otrzymuje inny token (aud=MCP), nie ten klienta |
| AUTHN-T015 | agent z `act` bez uprawnienia usera do zasobu | 403 (przecięcie uprawnień) |
| AUTHN-T016 (edge) | JWT z `kid` zawierającym `../` lub `' ` | 401, brak wyjątku |
| AUTHN-T017 (edge) | token dwukrotnie w nagłówku (`Authorization` x2) | BLOCK 400 |
| AUTHN-T018 (negative) | prawidłowy token tuż przed `exp` | ALLOW |

## 15. Sources
- MCP Authorization spec 2025-06-18 — https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization — pobrano 2026-10 — [RESEARCH]
- MCP Security Best Practices — https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices — [RESEARCH]
- CVE-2026-35030 LiteLLM — https://research.averlon.ai/vulnerability-intelligence/cve/CVE-2026-35030 ; https://docs.litellm.ai/blog/security-hardening-april-2026 — 2026 — [CONFIRMED-VULN]
- CVE-2026-42208 LiteLLM — https://labs.cloudsecurityalliance.org/research/csa-research-note-litellm-pre-auth-sqli-20260428/ — 2026-04-28 — [CONFIRMED-VULN]
- CVE-2025-49596 / 6514 — https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html ; https://asec.ahnlab.com/en/88812/ — 2025-07 — [CONFIRMED-VULN]
- CVE-2025-41235 — https://advisories.gitlab.com/maven/org.springframework.cloud/spring-cloud-gateway-server/CVE-2025-41235/ — 2025-05/07 — [CONFIRMED-VULN]; CVE-2026-47825 — https://www.herodevs.com/vulnerability-directory/cve-2026-47825 — [CONFIRMED-VULN, źródło wtórne]
- CVE-2015-9235 — https://nvd.nist.gov/vuln/detail/CVE-2015-9235 — [CONFIRMED-VULN]
- CVE-2022-21449 — https://neilmadden.blog/2022/04/19/cve-2022-21449-psychic-signatures-in-java/ — 2022-04-19 — [CONFIRMED-VULN]
- RFC 8725 — https://datatracker.ietf.org/doc/html/rfc8725 — 2020-02 — [MITIGATION]
- Probllama CVE-2024-37032 — https://wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032 — 2024-06 — [CONFIRMED-VULN]
- OWASP Top 10 for Agentic Applications 2026 (ASI03) — https://www.giskard.ai/knowledge/owasp-top-10-for-agentic-application-2026 — 2025-12 — [RESEARCH, źródło wtórne]
- RFC 8693 (Token Exchange), RFC 8705 (mTLS-bound tokens), RFC 8707 (Resource Indicators) — nie pobierane osobno, powołane z MCP spec/wiedzy — niezweryfikowane w tej sesji.
