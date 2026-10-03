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
```

Java jest właścicielem orkiestracji, polityk i ostatecznej decyzji. Javowy interfejs providera
oddziela decision pipeline od konkretnego modelu lub usługi. Implementacja może wywoływać
zewnętrzne API albo opcjonalny lokalny sidecar Python/FastAPI, gdy uzasadnia to ekosystem ML.
Awaria providera nie może omijać kontroli, a jego wymiana nie może zmieniać kontraktu
`ControlResult`.

Spring Cloud Gateway jest reaktywny, natomiast obecna persistencja JPA/JDBC jest blokująca.
Operacje bazodanowe nie mogą wykonywać się na event loopie WebFlux: należy izolować je na
`boundedElastic` albo w wydzielonej warstwie wykonawczej.

## 3. Technologie

| Warstwa | Decyzja |
|---|---|
| Backend/gateway | Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, WebFlux/Gateway, Gradle Groovy |
| Persistencja | JPA/Hibernate + JDBC; H2 dla profilu `local`, PostgreSQL + Flyway dla `prod` |
| Semantyka | Wymienny provider za interfejsem Javy: zewnętrzne API lub opcjonalny lokalny sidecar Python/FastAPI + Hugging Face/ONNX |
| Chroniony model | Ollama na Raspberry Pi; bazowy model `qwen2.5:1.5b-instruct-q4_K_M` |
| Frontend | React 18, TypeScript, Vite, Tailwind, Recharts |
| Uruchomienie | Docker Compose dla środowiska dev; `OLLAMA_BASE_URL` wybiera Ollamę lokalną lub na Raspberry Pi |

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
aktywnej wersji, ale nie wpływają na ruch. Polityka jest danymi (docelowo `policy.yaml`), ma
wersję i hash oraz jest przeładowywana atomowo. Błędna wersja nie zastępuje ostatniej poprawnej.
Nie wolno hardcodować progów i akcji w kontrolerach.

### Kontrole deterministyczne — Java

- wykrywanie i redakcja PII oraz sekretów (PESEL, IBAN, karty z Luhnem, klucze API);
- limity rozmiaru, zagnieżdżenia, tempa żądań i budżetu tokenów;
- allowlista modeli oraz docelowo narzędzi i schematów ich argumentów;
- sygnatury prompt/code/command injection, niebezpiecznej deserializacji i SSRF;
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

Docelowym wejściem dla playgroundu jest zgodny z OpenAI endpoint
`POST /v1/chat/completions`, wzbogacony o identyfikator żądania i trace kontroli. API
dashboardu korzysta z `/api/**`. Obecny backend udostępnia wyłącznie tymczasowy passthrough
`/llm/**`; nie jest on jeszcze chronionym API MVP.

Gateway działa na porcie `8000`, frontend na `3000`. Opcjonalny lokalny sidecar może działać
na `8001`; zewnętrzny provider jest konfigurowany adresem i poświadczeniami środowiskowymi.

## 8. Testy i kryteria akceptacji

Przypadki testowe są danymi YAML/JSON, nie kodem. Runner `./run-tests.sh` ma jednym poleceniem
wysyłać je do działającego gatewaya i sprawdzać status HTTP, akcję, politykę oraz obecność
bezpiecznego wpisu audytowego.

Minimalny zestaw obejmuje:

- poprawne prompty w języku polskim i angielskim;
- PII/secrets i redakcję wejścia oraz wyjścia;
- bezpośrednie i pośrednie prompt injection/jailbreak;
- niedozwolone tool-calls, SSRF i command injection;
- przekroczenie budżetu, timeout providera i brak poświadczeń;
- działanie wszystkich trybów kontroli oraz shadow nowej polityki;
- porównanie ruchu chronionego i niechronionego w Red Team Arena.

Raport pokazuje skuteczność, benign-block rate, p50/p95 i pokrycie kontroli. Testy jednostkowe
i integracyjne backendu uzupełniają suite, ale jej nie zastępują.

## 9. Plan implementacji

1. Uzgodnić kontrakty `ControlRequest`, `ControlResult`, decyzję końcową i format polityki.
2. Zbudować javowy decision pipeline z trybami oraz podstawowymi regułami PII/secrets.
3. Dodać bezpieczny audyt, metryki, budżet i endpointy dashboardu.
4. Zaimplementować interfejs providera semantycznego, wybrany adapter, timeouty i zachowanie
   degradacyjne; lokalny sidecar dodać, jeśli pozwoli czas.
5. Zastąpić passthrough przez chronione `/v1/chat/completions` i podłączyć frontend.
6. Dostarczyć Explainable Verdict, test runner i Red Team Arena.
7. Dopiero potem rozważać wyróżniki A, C i D.

## 10. Stan repozytorium

- `backend/` — działający szkielet Java 25/Spring Boot 4 z profilami H2/PostgreSQL,
  Flyway, Dockerfilem i niechronionym passthrough `/llm/**`;
- `frontend/` — działający szkielet widoków i mocków, bez podłączonego docelowego API;
- provider analizy semantycznej ani opcjonalny lokalny sidecar nie mają jeszcze implementacji;
- `docker-compose.yml` — uruchamia bazę, backend, frontend i lokalną Ollamę; adres modelu można
  nadpisać przez `OLLAMA_BASE_URL`, aby wskazać Raspberry Pi;
- decision pipeline, polityki, guardraile, audyt i data-driven test suite są jeszcze do
  zaimplementowania.

Każda zmiana architektury, technologii, priorytetu wyróżników albo kontraktu publicznego wymaga
aktualizacji tego pliku w tym samym commicie.
