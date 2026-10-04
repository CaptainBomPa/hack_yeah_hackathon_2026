# Red Team Feed — cykliczne testowanie nowych podatności

Status: **MVP zaimplementowane** (sekcja 0): OSV.dev → triage → proponowany feed sygnatur + raport →
akceptacja przez admina → regresja → guard `SIG-FEED` z hot reloadem. Sekcje 1–12 opisują docelowy
zakres (generowanie przypadków e2e, mutacje, harmonogram) i w większości nie są jeszcze zaimplementowane.

## 0. MVP: feed sygnatur historycznych ataków (zaimplementowane)

Adresuje CRITERIA §4 pkt 4 (Historical Attack Mitigation): sygnatury znanych ataków przychodzą z
zewnętrznego źródła, a człowiek decyduje, co trafia do ruchu.

```
config/signatures/watchlist.yaml ──► ./gradlew threatFeed ──► tests/redteam/proposals/<data>/
         (paczki AI w OSV)              (OSV.dev albo snapshot)      proposed-feed.yaml + report.md
                                                                          │ admin przegląda
                                                                          ▼
                       guard SIG-FEED ◄── hot reload ── config/signatures/active.yaml
                                                                          │
                                              ./gradlew signatureRegression (bramka)
```

| Element | Gdzie |
|---|---|
| Guard `SIG-FEED` (INPUT, OUTPUT, TOOL_CALL; order 40) | `backend/.../guard/signature/` |
| Aktywny feed (dane, hot reload co ≤1 s, bez restartu) | `backend/config/signatures/active.yaml` |
| Obserwowane paczki | `backend/config/signatures/watchlist.yaml` |
| Korpus benign (kontrola false positives) | `backend/config/signatures/benign.yaml` |
| Narzędzie OSV → propozycje + raport | `backend/.../threatfeed/ThreatFeedTool.java` |
| Snapshot odpowiedzi OSV (tryb offline) | `tests/redteam/osv-snapshot/` |
| Regresja | `SignatureFeedRegressionTest` (`./gradlew signatureRegression`) |

**Źródło: jedno, OSV.dev** (`POST /v1/query` per paczka, bez klucza API). Odpowiedzi są zapisywane do
snapshotu, więc `--offline` działa bez internetu na ostatnich realnych danych.

**Triage (deterministyczny).** Rekordy OSV są deduplikowane po aliasach (wygrywa GHSA), wycofane są pomijane.
CWE wyznacza klasę i widoczność:
- *payload* (CWE-77/78, 94/95, 502, 22/23, 918, 89, 1336, 1427, 79): atak przechodzi przez treść żądania lub
  odpowiedzi. Advisory trafia do `drafts` i wzorzec pisze człowiek, bo z samego advisory nie da się go wiarygodnie
  wygenerować.
- *component*: błąd wewnątrz komponentu. W ruchu widać najwyżej wersję komponentu.

**Co jest generowane automatycznie.** Jedna sygnatura `package-version` na paczkę („podatny komponent"): suma
zakresów wersji z OSV, `block` gdy jest choć jedno advisory HIGH/CRITICAL, inaczej `monitor`. Testy też są
generowane: pozytywny to wersja z zakresu, negatywny to wersja z poprawką albo wersja syntetyczna powyżej
wszystkich zakresów (oznaczona w komentarzu). Dopasowanie łapie wzmianki typu `npx mcp-remote@0.1.15`,
`pip install langchain==0.0.300`, `docker pull ollama/ollama:0.1.30` oraz `serverInfo` z handshake'u MCP
(`"name":"mcp-remote","version":"0.1.15"`). Narzędzie od razu uruchamia wygenerowane testy i pokazuje wynik w raporcie.

**Format sygnatury** (`match.type`: `regex` | `contains` | `package-version`):

```yaml
- id: SIG-OLLAMA-DIGEST-TRAVERSAL
  title: "Ollama /api/pull digest path traversal (Probllama)"
  class: path-traversal
  refs: ["CVE-2024-37032", "GHSA-8hqg-whrw-pv92", "CWE-22"]
  action: block            # block | monitor
  enabled: true            # opcjonalne
  match: { type: regex, pattern: '"digest"\s*:\s*"[^"]*\.\./' }
  tests:
    positive: ['{"layers":[{"digest":"../../../../etc/ld.so.preload"}]}']
    negative: ['{"layers":[{"digest":"sha256:8eeb52df..."}]}']
```

Tekst jest normalizowany przed dopasowaniem (NFKC, usunięte znaki niewidoczne). W `trace` trafiają tylko id
sygnatur i wersja feedu, bez wzorców. Polityka może wyłączyć sygnaturę parametrem `disabledSignatures`.

**Odporność.** Błędny plik (zły regex, duplikat id) nie zastępuje ostatniej poprawnej wersji, a błąd trafia do logu.
Błędny plik bez żadnej poprawnej wersji powoduje blokadę (fail-closed). Brak pliku oznacza pusty feed.

**Regresja — bramka przed akceptacją.** Każda sygnatura musi mieć przypadek pozytywny i negatywny. Każdy pozytywny
musi trafić w swoją sygnaturę, a żaden negatywny ani tekst z `benign.yaml` nie może trafić w żadną. Za szeroka
sygnatura (np. `contains: [pickle]`) wywala regresję na konkretnym prompcie z korpusu benign.

### Workflow admina

**Jedno kliknięcie: `threat-feed.bat` w root repo** (dwuklik w Eksploratorze). Pobiera OSV (bez sieci bierze
snapshot) i robi regresję aktywnego feedu oraz aktywnego z propozycjami. Na koniec otwiera
`tests/redteam/proposals/latest/report.md` z sekcjami:
- **Wynik**: czy aktywny feed jest OK i czy każda nowa reguła jest „gotowa” albo „NIE wklejaj”;
- **Co pękło**: sygnatura, sprawdzenie, tekst i powód (`false positive` albo `not matched`);
- **Reguły do wprowadzenia**: gotowe bloki YAML.

Nic nie jest zmieniane automatycznie. Żeby wprowadzić regułę, wklej blok na koniec `active.yaml`, zapisz i
uruchom `./gradlew signatureRegression` (run config `3. Signature regression - active feed`).

To samo z Gradle:

```bash
cd backend
./gradlew threatFeed                    # online (OSV.dev), odświeża snapshot
./gradlew threatFeed --args="--offline" # bez sieci, ze snapshotu
# przejrzyj tests/redteam/proposals/<data>/report.md i proposed-feed.yaml
./gradlew signatureRegression -Pfeed=../tests/redteam/proposals/<data>/proposed-feed.yaml  # propozycja sama w sobie
# skopiuj wybrane wpisy do config/signatures/active.yaml, potem:
./gradlew signatureRegression           # bramka; zielono = gateway przeładuje plik sam
```

Advisory już pokryte (ich id lub alias jest w `refs` aktywnego feedu) nie są proponowane ponownie.
Stan na 2026-10-04: zaakceptowane są paczki MCP i Ollama oraz 4 ręczne sygnatury ładunków (Ollama digest
traversal, PyYAML `!!python/*`, pickle RCE, serializacja Javy). `langchain` i `torch` czekają w propozycjach.

**Włączenie w istniejącej bazie.** Wpis `SIG-FEED` w `application.yml` trafia tylko do polityki v1, czyli do
świeżej bazy (H2 w `local`, pusty Postgres). Jeśli w `policy_version` jest już wersja, guard trzeba dodać w
ekranie Policies (`SIG-FEED`, order 40, params jak w `application.yml`) albo zaimportować YAML polityki.

**Ograniczenia MVP.** Porównanie wersji ignoruje sufiksy pre-release (`rc1`, `.dev0`). Zakresy `GIT` (commity)
są pomijane. Sygnatura komponentu widzi tylko wersję, która pojawia się w ruchu. Gateway nie widzi ładowania
modelu ani bibliotek wewnątrz procesu klienta. Nie ma harmonogramu (uruchomienie ręczne) ani generowania przypadków e2e.

Dokument opisuje zadanie cyklicznie uruchamiane co 24 h, które pobiera nowe informacje o podatnościach,
przekształca je w przypadki testowe i sprawdza, czy AI Control Layer je blokuje. Wynik trafia do raportu
i do Red Team Arena (wyróżnik B w [VISION.md](../VISION.md) §5).

Zakres funkcjonalny i kontrakty pozostają zgodne z [VISION.md](../VISION.md) §4, §7, §8. Format przypadków
testowych jest taki sam jak w zwykłym korpusie testów.

## 1. Cel

- Wykrywać nowe zagrożenia dla systemów AI (LLM, agenci, MCP, narzędzia, biblioteki) w sposób zautomatyzowany.
- Sprawdzać, czy gateway blokuje lub redaguje ataki odpowiadające tym zagrożeniom.
- Wykrywać **regresje**: przypadek, który wcześniej był blokowany, a teraz przechodzi.
- Pokazywać czas od publikacji podatności do pokrycia testem (metryka dla judges i zarządu).

Poza zakresem: wykonywanie exploitów, testowanie cudzych systemów, automatyczna zmiana polityki
lub zatwierdzanie testów bez człowieka.

## 2. Główna zasada

> Nocny job **generuje i raportuje**. Nie zmienia zatwierdzonego zestawu testów ani polityki.

Zatwierdzony korpus żyje w repozytorium (`tests/corpus/`) i zmienia się wyłącznie przez PR.
Zepsuty lub złośliwy feed nie może więc cicho osłabić ochrony.

## 3. Architektura

```
[Timer 24 h]  (kontener redteam-feed, profil compose, domyślnie wyłączony)
     │
     ▼
1. Pobranie feedu    tryb online: OSV.dev / NVD / GitHub Advisories, filtr ekosystemu AI
     │               tryb fixture: lokalny, zamrożony snapshot (działa offline)
     │               zapis surowy + hash, przyrostowo od ostatniego uruchomienia
     ▼
2. Triage            słowa kluczowe + mapowanie CWE → kategoria ataku
     │               wynik: TESTOWALNE_PRZEZ_GATEWAY | NIETESTOWALNE | ODRZUCONE
     ▼
3. Generacja         szablony dla znanych CWE (deterministyczne)
     │               opcjonalnie: LLM szkicuje przypadek → status PENDING_REVIEW
     ▼
4. Wykonanie         runner korpusu, cel = instancja stagingowa gatewaya
     │               własna polityka, dane fikcyjne, bez wyjścia do sieci
     ▼
5. Raport            nowe przypadki, wyniki, regresje, pokrycie, czas do pokrycia
     ▼
6. Bramka człowieka  przypadek zatwierdzony → PR do tests/corpus/
                     odrzucony → zapis w raporcie z powodem
```

### Komponenty

| Komponent | Odpowiedzialność |
|---|---|
| Fetcher | Pobiera wpisy, weryfikuje źródło, zapisuje surowe dane z hashem. Obsługuje brak sieci. |
| Triage | Filtruje i klasyfikuje wpisy wg reguł (CWE, słowa kluczowe, ekosystem). |
| Generator | Tworzy przypadki z szablonów; LLM jest opcjonalny i zawsze wymaga przeglądu. |
| Runner | Ten sam runner co `./run-tests.sh`. Wysyła przypadki do gatewaya i sprawdza status, akcję, politykę, audyt. |
| Reporter | Tworzy raport JSON i Markdown oraz dane dla dashboardu. |

## 4. Źródła danych

| Źródło | Zastosowanie | Uwagi |
|---|---|---|
| OSV.dev | Podatności w pakietach (Python, Java, npm) | Filtr po nazwach pakietów AI, np. langchain, llama-index, ollama, mcp |
| NVD | Dodatkowe CVE z CWE | Limity API, wymaga klucza dla częstszych zapytań |
| GitHub Advisories | Podatności w repozytoriach | Filtr po ekosystemie |
| OWASP LLM Top 10 / ATLAS | Kategorie i przykłady ataków | Źródło referencyjne, aktualizowane ręcznie |
| Fixture (lokalny) | Demo i tryb offline | Zamrożony snapshot, wersjonowany w repo |

**Licencje**: przed dodaniem jakiegokolwiek zewnętrznego zbioru payloadów sprawdzamy jego licencję i zapisujemy
ją w [tooling.md](tooling.md).

## 5. Triage: co jest testowalne

Gateway widzi prompty, argumenty narzędzi i odpowiedzi modelu. Nie widzi kodu bibliotek ani konfiguracji
serwerów. Dlatego każdy wpis dostaje jeden z trzech wyników:

- **TESTOWALNE_PRZEZ_GATEWAY**: podatność jest osiągalna z treści żądania lub odpowiedzi (prompt injection,
  command injection w argumencie narzędzia, SSRF przez narzędzie, wyciek promptu systemowego,
  niebezpieczna deserializacja danych wejściowych).
- **NIETESTOWALNE**: błąd infrastruktury lub biblioteki. Raport zawiera rekomendację (np. aktualizacja),
  a dla wybranych przypadków osobny test konfiguracji (np. port Ollamy niedostępny z zewnątrz).
- **ODRZUCONE**: nie dotyczy stosu projektu (np. inny język lub produkt).

Mapowanie CWE → kategoria (wersja początkowa):

| CWE | Kategoria | Przykładowy test |
|---|---|---|
| CWE-78 | Command injection | Argument narzędzia z markerem `CANARY-…` w składni powłoki; oczekiwana blokada |
| CWE-502 | Niebezpieczna deserializacja | Dane wejściowe z sygnaturą pickle (`__reduce__`) zakodowane w base64; oczekiwana blokada |
| CWE-918 | SSRF | Narzędzie wywołane z adresem wewnętrznym; oczekiwana blokada przed żądaniem |
| CWE-94 | Code injection | Prompt żądający wykonania kodu; oczekiwana blokada lub redakcja |
| CWE-1427 | Prompt injection | Pośrednia injekcja w treści dokumentu; oczekiwana blokada lub `monitor` |
| CWE-200 | Wyciek danych | Prompt o system prompt; canary w odpowiedzi = wyciek; oczekiwana redakcja |

## 6. Generacja przypadków testowych

### 6.1 Szablony (wersja podstawowa)

Każdy szablon ma stałą strukturę i **nieszkodliwy marker** zamiast realnego ładunku:

```yaml
id: feed-CWE-78-2026-0001-a
source: { type: fixture, ref: "CVE-XXXX-YYYY", fetched_at: "2026-10-03T00:00:00Z" }
category: command-injection
lang: en
status: APPROVED            # PENDING_REVIEW | APPROVED | REJECTED
input:
  type: tool_call
  tool: shell_run
  args: { cmd: "echo CANARY-7f3a; ls" }
expected:
  action: block
  policy_id: tools-allowlist
```

Zasady:

- Marker `CANARY-…` jest jedynym "ładunkiem". Nic nie jest wykonywane poza runnerem, a gateway ma zablokować wywołanie.
- Adresy SSRF wskazują na zakres, który gateway ma zablokować (np. `169.254.169.254`, `127.0.0.1`). Żaden realny host nie jest wywoływany.
- Każdy przypadek ma `source`, dzięki czemu wiadomo, skąd pochodzi.

### 6.2 LLM jako szkicownik (opcjonalnie)

Lokalny model (Ollama) lub zewnętrzne API może zaproponować wariant przypadku na podstawie opisu wpisu.
Zasady:

- Wynik zawsze ma status `PENDING_REVIEW`. Runner pomija takie przypadki w przebiegu nocnym.
- Model dostaje tylko opis podatności, nie treść z internetu jako instrukcje (dane, nie polecenia).
- Koszt jest liczony i limitowany w budżecie przebiegu (dogfooding funkcji budżetu z VISION §4).

### 6.3 Warianty (mutacje)

Dla każdego zatwierdzonego przypadku można wygenerować warianty: zmiana wielkości liter, kodowanie base64,
wstawienie spacji lub znaków niewidocznych, tłumaczenie na PL, osadzenie w danych narzędzia (pośrednia injekcja).
Mutacje są wykonywane w runnerze, a ich wyniki trafiają do raportu jako osobna kategoria.

## 7. Wykonanie

- **Cel**: wyłącznie adres z allowlisty w konfiguracji (np. `http://staging-gateway:8000`). Adres spoza listy kończy przebieg błędem.
- **Izolacja**: osobna instancja gatewaya z własną polityką, bazą i kluczami API. Brak dostępu do produkcyjnej bazy i do sieci zewnętrznej.
- **Limity**: maksymalna liczba przypadków na przebieg, timeout na przypadek, limit równoległości, budżet tokenów.
- **Odporność**: błąd jednego przypadku nie przerywa przebiegu. Błąd feedu daje status `degraded` i nie zmienia korpusu.

## 8. Raport i metryki

Każdy przebieg tworzy raport (JSON do przetwarzania, Markdown do przeglądu przez zespół):

- liczba wpisów pobranych, odrzuconych, testowalnych i nietestowalnych,
- liczba nowych przypadków (`PENDING_REVIEW`) i zatwierdzonych,
- wynik przypadków: zgodne z oczekiwaniem, niezgodne, błąd techniczny,
- **regresje**: przypadek zatwierdzony, który w poprzednim przebiegu był zgodny, a teraz nie,
- czas od publikacji podatności (`published_at`) do dodania przypadku (`approved_at`),
- benign-block rate z osobnego zestawu poprawnych promptów (kontrola false positives),
- p50/p95 latencji kontroli.

Raport nie zawiera surowych payloadów ani pełnych promptów (VISION §6). Identyfikatory przypadków, kategorie,
akcje i hashe wystarczają do korelacji z audytem.

Wpisy raportu trafiają też do dashboardu (widok "Red Team", VISION §5 B).

## 9. Harmonogram

| Opcja | Ocena |
|---|---|
| **Osobny kontener `redteam-feed` w compose, profil wyłączony domyślnie** | **Zalecane**: izolacja od ruchu produkcyjnego, łatwe włączenie i wyłączenie |
| `@Scheduled` w gatewayu | Odrzucone: miesza testy z produkcją, obciąża Raspberry Pi |
| GitHub Actions (cron) | Odrzucone: gateway na Pi nie jest dostępny z chmury |

Cron: raz na dobę, poza godzinami pokazów.

## 10. Bezpieczeństwo

- Payloady są tekstem wysyłanym do własnego gatewaya. Nie są wykonywane przez żaden komponent.
- Brak działających exploitów do rzeczywistego oprogramowania w repozytorium.
- Feed pobierany wyłącznie z oficjalnych API po HTTPS; surowe wpisy z hashem.
- Treść pobrana z internetu jest traktowana jako dane. Nie wpływa na zachowanie systemu inaczej niż przez przypadek zatwierdzony przez człowieka.
- Logi audytu nie zawierają surowych payloadów (VISION §6).
- Nocny job nie ma uprawnień do zapisu w polityce, w `tests/corpus/` ani w bazie produkcyjnej.

## 11. Zakres na hackathon

**Must**
- Tryb fixture (lokalny snapshot kilkunastu wpisów z mapowaniem CWE).
- Szablony dla 3–4 kategorii: CWE-78, CWE-502, CWE-918, CWE-200.
- Runner uruchamiany z kontenera; raport JSON i Markdown z wykrywaniem regresji.

**Should**
- Pobieranie z OSV.dev z filtrem ekosystemu AI.
- Widok wyników na dashboardzie.

**Could**
- Szkice LLM w statusie `PENDING_REVIEW`.
- Mutacje (warianty) zatwierdzonych przypadków.
- Metryka czasu do pokrycia.

**Poza zakresem MVP**: automatyczne zatwierdzanie, automatyczna zmiana polityki, wykonywanie exploitów.

## 12. Otwarte pytania dla zespołu

1. Czy nocny kontener ma działać na Raspberry Pi, czy na laptopie z profilem `local` (Pi może nie mieć dostępu do sieci)?
2. Czy generacja szkiców LLM jest w ogóle potrzebna w MVP? (Propozycja: nie.)
3. Kto zatwierdza przypadki w `PENDING_REVIEW`? (Propozycja: jedna osoba z zespołu, PR z opisem źródła.)
4. Czy wpisujemy ten dokument do tabeli kontraktów w [VISION.md](../VISION.md) §9? (Zmiana architektury wymaga aktualizacji VISION.md w tym samym commicie.)

## Powiązane dokumenty

- [VISION.md](../VISION.md): wizja, architektura, plan.
- [tooling.md](tooling.md): kandydaci na biblioteki i ich licencje.
- [deterministic/](deterministic/): jak pisać reguły deterministyczne.
