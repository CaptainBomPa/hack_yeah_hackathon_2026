# Auth — plan wdrożenia (lokalny)

Wersja wykonawcza dla osób implementujących. Decyzje D1–D6 i uzasadnienie z PDF są w
[`01-minimum.md`](01-minimum.md). Architektura: [`VISION.md`](../../VISION.md).

## 1. Przepływy

```text
Człowiek (czat, admin)                         Maszyna (agent, runner, jury)
  przeglądarka                                   HTTP client
     |  POST /login (formularz)                     |  X-API-Key: cl_<prefiks>_<sekret>
     |  cookie SESSION (HttpOnly)                   |
     v                                              v
 [Gateway :8000] ------------------------------------+
     |  1. filtr klucza API (principal key:<nazwa>)
     |  2. sesja (principal role:<rola> z app_user)
     |  3. decyzja: policy.yaml, allow/deny per principal
     |  4. wywołanie modelu, jeśli dozwolone
     v
 [Ollama: sieć compose, nazwa `ollama`] / [zewnętrzny LLM]
```

Zasady:
- Gateway nigdy nie przekazuje do modelu nagłówków uwierzytelniających (`Authorization`,
  `X-API-Key`, `Cookie`).
- Nagłówki `X-Caller-*` od klienta są usuwane na wejściu. `X-Caller-Id` ustawia gateway po
  uwierzytelnieniu, np. `key:runner` albo `user:<login>`.
- Audyt zapisuje principal, nie surowy klucz, hasło ani email (VISION §6).

## 2. Zależności (`backend/build.gradle`)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
```

Nie dodajemy `spring-boot-starter-security-oauth2-client` ani `...-oauth2-resource-server`
(D2, D6). Nie dodajemy Keycloak, Spring Authorization Server ani biblioteki JWT.

Nazwy startrów Boot 4.1 przy dodawaniu sprawdzamy w dokumentacji, bo moduły security zostały
rozbite. Stare nazwy bez `security-` są deprecated.

## 3. Konfiguracja

```yaml
# application.yml (wspólne)
control-layer:
  policy:
    file: ${CONTROL_LAYER_POLICY_FILE:config/policy.yaml}
  auth:
    bootstrap:
      admin-password: ${CL_ADMIN_PASSWORD:}      # konto admin tworzone, gdy brak w bazie
      demo-password: ${CL_DEMO_PASSWORD:}        # konto chat tworzone, gdy brak w bazie
      api-key: ${CL_BOOTSTRAP_API_KEY:}          # klucz runnera (hash zapisywany w bazie)

server:
  reactive:
    session:
      cookie:
        secure: ${AUTH_COOKIE_SECURE:false}      # true tylko za HTTPS
```

- Lokalnie (`local`): `AUTH_COOKIE_SECURE=false`, baza H2.
- Pi (`prod`): `AUTH_COOKIE_SECURE=false`, jeśli dostęp przez HTTP w sieci. `true` tylko z tunelem
  HTTPS.
- Zmienne w `.env` na Pi (uprawnienia `600`, nie w repo): `CL_ADMIN_PASSWORD`, `CL_DEMO_PASSWORD`,
  `CL_BOOTSTRAP_API_KEY`, klucze do zewnętrznych LLM-ów, `AUTH_COOKIE_SECURE`.
- Sprawdzić, że `.env` jest w `.gitignore`, przed commitem.

## 4. Polityka uprawnień (`config/policy.yaml`)

Sekcja `principals` w centralnej polityce (CRITERIA §4.1). Hot reload zgodnie z VISION §4.

```yaml
principals:
  key:runner:                       # klucz API o nazwie "runner"
    allow:
      models: ["qwen2.5:1.5b-instruct-q4_K_M"]
      tools: ["demo.search"]
      memory: ["public/*"]
    deny:
      tools: ["demo.exec"]
  role:chat:                        # zalogowany użytkownik z rolą chat
    allow:
      models: ["qwen2.5:1.5b-instruct-q4_K_M"]
  role:admin:
    allow:
      endpoints: ["/api/**"]
```

Reguły ewaluacji:
- Principal to `key:<nazwa klucza>` dla kluczy API albo `role:<rola>` dla konta lokalnego.
- `deny` wygrywa nad `allow`.
- Brak wpisu dla principal, modelu, narzędzia albo zasobu oznacza odmowę (fail-closed).
- Wynik: `block` z powodem w `ControlResult` i wpisem w audycie, zanim model zostanie wywołany.
- Model: `allow.models` zastępuje obecną listę `control-layer.models` w `application.yml`. Trzeba
  wybrać jedno źródło (patrz `01-minimum.md`).
- Narzędzia i pamięć: sprawdzane w decision pipeline, gdy pojawią się wywołania narzędzi.
  Do tego czasu testy negatywne opieramy na modelach i na demo-narzędziu.

## 5. Schemat danych

Profil `local` tworzy tabele przez Hibernate (`ddl-auto`). Profil `prod` używa Flyway,
`V2__auth.sql`:

```sql
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    login         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,           -- BCrypt
    role          VARCHAR(50)  NOT NULL,           -- chat | admin
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL
);

CREATE TABLE api_key (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,      -- principal: key:<name>
    key_prefix  VARCHAR(16)  NOT NULL UNIQUE,
    key_hash    CHAR(64)     NOT NULL,             -- SHA-256 pełnego klucza
    created_at  TIMESTAMP    NOT NULL,
    revoked_at  TIMESTAMP
);
```

Uwagi:
- Brak kolumny `scopes`. Uprawnienia są w `policy.yaml`, a baza przechowuje tylko tożsamość.
- Hasła: BCrypt (`PasswordEncoder` ze Spring Security). Klucze: SHA-256, bo mają 256 bitów
  entropii i nie są hasłami.
- Seed kont (admin, chat) i klucza runnera z env przy starcie, tylko gdy ich brak. Hasła nie trafiają
  do migracji.

## 6. SecurityWebFilterChain (szkic)

```java
@Bean
SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ApiKeyAuthenticationWebFilter apiKeyFilter) {
    return http
        // CSRF dotyczy sesji z formularza; żądania z X-API-Key go nie wymagają.
        .csrf(csrf -> csrf.requireCsrfProtectionMatcher(apiKeyAbsent()))
        .authorizeExchange(ex -> ex
            .pathMatchers("/actuator/health", "/login", "/logout").permitAll()
            .pathMatchers("/api/**").hasRole("ADMIN")
            .pathMatchers("/v1/**").authenticated()
            .anyExchange().authenticated())
        .formLogin(Customizer.withDefaults())
        .addFilterBefore(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .build();
}
```

Uwagi:
- `/v1/**` wymaga uwierzytelnienia (klucz albo sesja). Konkretne modele i narzędzia sprawdza
  pipeline wobec `policy.yaml`, a nie `hasAuthority`.
- `apiKeyAbsent()` to własny `ServerWebExchangeMatcher`, zwracający match tylko dla żądań bez
  `X-API-Key`.
- Frontend musi obsłużyć token CSRF z cookie `XSRF-TOKEN` przy `POST /login`.
- Nazwy klas i builderów sprawdzamy przy implementacji, zgodnie z API Spring Security z Boot 4.1.

## 7. Klucze API

### Format

- `cl_<prefiks 8 znaków>_<sekret 32 bajty base64url>`.
- W bazie: `key_prefix` (wyszukiwanie) i `SHA-256(pełny klucz)` w `key_hash`.
- Klucz pokazujemy raz, przy tworzeniu. Odwołanie = `revoked_at`.

### Filtr `ApiKeyAuthenticationWebFilter`

1. Brak `X-API-Key` → przechodzi dalej.
2. Parsuje prefiks, pobiera rekord, porównuje hash w stałym czasie (`MessageDigest.isEqual`).
3. Nieznany albo odwołany → 401 z tym samym komunikatem co brak klucza.
4. Poprawny → principal `key:<name>`, `X-Caller-Id: key:<name>`.

Odpytanie bazy jest blokujące, więc wykonujemy je na `Schedulers.boundedElastic()` (VISION §2).
Cache wyniku z TTL 30 s przyspiesza filtr. Efekt: odwołanie działa w ciągu 30 s. Do testów cache
wyłączamy, żeby odwołanie działało od razu.

### Tworzenie i bootstrap

- `POST /api/api-keys` (rola admin) tworzy klucz i zwraca go raz.
- `CL_BOOTSTRAP_API_KEY` z env tworzy klucz `runner` przy starcie. Klucz demo może być w README do
  prezentacji. Ma ograniczone uprawnienia w `policy.yaml`, więc jego wyciek nie daje dostępu do
  reszty.

## 8. Propagacja tożsamości

- Gateway wywołuje Ollamę przez `WebClient` bez nagłówków `Authorization`, `X-API-Key` i `Cookie`.
- Globalny filtr wejściowy usuwa wszystkie nagłówki `X-Caller-*` od klienta, a po uwierzytelnieniu
  ustawia `X-Caller-Id`.
- Filtr budżetu i rate-limit (VISION §4) czyta wyłącznie `X-Caller-Id`.

## 9. Testy

### JUnit (`WebTestClient`, `spring-boot-starter-security-test`)

- `POST /v1/chat/completions` bez poświadczenia → 401, model nie wywołany.
- Zły klucz i odwołany klucz → 401.
- Klucz `runner` z modelem spoza `allow.models` → 403, model nie wywołany.
- Klucz z narzędziem z `deny.tools` → 403.
- Sesja `role:chat` na `/api/**` → 403.
- Złe hasło w `/login` → 401.
- `X-Caller-Id` podsunięty przez klienta jest usuwany i nadpisywany.
- `Authorization`, `X-API-Key`, `Cookie` nie trafiają do Ollamy.

### Suite (dane YAML, VISION §8)

```yaml
- name: "brak poświadczenia blokuje /v1 przed modelem"
  request: { path: "/v1/chat/completions", headers: {} }
  expect: { status: 401, model_called: false }
- name: "klucz runner z dozwolonym modelem przechodzi"
  request: { path: "/v1/chat/completions", headers: { X-API-Key: "${CL_API_KEY}" } }
  expect: { status: 200, audit.caller: "key:runner" }
- name: "klucz runner z modelem spoza allowlisty blokowany"
  request: { path: "/v1/chat/completions", headers: { X-API-Key: "${CL_API_KEY}" }, body: { model: "inny-model" } }
  expect: { status: 403, model_called: false, policy: policy.allow.models }
- name: "podszycie się przez X-Caller-Id nie zmienia tożsamości"
  request: { path: "/v1/chat/completions", headers: { X-API-Key: "${CL_API_KEY}", X-Caller-Id: "user:admin" } }
  expect: { status: 200, audit.caller: "key:runner" }
- name: "odwołany klucz odrzucony"
  request: { path: "/v1/chat/completions", headers: { X-API-Key: "${CL_REVOKED_KEY}" } }
  expect: { status: 401 }
- name: "konto chat nie wejdzie do API admina"
  request: { path: "/api/policies", session: "role:chat" }
  expect: { status: 403 }
```

Runner czyta `CL_API_KEY` z env i nie ma go w repo. Testy z narzędziami i pamięcią dopisujemy
po zdefiniowaniu demo-narzędzi (patrz `01-minimum.md`).

## 10. Frontend

- Formularz logowania wysyła `POST /login` (z CSRF z cookie `XSRF-TOKEN`), potem `GET /api/me`.
- 401 z API → ekran logowania. 403 → komunikat o braku uprawnienia.
- Wylogowanie: `POST /logout`.
- Brak tokenów w `localStorage`. Sesja jest w cookie HttpOnly.
- W dev Vite proxy kieruje `/login`, `/logout`, `/api` i `/v1` na `:8000`, więc jest jeden origin.
- Na Pi frontend jest serwowany pod tym samym originem co gateway (np. przez proxy w kontenerze
  frontendu albo statyczne pliki gatewaya). Decyzja w otwartych pytaniach.

## 11. Deployment na Raspberry Pi

1. Pi: Docker (arm64), SSD, `docker compose`. Obrazy budujemy multi-arch na laptopie
   (`docker buildx`) i ściągamy na Pi.
2. `docker-compose.yml` na Pi (zmiany względem obecnego pliku):
   - `db` i `ollama` **bez** publikowanych portów (`ports:` usunąć). Backend łączy się z nimi
     po nazwie usługi w sieci compose. Obecny plik publikuje `5432` i `11434` na wszystkich
     interfejsach, co trzeba zmienić.
   - Publikowany jest tylko `backend` na `8000` (oraz `frontend`, jeśli serwowany osobno).
3. Adres dla klientów w sieci, w której stoi Pi:
   - `http://raspberrypi.local:8000` (mDNS), albo
   - IP z rezerwacją DHCP na routerze.
4. `AUTH_COOKIE_SECURE=false` przy dostępie przez HTTP w LAN.
5. `.env` na Pi z sekretami, uprawnienia `600`, nie w repo.
6. Restart policy `unless-stopped` już jest w compose.
7. Opcjonalnie tunel HTTPS (`cloudflared` albo `ngrok`) dla dostępu spoza sieci. Nie zmienia
   autentykacji. Wtedy `AUTH_COOKIE_SECURE=true`.
8. Sprawdzenie po wdrożeniu: `curl http://<pi>:8000/actuator/health`, logowanie w przeglądarce
   z innego urządzenia w tej samej sieci, klucz API z laptopa.

Plan B na prezentację: jeśli sieć konferencyjna izoluje klientów, włączamy tunel HTTPS. Auth działa
bez zmian, bo nie zależy od Google.

## 12. Kolejność prac

Szacunek przy pracy równoległej dwóch osób, zgłoszenie 4.10.2026 23:00:

| Krok | Zakres | Kto (propozycja) | Gotowe gdy |
|---|---|---|---|
| 1 | tabela `api_key`, filtr, bootstrap runnera, `/v1/**` za uwierzytelnieniem | osoba B | 401 bez klucza, 200 z kluczem |
| 2 | `policy.yaml` z `principals`, `allow`/`deny` modeli w pipeline | osoba A | 403 dla modelu spoza listy |
| 3 | tabela `app_user`, seed kont, `formLogin`, `/api/**` za rolą admin | osoba A | zły hasło → 401, chat na `/api` → 403 |
| 4 | testy (§9) i wpisy w suite (YAML) | osoba B | suite przechodzi jednym poleceniem |
| 5 | deployment na Pi, porty, test sieci konferencyjnej | osoba A | klient z innego urządzenia wchodzi |
| 6 | frontend: login, logout, 401/403 | osoba A | przepływ działa w przeglądarce |
| 7 | (opcjonalnie) tunel HTTPS | osoba A | dostęp spoza sieci |

**Punkt odcięcia:** jeśli krok 3 nie jest gotowy na kilka godzin przed zgłoszeniem, zostają klucze
API i `deny` w polityce (kroki 1, 2, 4). Konta lokalne zostają dla pokazu.

## 13. Definition of done

- [ ] bez poświadczenia `/v1/**` zwraca 401 i nie wywołuje modelu;
- [ ] klucz z modelem spoza `allow.models` zwraca 403;
- [ ] odwołanie klucza działa w ciągu 30 s, bez restartu;
- [ ] `X-Caller-Id` od klienta jest ignorowany, audyt pokazuje prawdziwego principal;
- [ ] `Authorization`, `X-API-Key` i `Cookie` nie trafiają do Ollamy;
- [ ] konto chat nie wchodzi do `/api/**`;
- [ ] testy auth są w suite jako dane YAML i przechodzą jednym poleceniem;
- [ ] `.env` jest w `.gitignore`, sekrety nie są w repo;
- [ ] porty `db` i `ollama` nie są publikowane na Pi;
- [ ] VISION zaktualizowany (§1, §2, §3, §4, §7, §8, §10).

## 14. Otwarte pytania

1. Model Raspberry Pi: 4 GB czy 8 GB RAM.
2. Sieć konferencyjna: czy klienci widzą Pi, czy działa izolacja klientów Wi-Fi.
3. Jedna lista modeli: `control-layer.models` w `application.yml` czy `principals` w `policy.yaml`.
4. Definicja demo-narzędzi (`demo.search`, `demo.exec`) i demo-zasobu pamięci w MVP.
5. Frontend na Pi: osobny port z proxy czy serwowany przez gateway.
6. Czy hasła demo i klucz runnera mogą być w README.
7. Czy `docker-compose.yml` ma uruchamiać Ollamę na Pi w sieci compose, zgodnie z VISION §10.
