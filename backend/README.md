# Backend — AI Control Layer (Spring Cloud Gateway)

Architektura i katalog kontroli: [`../VISION.md`](../VISION.md).
Stack: Java 25, Spring Boot 4.1, Spring Cloud 2025.1 (gateway webflux), Gradle 9 (wrapper w repo).

## Wymagania

- JDK 17+ do odpalenia Gradle (np. w IntelliJ: *Project Structure → SDK → Download JDK → 25*).
  JDK 25 do kompilacji Gradle pobierze sam (toolchain + foojay).
- Docker Desktop — tylko dla profilu `prod`.

## Profile

| Profil | Baza | Schemat | Kiedy |
|---|---|---|---|
| `local` (domyślny) | H2 in-memory | Hibernate `create-drop` | codzienny dev, testy — zero zależności |
| `prod` | PostgreSQL (`db` z `docker-compose.yml`) | Flyway (`src/main/resources/db/migration`), Hibernate `validate` | zgodność z produkcją, demo |

## Uruchomienie

### Web playground i Codex CLI równolegle

Błędy `/v1/responses` i `/v1/responses/compact`: blokada treści wejścia/wyjścia to **400**,
brak uprawnień do modelu **403**, brak uwierzytelnienia **401**, wyczerpany budżet lub limit
wyjścia **429**, za duże wejście **413**, a niedostępna kontrola fail-closed **503**.
`error` zawiera `message`, `code`, `request_id`, `policy_version`, `retryable`; blokada guarda
dodatkowo `stage`, `guard`, `reason`, `detections` (np. `PII-001/PL_PESEL`).
Zwracamy klasy wykryć, nigdy wykryte wartości, fragmenty promptu ani surowe błędy providera.

Obie integracje działają jednocześnie, w każdym profilu bazy (`local`/`prod`): playground woła
`/v1/chat/completions` (Ollama), Codex CLI `/v1/responses` (abonament ChatGPT). Każdą można wyłączyć
(`WEB_INTEGRATION_ENABLED=false` / `CODEX_INTEGRATION_ENABLED=false`, restart). Aktywne integracje
sprawdzisz jako admin przez `GET /api/integration`.

Codex używa istniejącego **logowania ChatGPT i abonamentu**. Backend nie potrzebuje
`OPENAI_API_KEY`, a adapter nigdy nie przechodzi na API z osobnym billingiem.
Modele Codexa są w katalogu na stałe: lista slugów z ChatGPT w
[`src/main/resources/codex-models.yml`](src/main/resources/codex-models.yml). Nowy model w Codexie
(`/model`) = dopisz tam jego slug i przebuduj backend (`docker compose up -d --build backend`).

W Policies włącz wybrane modele z base URL `https://chatgpt.com/backend-api/codex` w aktywnym
katalogu i modeli dostępnych dla roli konta agenta. Istniejąca polityka w bazie nie jest
nadpisywana konfiguracją profilu. Ustaw limity wejścia i wyjścia odpowiednie dla historii
kodowania i definicji narzędzi; dotychczasowe małe limity dla playgroundu mogą blokować Codexa.
Potrzebujesz Node >=20 i Codex CLI na PATH. W terminalu klienta, z katalogu głównego repo:

```sh
codex login                  # jeśli nie jesteś już zalogowany przez ChatGPT
node cli/control-layer.mjs run codex
```

Launcher domyślnie używa konta demo `codex-agent` / `codex-agent-123` (rola `codex`,
`config/users.yaml`) i wdrożonego gatewaya `https://apillminator.fmroz.me/v1`. Lokalny stack, inne konto:
`install codex --gateway http://localhost:8000/v1 [--user LOGIN]` (szczegóły w [cli/README.md](../cli/README.md)).

Routing jest ustawiany tylko dla tego procesu przez argumenty Codexa. Launcher zachowuje
oryginalny `CODEX_HOME`, konfigurację i magazyn logowania; sam Codex odświeża OAuth tak jak zwykle.
Gateway otrzymuje OAuth w `Authorization` i własne logowanie w oddzielnym nagłówku
`X-Control-Layer-Authorization`. Tylko nagłówki wymagane przez protokół trafiają do upstreamu;
logowanie gatewaya, cookies i pozostałe nagłówki nie są przekazywane do ChatGPT.
OAuth i hasła nie są zapisywane w profilu ani audycie. Dla zdalnego gatewaya użyj
`--gateway https://HOST/v1` podczas install. Credential helper i wszystkie systemy:
[cli/README.md](../cli/README.md).

```sh
node cli/control-layer.mjs disable codex
node cli/control-layer.mjs enable codex
node cli/control-layer.mjs uninstall codex
```

Zwykłe `codex` zawsze korzysta z oryginalnej konfiguracji. Disable i uninstall nie przywracają
starych plików użytkownika, bo launcher ich nie modyfikuje. Wcześniej utworzony profil launchera
zachowuje gateway/model/konto i po aktualizacji kodu także używa logowania ChatGPT.

Natywne endpointy: `POST /v1/responses`, `POST /v1/responses/compact`, `GET /v1/models`.
Katalog modeli jest filtrowany według polityki. Statusy 401 i 429 zachowują znaczenie logowania
oraz limitu abonamentu. SSE jest buforowane do końca i kontroli wyjścia; przy redakcji gateway
składa nowy stream z zredagowanej odpowiedzi końcowej (tymczasowe ramki oryginału nie wychodzą),
blokada nadal blokuje. JSON może być redagowany. Narzędzie `tool_search` jest dopuszczone tylko
w wariancie wykonywanym przez klienta (`execution: client`, Codex CLI >= 0.159). Nie obsługujemy jeszcze WebSocket, obrazów/plików,
background, ukrytej historii, hosted web search ani aplikacji desktopowej. Grupy narzędzi namespace
Codexa są zachowywane i ich opisy oraz schematy przechodzą te same kontrole wejścia.
Backend ChatGPT nie przyjmuje `max_output_tokens`: limit wyjścia sprawdzamy po generacji,
nie dokładamy tego parametru. Budżet pozostaje tokenowy; nie wyliczamy ceny za token abonamentu.
Playground i Codex dzielą polityki, budżety, dashboard i audyt.
Kontrola lokalnego wykonania narzędzi wymaga osobnej integracji; tutaj kontrolujemy ruch modelowy.

Testy obejmują pełny łańcuch security, OAuth, nagłówki, modele, compaction i guardy z atrapą
upstreamu. Osobny test uruchamia rzeczywisty Codex CLI 0.155.0 z fikcyjnymi poświadczeniami
na loopbackie. Test rzeczywistego konta ChatGPT pozostaje osobnym sprawdzeniem.

Otwórz **root repo** (nie ten katalog) w IntelliJ — `settings.gradle` w roocie jest composite
buildem, który dociąga `backend/` automatycznie (szczegóły: [`../README.md`](../README.md)).

W IntelliJ (configi współdzielone z `.run/`, pojawią się same po otwarciu roota):

- **Backend (local)** — `bootRun` na H2.
- **Backend (prod, postgres)** — najpierw stawia `Postgres (docker)`, potem `bootRun` z profilem `prod`.
- **Backend tests** — `gradle test`.

Z terminala (z tego katalogu `backend/`; z roota repo analogicznie przez `./gradlew :backend:<task>`):

```bash
./gradlew bootRun                                   # profil local
docker compose up -d db                             # z roota repo
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun       # profil prod
docker compose up -d --build db backend             # całość w kontenerach (prod)
```

Gateway słucha na `http://localhost:8000`, health: `/actuator/health`.

## Przebudowa po zmianach (z katalogu głównego repo)

Docker Desktop musi być uruchomiony. Baza i pobrane modele zostają w wolumenach.

```sh
# Po zmianach w backendzie (Java, resources, konfiguracja builda):
docker compose up -d --build backend

# Po zmianach w UI (bez restartu backendu, sesje zostają):
docker compose up -d --build --no-deps frontend

# Cały stack:
docker compose up -d --build

# Logi backendu (Ctrl+C kończy podgląd):
docker compose logs -f --tail 100 backend
```

Bez skryptu, po zmianie backendu: `docker compose up -d --build backend`.
Skrypt korzysta z cache Dockera i nie wyłącza cache Gradle.
Samo `docker compose restart backend` nie buduje nowego kodu. Build Dockerowy pomija testy;
testy backendu uruchamiaj osobno: `.\gradlew.bat :backend:test`.
UI jest na http://localhost:3000. Gotowość modeli przy pierwszym uruchomieniu sprawdzisz
przez `docker compose logs --tail 20 ollama-init` (kończy się sukcesem).

`POST /v1/chat/completions` woła skonfigurowany model (`{model, messages}` na wejściu,
`GuardedChatResponse` na wyjściu — kontrakt w `frontend/src/api/types.ts`). Model trzeba najpierw
dopisać do allowlisty (`control-layer.models` w `application.yml`), inaczej dostaniesz
`403 model.allowlist`. Adres providera: `OLLAMA_BASE_URL` (domyślnie `http://localhost:11434` —
nadpisywane w `docker-compose.yml` na `http://ollama:11434` dla wdrożenia na Raspberry Pi).

## Self-testing suite

Kontrole (guardy) mają dwa poziomy testów, oba odpalają się tą samą komendą:

| Poziom | Co sprawdza | Przykład |
|---|---|---|
| Testy jednostkowe JUnit | logikę jednego guarda w izolacji (regexy, progi, walidatory) | `PiiRecognizerGuardTest`, `SemanticGuardTest` |
| **Scenariusze BDD (Cucumber)** | cały pipeline end-to-end: polityka → user → prompt → odpowiedź | `src/test/resources/features/*.feature` |

Scenariusze BDD są pisane w Gherkin (Given/When/Then), czytelne bez znajomości Javy:

```gherkin
Scenario: The model "tries to help" by pasting a payment card number into its answer — it gets redacted
  Given the model responds with "Your card is 4111 1111 1111 1111, valid through 12/27"
  When the user sends the prompt "What card number do you have on file for me?"
  Then the response action is "redact"
  And the response does not contain "4111 1111 1111 1111"
```

Kroki Given/When/Then i nazwy metod w `src/test/java/.../chat/bdd/` są po angielsku (tak jak
reszta identyfikatorów w kodzie) — polskie są tylko komentarze/dokumentacja projektu, zgodnie
z resztą repo.

Pod spodem: prawdziwy `ChatCompletionController` z prawdziwym `GuardChain`/`BudgetGate`, a model
i sidecar semantyczny to domyślnie lekkie atrapy HTTP (`com.sun.net.httpserver.HttpServer`)
sterowane krokami `Given` — żadnego Dockera, żadnej prawdziwej Ollamy. To samo podejście, co w
`ChatCompletionControllerTest`/`SemanticGuardControllerTest`, tylko opakowane w język scenariusza.

**Wyjątek**: `semantic_sidecar_e2e.feature` woła prawdziwy proces `semantic-sidecar/` (prawdziwy
klasyfikator Horizon), żeby sprawdzić, że model naprawdę łapie prompt injection, a nie tylko że
Java poprawnie reaguje na wyskryptowany wynik.

- `./gradlew test` (domyślne) — jeśli sidecar nie odpowiada na `localhost:8001`, te dwa scenariusze
  są **pomijane** (skipped), nie failowane. Zero Dockera, zero zależności od internetu.
- `./gradlew testWithSidecar` — **sam** stawia `semantic-sidecar-init`/`semantic-sidecar` z
  `docker-compose.yml` przez Testcontainers, czeka aż sidecar będzie zdrowy, odpala całą suitę, a
  na koniec sam gasi kontenery. Jedna komenda, wymaga działającego Dockera; pierwszy raz ściąga
  model (~600 MB z Hugging Face), więc może potrwać kilka minut.
- Inny adres sidecara (np. już gdzieś działający): `-Dsidecar.e2e.url=...` albo zmienna
  środowiskowa `SIDECAR_E2E_URL` — wtedy `./gradlew test` (bez `testWithSidecar`) też go znajdzie
  i scenariusze się odpalą zamiast pominąć.

### Jak odpalić

```bash
./gradlew test                                               # wszystko, w tym BDD (e2e sidecara pominięte bez Dockera)
./gradlew testWithSidecar                                    # to samo, ale sam stawia i gasi prawdziwego sidecara
./gradlew test --tests "*.bdd.CucumberSuite"                 # tylko scenariusze BDD
./gradlew test -Dcucumber.filter.tags="@budget"               # tylko jedna kategoria (gdy dodacie tagi)
```

### Jak zobaczyć wynik ładnie, nie w konsoli

Po `./gradlew test` powstają dwa raporty:

1. **Zawsze działa, zero instalacji**: `build/reports/cucumber/report.html` — otwórz w przeglądarce.
2. **Ładniejszy, jeśli masz zainstalowane [Allure CLI](https://allurereport.org/docs/install/)**
   (`brew install allure` / `scoop install allure` — Gradle plugin `io.qameta.allure` jest
   dziś niekompatybilny z Gradle 9.x, więc generujemy raport przez CLI, nie przez
   `./gradlew allureReport`):
   ```bash
   allure serve build/allure-results
   ```

### Gdzie dopisać kolejny guard

1. Nowy plik `.feature` w `src/test/resources/features/` (albo nowy `Scenario` w istniejącym) —
   `Given`/`When`/`Then` po polsku, nie trzeba znać Javy.
2. Jeśli potrzebujesz nowego kroku (np. sterowania nowym zewnętrznym komponentem), dodaj metodę
   `@Given`/`@When`/`@Then` w jednej z klas w `src/test/java/.../chat/bdd/` — żeby zamockować
   kolejny serwis HTTP, skopiuj wzorzec `FakeHttpService` (dokładnie ten, którego już używają
   atrapy modelu i sidecara).
3. Logikę samego guarda testuj dodatkowo zwykłym testem JUnit (szybsza pętla feedbacku przy
   dopracowywaniu regexów/progów) — BDD sprawdza integrację, nie zastępuje testu jednostkowego.
