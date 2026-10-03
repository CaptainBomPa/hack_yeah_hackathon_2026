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

## Przebudowa po zmianach (PowerShell, z katalogu głównego repo)

Docker Desktop musi być uruchomiony. Gotowy skrypt buduje obrazy, uruchamia kontenery
i czeka na gotowość usług; przerywa przy błędzie builda. Zachowuje bazę i pobrane modele.

```powershell
# Po zmianach w backendzie (Java, resources, konfiguracja builda):
.\scripts\rebuild.ps1 -Target backend

# Po zmianach w UI:
.\scripts\rebuild.ps1 -Target frontend

# Cały stack:
.\scripts\rebuild.ps1

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
