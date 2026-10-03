# VISION — AI Control Layer

To jest jedyne źródło prawdy o wizji produktu, architekturze, technologiach i planie
implementacji. Pozostałe pliki opisowe mają jedynie kierować tutaj albo opisywać szczegół
techniczny bez powtarzania decyzji z tego dokumentu.

Najważniejszym materiałem źródłowym zadania, nadrzędnym wobec naszej interpretacji, jest
[`CRITERIA AI Control Layer.pdf`](project-spec/CRITERIA%20AI%20Control%20Layer.pdf). Formalnym
uzupełnieniem są [`RULES AI Control Layer.pdf`](project-spec/RULES%20AI%20Control%20Layer.pdf).
Wizję naszego rozwiązania definiuje wyłącznie ten dokument i nie może ona być sprzeczna z
CRITERIA.

## 1. Cel i zakres MVP

Budujemy **AI Control Layer**: lokalny gateway przed LLM, który przechwytuje wejście i wyjście,
egzekwuje polityki bezpieczeństwa, zarządza budżetem oraz zapisuje bezpieczny audyt. Chroniony
model działa w Ollamie na Raspberry Pi; sam model nie implementuje guardraili.

MVP musi:

- wpuszczać do gatewaya wyłącznie zidentyfikowanych callerów: klucze API dla agentów i runnera
  oraz lokalne konta dla czatu i admina; uprawnienia do modeli, narzędzi i pamięci wynikają z
  `policy.yaml` (szczegóły: [`docs/auth/`](docs/auth/));
- nie wiązać decision pipeline z jednym dostawcą analizy semantycznej;
- łączyć szybkie kontrole deterministyczne w Javie z analizą semantyczną dostarczaną przez
  wymienny provider;
- pozwalać zmienić politykę bez przebudowy aplikacji;
- prezentować decyzję, jej powody i latencję każdej kontroli;
- zawierać uruchamialny jednym poleceniem zestaw testów pozytywnych i negatywnych;
- demonstrować dwa główne wyróżniki: **B — Red Team Arena** i
  **E — Explainable Verdict / Security X-ray**.

Integracja z pełnym protokołem MCP, rozbudowane role użytkowników i rozproszony deployment nie
są warunkiem MVP. Lokalna analiza promptów jest preferowana ze względu na prywatność,
niezależność i koszt, ale jest **nice to have**, a nie ograniczeniem architektury. MVP może
korzystać z zewnętrznego API, jeśli daje ono najlepszą jakość i przewidywalne demo.

## 2. Architektura

```text
[React SPA: playground + dashboard]
                  |
                  v
[Java / Spring Cloud Gateway: Control Layer]
  |  1. identyfikacja żądania i polityki
  |  2. kontrole wejścia: reguły + semantyka
  |  3. decyzja i akcja
  |  4. wywołanie chronionego modelu, jeśli dozwolone
  |  5. kontrole odpowiedzi i redakcja
  |  6. audyt, metryki i Explainable Verdict
  |
  +--> [Semantic provider: zewnętrzne API lub lokalny sidecar]
  +--> [PostgreSQL: polityki, audyt, budżety]
  +--> [Ollama na Raspberry Pi: chroniony LLM]
  +--> [Zewnętrzne LLM-y: klucze z env, budżet tokenów]
  +--> [Tożsamość: lokalne konta (czat, admin) i klucze API (agenci); uprawnienia w polityce (baza)]
```

Java jest właścicielem orkiestracji, polityk i ostatecznej decyzji. Javowy interfejs providera
oddziela decision pipeline od konkretnego modelu lub usługi. Implementacja może wywoływać
zewnętrzne API albo opcjonalny lokalny sidecar Python/FastAPI, gdy uzasadnia to ekosystem ML.
Awaria providera nie może omijać kontroli, a jego wymiana nie może zmieniać kontraktu
`ControlResult`.

Wdrożenie docelowe: cały stack (gateway, PostgreSQL, sidecar, statyczny frontend i Ollama) działa
na Raspberry Pi. Rozwój odbywa się lokalnie na profilu `local`. Klienci zespołu i jury wchodzą
przez adres Pi w sieci, w której stoi Pi. Logowanie i klucze API nie wymagają internetu. Tunel
HTTPS jest opcjonalny, tylko dla dostępu spoza tej sieci. Na Pi publikujemy wyłącznie gateway,
nigdy bazy ani Ollamy.

Spring Cloud Gateway jest reaktywny, natomiast obecna persistencja JPA/JDBC jest blokująca.
Operacje bazodanowe nie mogą wykonywać się na event loopie WebFlux: należy izolować je na
`boundedElastic` albo w wydzielonej warstwie wykonawczej.

Gateway ogranicza tempo i współbieżność żądań według centralnej polityki, aby chronić
zasoby modelu. Limity obejmują każde konto; awarie mechanizmów ochrony nie mogą omijać kontroli.
Rate limiter korzysta z `rateLimit` i nadpisań per rola w aktywnej wersji `policy_version`;
autoryzacja, limiter, budżet i guardy używają jednego snapshotu polityki na żądanie.
Zakończone błędy providera i brak połączenia zwalniają slot współbieżności; timeout lub
przerwane połączenie po wysłaniu żądania zachowują dzierżawę do wygaśnięcia. Brak połączenia
przed wysłaniem żądania zwalnia również rezerwację budżetu bez naliczania tokenów.

## 3. Technologie

| Warstwa | Decyzja |
|---|---|
| Backend/gateway | Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, WebFlux/Gateway, Gradle Groovy |
| Persistencja | JPA/Hibernate + JDBC; H2 dla profilu `local`, PostgreSQL + Flyway dla `prod` |
| Semantyka | Wymienny provider za interfejsem Javy: zewnętrzne API lub opcjonalny lokalny sidecar Python/FastAPI + Hugging Face/ONNX |
| Chroniony model | Ollama na Raspberry Pi; bazowy model `qwen2.5:1.5b-instruct-q4_K_M` |
| Frontend | React 18, TypeScript, Vite, Tailwind, Recharts; **cały UI po angielsku** (teksty, komunikaty błędów zwracane do UI, formaty dat/liczb `en`) |
| Uruchomienie | Docker Compose; docelowo backend + Ollama razem na Raspberry Pi (jedna sieć docker, §10); `OLLAMA_BASE_URL` nadpisuje adres dla lokalnego dev |
| Autentykacja | Spring Security (reactive). Ludzie: lokalne konta w PostgreSQL (BCrypt), formularz i sesja w cookie. Maszyny: klucze API z hashem w bazie. Uprawnienia do modeli, narzędzi i pamięci: `policy.yaml`. Szczegóły: [`docs/auth/`](docs/auth/) |

Kandydaci na biblioteki i sposób ich oceny są opisani wyłącznie w
[`docs/tooling.md`](docs/tooling.md).

## 4. Kontrole, decyzje i tryby

Każda kontrola zwraca wspólny `ControlResult`: identyfikator polityki, wynik, proponowaną akcję,
pewność, krótki powód i czas wykonania. Java agreguje wyniki według aktywnej wersji polityki.

Każda kontrola ma próg oraz tryb z polityki:

| Tryb | Zachowanie |
|---|---|
| `off` | Kontrola jest wyłączona, ale jej stan pozostaje widoczny. |
| `monitor` | Ocenia i raportuje, lecz nie zatrzymuje przepływu. |
| `redact` | Maskuje dokładnie wskazane fragmenty i pozwala kontynuować. |
| `require_approval` | Zatrzymuje akcję do zatwierdzenia; bez approval flow zachowuje się jak `block`. |
| `block` | Odrzuca żądanie, odpowiedź albo wywołanie narzędzia. |

Finalny `ControlResult` ma akcję `allow | monitor | redact | require_approval | block` oraz
osobny status techniczny `ok | degraded | error`. Profile `permissive`, `balanced` i `strict`
są nazwanymi zestawami trybów i progów, a nie drugim mechanizmem decyzyjnym.

Nową wersję polityki można najpierw uruchomić w trybie shadow: jej decyzje są raportowane obok
aktywnej wersji, ale nie wpływają na ruch. Polityka jest danymi: **źródłem prawdy jest tabela
`policy_version` w bazie** (append-only, każda zmiana to nowa wersja z hashem, autorem i komentarzem;
aktywna = najnowsza). Admin zmienia ją w UI (Policies) albo importem YAML; zapis aktywuje nową wersję
atomowo, od następnego żądania, bez restartu. Błędna wersja nie przechodzi walidacji i nie zastępuje
ostatniej poprawnej. `config/policy.yaml` i sekcje `application.yml` służą tylko do zbudowania
wersji 1 przy pierwszym starcie (szczegóły: [`docs/policy-management-plan.md`](docs/policy-management-plan.md)).
Nie wolno hardcodować progów i akcji w kontrolerach.

### Kontrole deterministyczne — Java

- wykrywanie i redakcja PII oraz sekretów (PESEL, IBAN, karty z Luhnem, klucze API);
- limity rozmiaru, zagnieżdżenia, tempa żądań i budżetu tokenów;
- allowlista modeli oraz docelowo narzędzi i schematów ich argumentów;
- sygnatury prompt/code/command injection, niebezpiecznej deserializacji i SSRF;
- uwierzytelnianie callerów (klucz API, sesja lokalna) i autoryzacja per principal (modele,
  narzędzia, pamięć) wg `policy.yaml`; brak lub złe poświadczenie kończy się odrzuceniem przed
  wywołaniem modelu;
- timeout, circuit breaker i jawna strategia `fail-open`/`fail-closed` per kontrola.

### Kontrole semantyczne — wymienny provider

- klasyfikacja prompt injection i jailbreak;
- klasyfikacja wycieku danych/system promptu na wyjściu;
- podobieństwo do korpusu znanych ataków;
- opcjonalny LLM-as-judge tylko dla przypadków granicznych.

Semantyka jest sygnałem w hybrydowym scoringu, nigdy pojedynczym punktem autoryzacji. Przy
providerze zewnętrznym polityka określa, jakie dane wolno wysłać: sekrety i wykrywalne PII są
redagowane przed wywołaniem, a audyt zapisuje nazwę providera, latencję i status bez utrwalania
surowego promptu.

## 5. Wyróżniki produktu

Zachowujemy wszystkie pięć pomysłów. **B i E są częścią głównego MVP**; A, C i D są opcjonalne
i nie mogą opóźnić stabilnego gatewaya, testów ani audytu.

| ID | Priorytet | Wyróżnik | Sens demo |
|---|---|---|---|
| A | Opcjonalny | **Data-Flow Firewall** | Graf pokazuje, skąd pochodzą dane i dokąd mogą wypłynąć; polityka zatrzymuje niedozwolony przepływ. |
| B | **Główny MVP** | **Red Team Arena** | Ten sam korpus ataków uruchamiany przeciw ruchowi bez ochrony i przez Control Layer; dashboard pokazuje skuteczność, false positives i narzut. |
| C | Opcjonalny | **Policy Time Machine** | Odtworzenie zapisanych zdarzeń na nowej wersji polityki i porównanie, jak zmieniłyby się decyzje. |
| D | Opcjonalny | **Policy Copilot** | Lokalny asystent proponuje regułę na podstawie incydentu, lecz człowiek zatwierdza zmianę. |
| E | **Główny MVP** | **Explainable Verdict / Security X-ray** | Dla każdego żądania widoczna jest ścieżka kontroli, sygnały, akcja, wersja polityki i koszt czasowy — bez ujawniania surowego PII. |

Red Team Arena dostarcza mierzalny dowód odporności, a Explainable Verdict tłumaczy każdą
decyzję. Razem są główną narracją prezentacji.

## 6. Reporting i audyt

Dashboard pokazuje liczbę żądań, blokad i redakcji, użycie budżetu, trafienia per polityka oraz
p50/p95 latencji. Wpis audytowy zawiera co najmniej: request/caller/session ID, czas, wersję
polityki, wyniki kontroli, finalną akcję, status zależności i zużycie tokenów.

Nie zapisujemy surowego PII, sekretów ani pełnych promptów. Do korelacji używamy maskowania,
hashy i bezpiecznych fragmentów. Eksport CSV/JSON musi zachowywać te same zasady.

## 7. API i routing

Wejściem dla playgroundu jest zgodny z OpenAI endpoint `POST /v1/chat/completions`, wzbogacony
o identyfikator żądania i trace kontroli. API dashboardu korzysta z `/api/**`. Kontrakt
zaimplementowanych endpointów (OpenAPI 3.0): [`docs/api/openapi.yaml`](docs/api/openapi.yaml).

`/v1/chat/completions` **działa** (zastąpił tymczasowy passthrough `/llm/**`): waliduje `model`
wobec allowlisty (`control-layer.models` w `backend/src/main/resources/application.yml` —
dokładny tag providera, bez warstwy aliasów), woła go po jego natywnym OpenAI-compatible
endponcie (Ollama wystawia go wprost) z timeoutem fail-closed, i mapuje odpowiedź do
`GuardedChatResponse`. To tylko krok 4 pipeline'u („wywołanie modelu, jeśli dozwolone") plus
pierwsza realna kontrola (allowlista modeli) — reszta `trace` zapełni się, gdy powstaną kroki
1-4 z §9. Zaimplementowane jako zwykły kontroler WebFlux, nie deklaratywny route Spring Cloud
Gateway — wybór providera zależy od treści body, a odpowiedź wymaga przekształcenia do własnego
kontraktu, co w kontrolerze jest prostsze i mniej ryzykowne niż ręczne przepisywanie URI/body na
poziomie filtrów Gateway. Inne route'y (np. do sidecara) mogą nadal być deklaratywne.

Kontrole deterministyczne to beany `Guard` (`backend/.../guard`) spięte w łańcuch `GuardChain`,
wołany z kontrolera przed (`INPUT`) i po (`OUTPUT`) wywołaniu modelu. Włączane i parametryzowane
w `control-layer.guards` (`application.yml`); guard bez wpisu jest wyłączony. Instrukcja:
`docs/deterministic/how-to-write-a-rule.md`. PII obsługuje jeden guard `PII-RECOGNIZERS`: silnik
z konceptami Microsoft Presidio (recognizery jako dane w formacie YAML Presidio, nazwane walidatory
checksum, słowa kontekstowe, score i próg, rozwiązywanie konfliktów, operatory anonymizera),
zaimplementowany w Javie bez NLP. Recognizery `PII-001..PII-008` są w
`backend/src/main/resources/rules/pii/recognizers.yaml`, a ich id trafiają do `trace`. Samo
Presidio nie działa w ścieżce deterministycznej; może być jedynie implementacją providera
semantycznego dla NER (imiona, adresy). Szczegóły: `docs/deterministic/pii-recognizers.md`.
Sekrety obsługuje guard `SEC-GITLEAKS` (przed PII): reguły to przypięta paczka Gitleaks
(`backend/src/main/resources/rules/gitleaks/gitleaks.toml`, MIT) ładowana jako dane, a skan robi nasz
silnik w Javie — jeden przebieg Aho-Corasick po keywordach, regex tylko wokół trafień, filtry Gitleaks
(entropia, allowlisty). Binarka Gitleaks nie działa w runtime. Polityka (`blockRules`, `monitorRules`,
`disabledRules`) wskazuje id reguł Gitleaks; domyślnie redakcja, klucz prywatny blokuje.

**Logowanie (docs/auth, bez SSO i zewnętrznego IdP — działa offline):** konta lokalne z
`backend/config/users.yaml`, zakładane w bazie przy starcie (hasła BCrypt). Przeglądarka loguje się
przez `POST /api/auth/login` i dostaje ciasteczko sesji `SESSION` (HttpOnly, SameSite=Lax; sesje w
pamięci backendu — restart wylogowuje); maszyny (agenci, runner testów) używają HTTP Basic bez sesji.
401 nie wywołuje natywnego okienka przeglądarki; limit 5 nieudanych prób/min na login. `/api/**`
wymaga roli `admin`, `/api/auth/**` każdego zalogowanego, `/v1/*` uwierzytelnienia (sesja albo Basic).
Kontrakt dashboardu i przepływy frontu: [`docs/frontend-flows-and-api.md`](docs/frontend-flows-and-api.md).

Gateway działa na porcie `8000`, frontend na `3000`. Opcjonalny lokalny sidecar może działać
na `8001`; zewnętrzny provider jest konfigurowany adresem i poświadczeniami środowiskowymi.

Autoryzacja: `/v1/**` wymaga klucza API albo sesji zalogowanego użytkownika. Uprawnienia do modeli,
narzędzi i źródeł pamięci wynikają z `policy.yaml` dla danego principal (`key:<nazwa>` albo
`role:<rola>`). Brak wpisu oznacza odmowę. `/api/**` dashboardu wymaga roli admin. Brak poświadczenia
daje 401, brak uprawnienia 403, oba przed wywołaniem modelu, i są zapisywane w audycie.

## 8. Testy i kryteria akceptacji

Przypadki testowe są danymi YAML/JSON, nie kodem. Runner `./run-tests.sh` ma jednym poleceniem
wysyłać je do działającego gatewaya i sprawdzać status HTTP, akcję, politykę oraz obecność
bezpiecznego wpisu audytowego. Runner przekazuje klucz API z zmiennej `CL_API_KEY`.

Minimalny zestaw obejmuje:

- poprawne prompty w języku angielskim;
- PII/secrets i redakcję wejścia oraz wyjścia;
- bezpośrednie i pośrednie prompt injection/jailbreak;
- niedozwolone tool-calls, SSRF i command injection;
- przekroczenie budżetu, timeout providera i brak poświadczeń;
- brak klucza API, zły lub odwołany klucz, brak sesji i brak roli admin: 401/403 bez wywołania modelu;
- działanie wszystkich trybów kontroli oraz shadow nowej polityki;
- porównanie ruchu chronionego i niechronionego w Red Team Arena.

Raport pokazuje skuteczność, benign-block rate, p50/p95 i pokrycie kontroli. Testy jednostkowe
i integracyjne backendu uzupełniają suite, ale jej nie zastępują.

## 9. Plan implementacji

1. Uzgodnić kontrakty `ControlRequest`, `ControlResult`, decyzję końcową i format polityki.
2. Zbudować javowy decision pipeline z trybami oraz podstawowymi regułami PII/secrets.
3. Dodać uwierzytelnianie i autoryzację (`docs/auth/`), bezpieczny audyt, metryki, budżet i
   endpointy dashboardu.
4. Zaimplementować interfejs providera semantycznego, wybrany adapter, timeouty i zachowanie
   degradacyjne; lokalny sidecar dodać, jeśli pozwoli czas.
5. Zastąpić passthrough przez chronione `/v1/chat/completions` i podłączyć frontend. Backend:
   zrobione poza kolejnością (routing do modelu + allowlista nie czekały na kroki 1-4, zob. §7);
   podłączenie frontendu (dziś na mockach, `VITE_USE_MOCKS`) i reszta `trace` — wciąż do zrobienia.
6. Dostarczyć Explainable Verdict, test runner i Red Team Arena.
7. Dopiero potem rozważać wyróżniki A, C i D.

## 10. Stan repozytorium

- `backend/` — działający szkielet Java 25/Spring Boot 4 z profilami H2/PostgreSQL, Flyway,
  Dockerfilem i **działającym** `POST /v1/chat/completions` (allowlista modeli + wywołanie
  providera + `GuardedChatResponse`, zob. §7) — bez reszty decision pipeline;
- audyt: **działa e2e** — każde żądanie `/v1/chat/completions` (także allow i odmowy) trafia do
  tabeli `audit_event` (Flyway V3) z pełną ścieżką kontroli, bez treści wiadomości, w łańcuchu
  HMAC (wykrywanie modyfikacji, `GET /api/audit/verify`); zapis przed odpowiedzią, fail-closed
  (503) przy awarii; API `/api/audit/**` (rola ADMIN) i ekran Audit log we frontendzie;
  na Postgresie dodatkowo trigger append-only (zob. `docs/deterministic/27-audit-logging.md`);
- dashboard: **działa e2e** — `GET /api/dashboard?window=1h|24h|7d` liczy metryki z `audit_event` (akcje, 5xx,
  latencja p50/p95, tokeny, oś czasu, top kontroli, per model/użytkownik) + budżety ról z `budget_counter`;
- `frontend/` — Playground (czat + X-ray), Audit log i Dashboard podłączone do backendu; Polityki i Session graph jeszcze nie;
- `semantic-sidecar/` — **działa**: lokalny sidecar Python/FastAPI (port 8001) z klasyfikatorem prompt injection
  `Horizon-Labs/prompt-injection-guard-small` (Apache-2.0, 141M, kalibrowany), wołany przez guard `SEM-001`
  (próg blokady 0,9 w `application.yml`, fail-closed). Pokrywa P1, P2, P5; P3 i P4 raportuje jako brak pokrycia
  (`covered: false`). Dobór modelu i pomiary: `semantic-sidecar/docs/models.md`. Nie sprawdzony na Raspberry Pi;
- `docker-compose.yml` — docelowo wdrażany w całości na Raspberry Pi: baza, backend i Ollama
  żyją w jednej sieci docker na tym samym hoście (backend łączy się z Ollamą przez nazwę
  usługi `ollama`, nie przez LAN). `OLLAMA_BASE_URL` pozwala deweloperowi nadpisać to lokalnie
  (np. `bootRun` na laptopie z własną Ollamą pod `localhost:11434`, czyli wartość domyślna);
- uwierzytelnianie: **działa** — konta z `users.yaml` w bazie, ekran logowania we frontendzie
  (sesja), HTTP Basic dla maszyn, polityka ról w bazie (zob. §4, §7, `docs/auth/`);
- polityki: **działa e2e** — tabela `policy_version` (Flyway V5) jako źródło prawdy, `PolicyStore` z
  atomową podmianą, walidacja, API `/api/policy/**` (zapis, historia, restore, eksport/import YAML),
  ekran Policies; audyt i odpowiedź czatu niosą `policyVersion` (Flyway V6);
- termin zgłoszenia projektu: 4.10.2026, 23:00 (RULES, pkt 5);
- decision pipeline, polityki, guardraile i data-driven test suite są jeszcze do
  zaimplementowania.

Każda zmiana architektury, technologii, priorytetu wyróżników albo kontraktu publicznego wymaga
aktualizacji tego pliku w tym samym commicie.
