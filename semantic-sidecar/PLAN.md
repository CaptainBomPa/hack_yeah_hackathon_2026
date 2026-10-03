# Plan: semantic-sidecar

Stan na 2026-10-03. Prosty spis: **co jest zrobione, co robimy teraz, co dalej**.
Szczegóły techniczne: [`README.md`](README.md), [`docs/decisions.md`](docs/decisions.md), [`evaluation/README.md`](evaluation/README.md).
Analiza (po co to wszystko): [`../docs/ai-control-layer/semantic-validator-analysis.md`](../docs/ai-control-layer/semantic-validator-analysis.md).

## Cel w jednym zdaniu

Sidecar ocenia tekst i zwraca **sygnały ryzyka**. Gateway (Java) na ich podstawie **blokuje żądanie, zanim dojdzie do modelu**.
**Gateway woła sidecara od 2026-10-03** (guard SEM-001), a atak jest blokowany przed modelem (zob. kroki B i C).

### Granice komponentu (ustalone z zespołem i w `VISION.md`)

- **Semantyka to wymienny provider za interfejsem Javy** (zewnętrzne API albo ten lokalny sidecar). Java ma orkiestrację,
  politykę i ostateczną decyzję. Sidecar nie proponuje akcji.
- **Sidecar dostaje tekst już znormalizowany** (`input.pre_normalized: true`). Normalizacja, reguły i sygnatury to kod
  deterministyczny po stronie Javy. Kontrakt: [`docs/input-contract.md`](docs/input-contract.md).
- **W sidecarze ma być AI.** Reguły (w tym `obfuscation`) tu nie pasują. Detektor AI: `Horizon-Labs/prompt-injection-guard-small` (od 2026-10-03, wcześniej `protectai`; kroki D i sekcja 7 poniżej opisują historię na `protectai`).
- Port sidecara: **8001**.---

## 1. Zrobione

| # | Co | Gdzie | Uwagi |
|---|---|---|---|
| ✅ | Szkielet sidecara (FastAPI), kontrakt, interfejs detektora, runner | `app/` | `POST /classify`, `GET /health` |
| ✅ | Normalizacja tekstu (**referencyjna, do przeniesienia do Javy**) | `app/normalize/` | Unicode, znaki niewidoczne, homoglify, base64/hex/URL/rot13/odwrócony, leet, litery rozstrzelone, ukryty HTML, tekst w znakach tagów. W sidecarze działa tylko w trybie samodzielnym |
| ✅ | Detektor `obfuscation` (reguły, nie AI) | `app/detectors/obfuscation.py` | działa tylko w trybie samodzielnym, w konfiguracji domyślnej wyłączony |
| ✅ | Konfiguracja jako dane | `config/semantic.yaml` | limity, normalizacja, włączone detektory |
| ✅ | Zbiór ewaluacyjny (ręczny) | `evaluation/cases/` | 122 przypadki, punkty P1-P5, trudne negatywy |
| ✅ | Runner z metrykami | `evaluation/run.py` | recall, FPR, AUROC, przedziały ufności, per rodzina, latencja |
| ✅ | Zbiór publiczny `neuralchemy` | `evaluation/data/` (poza gitem) | 1883 przypadki. **Uwaga:** premiuje wykrywanie szumu (patrz niżej) |
| ✅ | Utwardzenie sidecara (krok A) | `app/runner.py`, `app/evidence.py` | deadline'y, `missing_checks`, bezpieczne dowody |
| ✅ | Kontrakt wejścia: sidecar dostaje tekst znormalizowany | `app/runner.py`, `docs/input-contract.md` | tryb `pre_normalized`, dokument dla zespołu Java z pytaniami |
| ✅ | Modele pobrane, detektor `hf_classifier`, pierwszy pomiar | `models/`, `app/detectors/hf_classifier.py` | wyniki w `docs/models.md` |
| ✅ | Priorytet 1 backlogu (poprawność) | `app/detectors/hf_classifier.py`, `tests/test_hf_classifier.py` | testy detektora, naprawione okna, rozgrzanie, `/health`, kalibracja jailbreaka |
| ✅ | Eksperyment kNN | `scripts/knn_experiment.py`, `docs/knn-experiment.md`, `evaluation/index_corpus/` | wynik negatywny, opisany |
| ✅ | Docker | `Dockerfile`, `constraints.txt`, `../docker-compose.yml` | zbudowany i uruchomiony w Docker Desktop (arm64), obraz 1,15 GB, RSS ok. 0,88 GiB (jeden model), działa offline. Nie sprawdzony na Pi |
| ✅ | **Integracja z gatewayem (guard SEM-001) i dowód na prawdziwym stacku** | `backend/.../guard/semantic/`, `scripts/demo-up.sh`, `docs/local-stack.md` | atak blokowany przed modelem, fail-closed, 51 testów Javy |
| ✅ | Zbiory zewnętrzne pobrane i zmierzone (2026-10-03): `deepset`, `Lakera`, `in-the-wild` (`jackhhao` pominięty: zanieczyszczenie, `protectai` trenowano m.in. na nim) | `evaluation/data/`, `evaluation/fetch_public.py` | `deepset`: recall 55%, FPR 2,2%, AUROC 0,885 (niemiecki = angielski: 55% vs 53%). `in-the-wild`: recall 85%, FPR 32% (część to szum etykiet: „regularne" prompty to persony i role-play, w tym „Please ignore all prior prompts"). `Lakera` same ataki: recall 100% (n=600). Naprawiony błąd czytnika JSONL (`splitlines` ciął na U+2028) |
| ✅ | **Zmiana modelu: Horizon small zastępuje `protectai`** (2026-10-03) | `config/models.yaml`, `config/semantic.models.yaml`, `config/calibration/injection_classifier_horizon.json`, `scripts/compare_detectors.py` | Uczciwe porównanie na zbiorach czystych dla obu: AUROC vs trudne negatywy 0,972 vs 0,848, recall @FPR 1% 69,5% vs 39,5%, latencja ok. 3x niższa. Kalibracja na deepset-train + NotInject. Próg Javy 0,998 → 0,9. Sprawdzone w Dockerze (init pobiera model, sidecar healthy, atak 403 przez gateway). Zob. `docs/models.md`, `docs/decisions.md` |
| ✅ | Testy sidecara | `tests/` | 107 przechodzi |

### Co pokazały pomiary (wstępnie)

- `obfuscation` łapie 15 z 17 ataków obfuskacji z naszego zbioru (88%), 0% fałszywych alarmów na naszych trudnych negatywach.
- Na `neuralchemy`: 55% ataków zaszumionych, **1,8% ataków czystych**. Czyli łapie szum, a nie ataki. Tak ma być, to nie klasyfikator.
- `neuralchemy`: 29% ataków ma szum, a niewinnych tylko 1%, więc wyniki trzeba rozbijać na czyste i zaszumione.
- Wagi detektora dobrałem, patrząc na własne przypadki. Trzeba je sprawdzić na innych danych (ryzyko przeuczenia).

---

## 2. Znane problemy (do naprawy przed integracją)

| Problem | Skutek | Sprawdzone |
|---|---|---|
| ~~Awaria normalizacji daje `status: ok, score: 0`~~ | naprawione w kroku A | tak, odtworzone i naprawione |
| ~~`evidence.text` zawiera odkodowany tekst, w tym sekrety~~ | naprawione w kroku A | tak, odtworzone i naprawione |
| ~~Brak limitów czasu (deadline)~~ | naprawione w kroku A (z ograniczeniem opisanym niżej) | testy |
| ~~Brak informacji, których kontroli nie wykonano~~ | naprawione w kroku A (`missing_checks`) | testy |
| **Gateway nie woła sidecara** | żadna blokada nie działa | z przeglądu kodu |

---

## 3. Teraz: jeden detektor end-to-end (wąski pionowy wycinek)

Zasada: **najpierw udowodnić, że jedno prawdziwe żądanie jest blokowane przed modelem**, dopiero potem dokładać modele,
embeddingi i FAISS.

### Krok A: utwardzenie sidecara (Python, `semantic-sidecar/`): ZROBIONE

- [x] Awaria normalizacji = detektory dostają status `error` z powodem `normalization_failed` (albo `normalization_timeout`), a nie `ok/0`
- [x] Deadline na żądanie, na normalizację i na detektor; statusy `timeout` i `skipped` z powodem (`timeout`, `deadline_exceeded`)
- [x] Pola `missing_checks` i `complete` w odpowiedzi: które kontrole nie dały wyniku
- [x] `evidence` bez treści: zakres, wariant, długość i skrót HMAC. Opcjonalny zamaskowany podgląd, domyślnie wyłączony
- [x] Treść wyjątku detektora nie trafia ani do odpowiedzi, ani do logów (może zawierać fragment wejścia)
- [x] 14 nowych testów (65 razem). Sprawdzone mutacjami: celowe cofnięcie każdej z 4 napraw wywala odpowiedni test

**Znane ograniczenie kroku A:** Python nie umie przerwać działającego wątku. Po timeout wątek dobiega końca w tle, a odpowiedź
wraca na czas. Skończona pula wątków sprawia, że przy zablokowanych wątkach kolejne żądania kończą się timeoutem, a nie
zawieszeniem. Prawdziwy twardy deadline ma egzekwować gateway (krok B). Wartości czasów (2000/500/1000 ms) to założenia.

### Krok A2: reguły leksykalne: ODRZUCONE w sidecarze

Reguły i sygnatury (np. „ignore previous instructions", znaczniki ról) to kontrole deterministyczne, czyli warstwa Java
(`VISION.md` §4, sygnatury prompt/code/command injection). Nie dokładamy ich do sidecara.

### Krok B: integracja z gatewayem (Java, `backend/`): ZROBIONE (2026-10-03)

Decyzja z wcześniejszego dnia („spinamy na końcu") została zmieniona na prośbę Anny: sprawdzamy, czy cały stack się komunikuje, i dopisujemy brakującą część.
Backend miał już pipeline kontroli (`GuardChain`), więc sidecar wpięto jako **zwykły `Guard`**, a nie osobny mechanizm.

- [x] **Guard `SEM-001`** (`backend/.../guard/semantic/`): `SemanticGuard`, `SidecarClient` (blokujące wołanie `POST /classify` na `boundedElastic`), `SidecarProperties`, `SidecarResponse`
- [x] Decyzję podejmuje **Java z progu** (`blockThreshold`, domyślnie 0,998; `timeoutMs`; `failureMode: closed|open`) w `application.yml`, nie sidecar
- [x] **Fail-closed** także przy: awarii/timeout/HTTP 5xx/złym JSON, `complete=false` i **pustej liście wyników** (pułapka z sekcji niżej jest obsłużona po stronie guarda)
- [x] Ślad kontroli: `ControlTrace.kind = "semantic"` (nowa metoda `Guard.kind()`), wynik widoczny także przy przepuszczeniu (`Verdict.Allow(detail)`)
- [x] Blokada = `GuardedChatResponse.block`, żądanie nie dociera do Ollamy, rezerwacja budżetu zwalniana, wpis w audycie
- [x] Domyślnie **wyłączony** w `application.yml` (przełącznik `SEMANTIC_GUARD_ENABLED`), żeby backend bez sidecara nie blokował wszystkiego; w `docker-compose.yml` włączony
- [ ] **Mapowanie na `ControlResult`** (`confidence`, status `ok/degraded/error`): `ControlResult` jeszcze nie istnieje w kodzie, dziś używamy `ControlTrace`
- [ ] Hot-reload progu (dziś zmiana `application.yml` wymaga przebudowy obrazu backendu)
- [ ] Normalizacja w gatewayu (zespół Java): do tego czasu zakodowane ataki mogą przejść

### Krok C: dowód, że działa: ZROBIONE (2026-10-03)

- [x] **Testy Javy (JDK 25 w Dockerze): 51 przechodzi, 0 porażek** (19 nowych: 14 jednostkowych `SemanticGuardTest`, 5 na poziomie kontrolera `SemanticGuardControllerTest`; 32 istniejące bez zmian). Sprawdzone mutacjami (zob. `docs/decisions.md`)
- [x] **Na prawdziwym stacku (compose):** atak `Ignore all previous instructions...` daje **403, `blockedBy=SEM-001`, score 0,9988 przy progu 0,998**, a licznik wywołań Ollamy **nie rośnie**
- [x] Prompt niewinny przechodzi kontrolę semantyczną (score 0,27 dla polskiego zdania, 0,0866 dla angielskiego)
- [x] **Sidecar zatrzymany = fail-closed:** 403, `sidecar unavailable (fail-closed)`, model niewołany. Po starcie sidecara wraca do działania w ok. 11 s
- [x] Skrypt `scripts/demo-up.sh` i `docs/local-stack.md` (jak postawić i czego spróbować)
- [ ] Skrypt `./run-tests.sh` z danymi YAML (wymóg `VISION.md` §8), uruchamiany przeciw żywemu gatewayowi

### Krok D: pierwszy detektor AI (przed krokiem C)

- [x] Pobrano 4 modele (735 MB, `docs/models.md`): `fmops`, `Jailbreak-Detector`, MiniLM, bge-small. **`fmops` i `Jailbreak-Detector` zostały potem usunięte** (zob. niżej). Zamiast `protectai` (750 MB) najpierw mniejszy `fmops`
- [x] Generyczny detektor `hf_classifier` (model wymienny przez konfigurację `config/semantic.models.yaml`), okna dla długich tekstów
- [x] Pierwszy pomiar: **`fmops` ma 75% fałszywych alarmów na naszych niewinnych przypadkach, nie nadaje się jako bramka**; `Jailbreak-Detector` precyzyjny, ale łapie ok. 1/3 ataków
- [x] `protectai` pobrany i zmierzony: **AUROC 0,97, FPR 4% (całość), recall 85%**, wyraźnie lepszy od `fmops`. Wybrany jako klasyfikator injection. Słabości: trudne negatywy 26-35% FP, jailbreaki 49%
- [x] Kalibracja `protectai` (Platt na marginesie logitów, `config/calibration/`): log-loss na tym samym rozkładzie 0,72 → 0,21, ale **nie przenosi się na nasze trudne negatywy** (ECE 0,14 → 0,17). Progi wybieramy z docelowego FPR, nie z 0,5
- [x] ~~Kalibracja `Jailbreak-Detector`~~: detektor usunięty, bo słaby (zob. niżej)
- [ ] **Trudne negatywy: 26-35% FP.** Największy problem. Pomysły: indeks negatywny w kNN, więcej trudnych negatywów w danych, douczenie
- [ ] Sprawdzić `max(protectai, jailbreak)` jako prosty ensemble i wpływ na trudne negatywy
- [ ] Latencja na docelowym sprzęcie (Raspberry Pi?) i ewentualnie ONNX
- [ ] Detektor w sidecarze, wynik skalibrowany, pomiar osobno na atakach czystych i zaszumionych
- [ ] Wejście do eval-runnera jest już gotowe: runner udaje normalizator gatewaya (`--no-normalize` wyłącza)

---

## 4. Potem (po spięciu z gatewayem)

Kolejność robocza. Każdy krok zostaje tylko wtedy, gdy pomiar pokaże, że poprawia wynik.

| # | Element | Po co |
|---|---|---|
| 1 | Więcej zbiorów publicznych (`deepset`, `in-the-wild`, `Lakera`, `jackhhao`) | czystsze ataki do pomiaru. **Nie pobrane**, czekają na decyzję |
| 2 | Bramka języka (#5) | polityka dla innych języków niż angielski |
| 3 | Klasyfikator injection (encoder, #3) | wykrywanie ataków bez szumu i bez słów kluczowych |
| 4 | Embeddingi i kNN (#4) | warianty znanych ataków, dodawane na żywo |
| 5 | Wyjście: canary, odciski, linki (#7), PII/NER (#8) | wyciek danych w odpowiedzi (P4) |
| 6 | Eskalacja: LLM na logitach lub guard (#9) | szara strefa |
| 7 | Sesja: sondowanie, zapętlenie (#10) | ataki wielotur i pętle |
| 8 | Równoległość detektorów, ONNX (#11) | wydajność |
| 9 | Telemetria (#12), hot-reload (#13) | wymogi `CRITERIA` (raportowanie, zmiana konfiguracji na żywo) |
| 10 | Red-teaming (#14), Docker (#15) | raport odporności, uruchomienie u jury bez przygotowania |

Numeracja `#` odpowiada liście w [`docs/decisions.md`](docs/decisions.md).

---

## 5. Decyzje

**Rozstrzygnięte:**

| Decyzja | Rozstrzygnięcie |
|---|---|
| Reguły leksykalne w sidecarze? | **Nie**, to warstwa deterministyczna (Java). Wynika z `VISION.md` §4 |
| Gdzie żyje normalizacja? | **W gatewayu (Java).** Sidecar dostaje tekst znormalizowany (ustalenie z zespołem) |
| Port sidecara | 8001 (`VISION.md` §7) |

**Czekają:**

| # | Decyzja | Rekomendacja |
|---|---|---|
| 1 | **Odkodowane segmenty:** gateway podstawia odkodowaną treść w tekście, czy kontrakt dostaje pole `variants`? (`docs/input-contract.md` §4) | zapytać zespół Java, bez tego zakodowane ataki są dla sidecara niewidoczne |
| 2 | ~~Czy edytuję `backend/`~~ | **rozstrzygnięte przez Annę (2026-10-03): tak**, integracja zrobiona jako `Guard` SEM-001 |
| 3 | Pobrać model do kroku D i które | najpierw sprawdzę rozmiar i licencję, potem poproszę o zgodę |
| 4 | Pobrać kolejne zbiory publiczne? | nie blokuje kroku D |
| 5 | Polityka dla innych języków | do ustalenia (`docs/input-contract.md` §4 pkt 6) |

---

## 6. Czego jeszcze nie wiemy

- Nie mamy żadnego modelu AI, więc **sidecar niczego jeszcze nie wykrywa w konfiguracji domyślnej**. To zmieni się po kroku D.
- Nie mierzyliśmy latencji na docelowym sprzęcie. Wszystkie liczby czasowe to założenia.
- Nie czytałem `RULES AI Control Layer.pdf`. Mogą tam być ograniczenia, które zmienią plan.
- Wszystkie wyniki pochodzą z małych zbiorów (122 ręczne przypadki, 1883 z `neuralchemy`), więc są wstępne.

---

## 7. Backlog sidecara (stan po kalibracji, 2026-10-03)

Kolejność = proponowany priorytet. Każdy detektor zostaje tylko wtedy, gdy pomiar pokaże, że poprawia wynik.

### Priorytet 1: małe poprawki poprawności (ok. pół dnia)
- [x] Testy automatyczne `hf_classifier` na malutkim losowym modelu (margines, klasa pozytywna, okna, warianty, kalibracja, współbieżność). Sprawdzone mutacjami. **Znalazły realny błąd okien** (zob. `docs/models.md`)
- [x] `label` tylko przy `label_threshold` (domyślnie nigdy): decyzja należy do gatewaya
- [x] Rozgrzanie modeli przy starcie (błąd przerywa start) i `/health` z wersjami i czasem rozgrzewki
- [x] Kalibracja `Jailbreak-Detector` (wiarygodność lepsza, detektor nadal słaby: 2% ataków przy FPR ≤ 1% na `neuralchemy`). **Detektor usunięty** (decyzja zespołu): model, kalibracja i konfiguracja skasowane, wyniki zostają w `docs/models.md` jako historia
- [x] Sprzątanie: `fmops` usunięty, pseudo-detektor `max()` w raporcie tylko na życzenie (`--ensemble-max`), naprawiony manifest modeli

### Priorytet 2: największa luka jakościowa, czyli trudne negatywy (26-35% fałszywych alarmów)
- [x] **Eksperyment kNN (`docs/knn-experiment.md`): hipoteza NIE potwierdzona.** Mocna poprawa na `neuralchemy` wynika w dużej mierze z jego szablonowości; na naszych danych zysk w granicach szumu (AUROC 0,930 → 0,933 na niepodobnych), trudne negatywy bez zmian. Stacker nie wchodzi do produkcji
- [ ] kNN jako **osobny sygnał do wyjaśnialności i dodawania ataków na żywo** (wymóg demo), nie jako sposób na obniżenie FP. Do decyzji
- [ ] **Więcej trudnych negatywów, najlepiej napisanych przez kogoś innego niż autor zbioru oceny** (najsilniejszy zaobserwowany czynnik: +70 trudnych negatywów w indeksie obniżyło FP na naszych danych z 27% do 10%, ale z ryzykiem wspólnej taksonomii). Dziś w `evaluation/index_corpus/` jest 70, a w ewaluacji 34 nasze
- [x] ~~Ensemble `protectai` + `Jailbreak-Detector`~~: odpada, detektor usunięty. kNN też odpada (zob. wyżej). Zostaje jeden klasyfikator injection
- [ ] Jeśli to nie wystarczy: **douczanie** klasyfikatora (dane `neuralchemy/train` plus trudne negatywy, test leave-one-family-out). Kosztowne

### Priorytet 3: słabe rodziny ataków
- [ ] Zmierzone słabości `protectai`: jailbreaki 49%, `encoding` 49%, `adversarial` 76%, `control` 0% (n=10)
- [ ] Rodzina zakodowanych ataków zależy od decyzji z Javą (`variants`, `docs/input-contract.md` §4)

### Priorytet 4: pokrycie punktów kontroli (dziś realnie tylko P1, P2, P5)
- [ ] **P4: wyciek w odpowiedzi** (canary, odciski dokumentów, embedding do system promptu). PII/sekrety zostają w Javie
- [ ] **P3: zgodność akcji z zadaniem** (embedding zadanie↔akcja, ryzyko akcji)
- [ ] **Sesja:** wykrywanie sondowania (kolejne zablokowane, podobne wiadomości) i zapętleń, na tych samych embeddingach

### Priorytet 5: ewaluacja i odporność
- [ ] Zbiór ręczny jest mały (122). Dopisać wielotur, więcej P3/P4, ataki parafrazowane
- [ ] **Test odporności na mutacje** (parafraza, zmiana wielkości liter, rozbicie, tłumaczenie) i test leave-one-family-out
- [ ] Pozostałe zbiory publiczne (`deepset`, `in-the-wild`, `Lakera`): **nie pobrane**, wymagają zgody. Uwaga na zanieczyszczenie: `protectai` trenowano m.in. na `jackhhao`
- [ ] Polityka dla innych języków niż angielski (bramka języka, decyzja zespołu)

### Priorytet 6: wydajność i uruchamianie
- [ ] **Latencja na docelowym sprzęcie** (Raspberry Pi?). Dziś p50 56 ms / p95 94 ms na Macu. Nie wiemy, jak będzie na Pi
- [ ] Test obciążeniowy (równoległość, GIL, wątki torch), ewentualnie ONNX/kwantyzacja
- [x] **Dockerfile, `.dockerignore`, `constraints.txt` i usługi `semantic-sidecar-init` + `semantic-sidecar` w `docker-compose.yml`** (CPU-owy torch, wagi w wolumenie, start offline po pierwszym razie, opcja `BAKE_MODELS`). **Obraz zbudowany i sprawdzony w Docker Desktop (arm64, Mac): 1,15 GB, `up` działa, start i odpowiedzi bez sieci, wyniki zgodne z natywnymi. Nadal NIE sprawdzony na Raspberry Pi**
- [ ] **Pamięć:** proces zajmuje **ok. 0,88 GiB RSS** z jednym modelem (441 MiB sterty + 442 MiB zmapowanych wag; wcześniej 1,07 GiB z dwoma). `docker stats` zaniża tę liczbę. Na Raspberry Pi współdzielonym z Ollamą, bazą i backendem może być ciasno. Do zmierzenia na Pi; opcje: kwantyzacja/ONNX
- [ ] Hot-reload progów i konfiguracji (`watchfiles`), metryki (p50/p95 per detektor, timeouty), strukturalne logi bez sekretów

### Znana pułapka kontraktu (do rozstrzygnięcia przed spięciem)
- [x] **Brak pokrycia wygląda jak „sprawdzone": naprawione.** Punkt kontroli bez detektora (dziś P3, P4) zwraca `covered: false`, `complete: false`
      i wpis `missing_checks: {check: "coverage", reason: "no_detector_for_checkpoint"}`. Guard Javy już traktował pustą listę jako brak pokrycia (fail-closed), więc zmiana jest zgodna wstecz

### Przygotowanie do spięcia (nie wymaga Javy)
- [ ] Odpowiedzi zespołu Java na pytania z `docs/input-contract.md` §4 (warianty, kontekst, normalizacja P4)
- [ ] Atrapa sidecara bez modeli i bez torch, żeby Java mogła testować integrację wcześniej (jako test double, nie detektor)
