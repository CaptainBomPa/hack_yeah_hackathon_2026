# Modele do pobrania (krok D)

Stan (2026-10-03): **zostaje jeden klasyfikator, `protectai` (749 MB), plus embeddingi `MiniLM` i `bge` (używane tylko w eksperymencie kNN).** Pobrano 4 modele (735 MB), pierwszy pomiar zrobiony (sekcja „Wyniki pierwszego pomiaru"). `fmops` i `Jailbreak-Detector` zostały usunięte po pomiarze (sekcje niżej). Zmiana wobec propozycji: zamiast
`protectai/deberta-v3-base-prompt-injection-v2` (750 MB) pobrano mniejszy `fmops/distilbert-prompt-injection` (268 MB), bo zespół chciał mały model
na test. `protectai` można wpiąć przez samą konfigurację. Rozmiary, licencje, liczby parametrów i bramki dostępu
sprawdziłem w API Hugging Face. Informacje o jakości modeli pochodzą z kart modeli (wyniki autorów, nie nasze) albo z
mojej pamięci i są oznaczone. **Żaden model nie był jeszcze uruchomiony ani zmierzony na naszych danych.**

Wejście modeli to tekst **już znormalizowany** przez gateway (`input-contract.md`). Wszystkie modele są angielskie.

## Tier 1: pobieramy teraz (ok. 1,24 GB modeli)

| # | Model | Rozmiar plików | Licencja | Parametry | Do czego służy |
|---|---|---|---|---|---|
| 1 | ~~`protectai/deberta-v3-base-prompt-injection-v2`~~ → **faktycznie pobrany: `fmops/distilbert-prompt-injection`** (268 MB, Apache-2.0, 67 M). Poniższy opis dotyczy `protectai` | ok. 750 MB (`model.safetensors` 738 MB + tokenizer) | Apache-2.0 | 184 M | **klasyfikator prompt injection**: P1 (prompt), P2 (dane niezaufane), P5 (zapis do pamięci). Zwraca prawdopodobieństwo `INJECTION` |
| 2 | ~~`madhurjindal/Jailbreak-Detector`~~ **USUNIĘTY** (słaby: przy FPR ≤ 1% łapał 2,1% ataków z `neuralchemy` i 40,6% naszych, brak pokrycia wyciągania promptu i zakodowanych ataków). Opis poniżej to historia | ok. 265 MB | MIT | 66 M (DistilBERT) | był: klasyfikator jailbreaków (role-play, persony, DAN): P1 |
| 3 | `sentence-transformers/all-MiniLM-L6-v2` | ok. 92 MB | Apache-2.0 | 23 M | **embeddingi**: podobieństwo do korpusu znanych ataków (kNN), później wykrywanie sondowania i zapętleń |
| 4 | `BAAI/bge-small-en-v1.5` | ok. 134 MB | MIT | 33 M | **embeddingi, kandydat zamienny z #3**: pobieramy oba, mierzymy, zostawiamy lepszy |

Pliki, których **nie** pobieramy: katalog `checkpoint-294/` w `Jailbreak-Detector` (z `optimizer.pt` 526 MB), duplikaty
formatów (`pytorch_model.bin`, ONNX, TF/Flax) oraz inne warianty. Pobieranie z `allow_patterns` i **przypiętą rewizją
(hash commita)**, żeby wynik był powtarzalny.

### Zależności Pythona (macOS arm64, rozmiary kół z PyPI)

`torch` 127 MB, `transformers` 12,6 MB, `tokenizers` 3 MB, `safetensors` 0,5 MB, `sentence-transformers` 0,7 MB oraz ich
zależności (m.in. `scipy` ok. 21-29 MB, `scikit-learn` 8 MB, `numpy` 5-12 MB). Łącznie szacuję **0,2-0,3 GB**
(zależności tranzytywne `torch` nie były liczone, to oszacowanie). Licencje: Apache-2.0, MIT, BSD.

**Razem ok. 1,5 GB na dysku.** Modele trafiają do `semantic-sidecar/models/` (katalog w `.gitignore`).

## Dlaczego te, a nie inne

- **#1 ProtectAI**: najczęściej używany klasyfikator tego typu (ok. 760 tys. pobrań), licencja Apache-2.0, bez bramki dostępu.
  Jest w formacie ONNX, więc da się go później uruchomić bez PyTorcha.
- **#2 Jailbreak-Detector**: mały, MIT, bez bramki, więc tani do sprawdzenia. **O jego jakości wiem tylko tyle, że istnieje**
  (ok. 4 tys. pobrań, dane treningowe opisane jako „custom"). To kandydat do zmierzenia, nie wybór.
- **#3 i #4**: małe, bardzo popularne (MiniLM ok. 240 mln pobrań), licencje permisywne. Wybór między nimi zrobi pomiar.

## Ograniczenia, o których trzeba wiedzieć (z karty modelu #1)

- **Nie wykrywa jailbreaków i nie obsługuje języków innych niż angielski.** Stąd model #2.
- Autorzy **odradzają go dla system promptów** (fałszywe alarmy). Dla P1 i P2 jest właściwy, dla analizy system promptu nie.
- **Projekt jest zarchiwizowany** (nie jest rozwijany).
- Wyniki z karty (accuracy 95,25%, precision 91,59%, recall 99,74% na 20 000 promptach) to **pomiar autora na własnym
  zbiorze**, nie niezależny. Precision 91,6% przy recall 99,7% sugeruje zauważalną liczbę fałszywych alarmów, co trzeba
  sprawdzić u nas na trudnych negatywach.
- **Zanieczyszczenie danych:** karta #1 podaje, że trenowano go m.in. na `jackhhao/jailbreak-classification`. Nie wolno mierzyć
  go na tym zbiorze. Dane treningowe #2 są niejasne („custom"), więc najczystszym testem są **nasze ręczne przypadki**.
- Limit kontekstu #1 to 512 tokenów. Dłuższy tekst trzeba dzielić na okna (patrz analiza §3.1.3).

## Usunięcie `Jailbreak-Detector` (2026-10-03)

Decyzja zespołu po pomiarach: detektor jest zbyt słaby, żeby utrzymywać go w sidecarze. Usunięto: detektor z `config/semantic.models.yaml`, model z `config/models.yaml`,
kalibrację (`config/calibration/jailbreak_classifier.json`), plik 252 MB z `models/`, usługę pobierania z `docker-compose.yml` i `Dockerfile`, model z wolumenu Dockera.
Pin rewizji zostaje w komentarzu `config/models.yaml` (można przywrócić). Wyniki pomiarów poniżej zostają jako zapis, dlaczego go usunięto.
**Skutek:** jailbreaki (49% wykrywane przez `protectai`) nie mają już żadnego dodatkowego sygnału. Z jednym modelem proces zajmuje **ok. 883 MiB RSS**
(441 MiB sterty + 442 MiB wag, było 1,07 GiB), start kontenera do `healthy` ok. 13 s, init pobiera 749 MB.

## Wyniki pierwszego pomiaru (2026-10-03)

Pomiar na naszym zbiorze: 122 ręczne przypadki oraz 1883 z `neuralchemy`. Próg 0,5, wynik = surowe prawdopodobieństwo (niekalibrowane),
tekst znormalizowany przez runner. Modele ładowane z `models/`, pinowane rewizje w `config/models.yaml`.

| Detektor | Zbiór | Recall | FPR | FPR na trudnych negatywach | AUROC | Recall przy FPR ≤ 1% |
|---|---|---|---|---|---|---|
| `injection_classifier` (`fmops`) | nasze 122 | 97,8% (CI 89-100) | **75,0%** (CI 62-85) | 79,3% | 0,87 | 2,2% |
| `injection_classifier` (`fmops`) | wszystko (2005) | 96,6% | **47,5%** | 79,6% | 0,93 | 12,4% |
| `jailbreak_classifier` | nasze 122 | 34,4% (CI 20-52) | 0,0% (CI 0-9) | 0,0% | 0,83 | 40,6% |
| `jailbreak_classifier` | wszystko (2005) | 24,1% | 3,9% | 0,0% | 0,72 | 2,5% |

Latencja detektora: p50 ok. 15 ms, p95 ok. 32 ms (Mac, CPU, krótkie teksty). **To nie jest Raspberry Pi.**

**Wnioski (zmierzone, nie założone):**
- **`fmops` w obecnej postaci nie nadaje się jako bramka.** Wykrywa niemal wszystko (recall 97%), ale oznacza jako atak 75% naszych niewinnych
  przypadków i 42% niewinnych z `neuralchemy`. Przy FPR ≤ 1% recall spada do 2-12%. Prawdopodobieństwa są nasycone (blisko 0 albo 1), więc
  próg nie pomaga. Z przykładów: „Write a short gardening tips discussing programming languages" (0,994), „Ignore the noise in this signal"
  (0,999), „override the default theme" (0,999). Wygląda na to, że model reaguje na każdy tekst w trybie rozkazującym.
  Jego karta jest pusta (same metadane), a trenowano go na `deepset/prompt-injections`.
- **`Jailbreak-Detector` jest precyzyjny, ale ma niskie pokrycie:** 0% fałszywych alarmów na naszych przypadkach, ale łapie tylko ok. jedną trzecią
  ataków. Nie wykrywa wyciągania promptu (0% z 4) ani zakodowanych ataków. Przydatny co najwyżej jako sygnał wysokiej precyzji.
- **Szum nie tłumaczy wyniku:** recall `fmops` jest podobny dla tekstów czystych (96,1%) i zaszumionych (97,5%).
- Próbki są małe, a przedziały ufności szerokie dla naszych 122 przypadków. Wynik na `neuralchemy` jest bardziej stabilny, ale zbiór ma swoje
  wady (szablonowe niewinne prompty, premiowanie szumu).
- Embeddingów (`MiniLM`, `bge`) jeszcze nie zmierzono (brak detektora kNN).

### Porównanie z `protectai/deberta-v3-base-prompt-injection-v2` (pobrany po pierwszym pomiarze, 749 MB)

Ten sam zbiór i próg 0,5. Pomiar z 2026-10-03, wyniki surowe (niekalibrowane).

| Detektor | Zbiór | Recall | FPR | FPR na trudnych negatywach | Precyzja | AUROC | Recall przy FPR ≤ 1% | Latencja p50 / p95 |
|---|---|---|---|---|---|---|---|---|
| `fmops` (268 MB) | wszystko (2005) | 96,6% | 47,5% | 79,6% | 73,1% | 0,925 | 12,4% | 21 / 41 ms |
| **`protectai` (749 MB)** | wszystko (2005) | 85,1% | **4,0%** | 26,5% | 96,6% | **0,970** | **62,8%** | 56 / 94 ms |
| `Jailbreak-Detector` (264 MB) | wszystko (2005) | 24,1% | 3,9% | 0,0% | 89,0% | 0,719 | 2,5% | 21 / 42 ms |
| `fmops` | nasze 122 | 97,8% | 75,0% | 79,3% | 53,6% | 0,871 | 2,2% | 22 / 32 ms |
| **`protectai`** | nasze 122 | 91,3% (CI 80-97) | **19,2%** (CI 11-32) | 34,5% | 80,8% | **0,930** | 58,7% | 55 / 75 ms |
| `Jailbreak-Detector` | nasze 122 | 34,4% | 0,0% | 0,0% | 100% | 0,829 | 40,6% | 24 / 33 ms |

Latencja z Maca (CPU, 4 wątki, żądania z równoległością 1). **Nie dotyczy Raspberry Pi.**

**Wniosek: `protectai` jest wyraźnie lepszy od `fmops`** (AUROC 0,97 vs 0,93, FPR 4% vs 47%) kosztem ok. 2,7x większej latencji i 2,8x większego pliku.
`fmops` wyłączony w `config/semantic.models.yaml` (plik zostaje na dysku do porównań, można go usunąć).

Słabe strony `protectai` (zmierzone):
- **Trudne negatywy: 26-35% fałszywych alarmów** (tekst *o* injection, dokumentacja, słowa kluczowe). To najpoważniejszy problem dla demo.
- **Jailbreaki: 49% (n=98)**, zgodnie z kartą modelu, która mówi, że ich nie wykrywa. Rodziny `encoding` 49%, `adversarial` 76%, `control` 0% (n=10).
- **Prawdopodobieństwa są nasycone**: próg dający FPR ≤ 1% to 0,99999. Zmiana progu o drobny ułamek zmienia wynik skokowo, więc potrzebna jest kalibracja.
- Zbiór `neuralchemy` mógł częściowo pokrywać się z danymi treningowymi `protectai` (nie sprawdzałem), więc jego wynik na `neuralchemy` może być zawyżony.
  Nasze 122 przypadki są czystsze, ale małe.

## Kalibracja `protectai` (2026-10-03)

Metoda: skalowanie Platta na **marginesie logitów** (nie na softmaksie). Dopasowanie: `neuralchemy/train` (4391 przypadków, 60,4% ataków).
Ocena na osobnych danych. Parametry: `config/calibration/injection_classifier_protectai.json` (a=0,310, b=1,946). Skrypt: `scripts/calibrate.py`.

| Zbiór oceny | n | Brier przed → po | Log-loss przed → po | ECE przed → po | AUROC (bez zmian) |
|---|---|---|---|---|---|
| `neuralchemy` val+test (ten sam rozkład) | 1883 | 0,094 → **0,053** | 0,722 → **0,205** | 0,098 → **0,037** | 0,9735 |
| nasze ręczne (inny rozkład, trudne negatywy) | 98 | 0,140 → **0,152** (gorzej) | 1,231 → 0,663 | 0,141 → **0,174** (gorzej) | 0,9302 |

**Wnioski:**
- **Kalibracja poprawia wiarygodność wyniku na danych z tego samego rozkładu** (log-loss spada 3,5 raza), ale **nie przenosi się na nasze trudne
  negatywy**: najwyższy przedział (przewidywane 0,99) ma obserwowane tylko 0,81 trafień (n=52), bo część niewinnych tekstów (trudne negatywy)
  dostaje bardzo wysoki wynik. Kalibracja nie naprawia modelu, który jest pewny siebie i się myli.
- **Ranking i recall przy zadanym FPR nie zmieniają się** (Platt jest monotoniczny). Poprzedni próg 0,99999 dla FPR ≤ 1% był artefaktem
  float32; teraz to 0,9944 (neuralchemy) i 0,9978 (nasze).
- **Skalibrowany wynik to NIE jest prawdopodobieństwo ataku w produkcji.** Zbiór dopasowania ma 60% ataków, ruch produkcyjny zwykle znacznie mniej,
  a jego częstości nie znamy. Dlatego **progi wybieramy z docelowego FPR, nie z „0,5"**. Opcja `target_prior` przesuwa wynik na inną częstość.

Punkty pracy (skalibrowany wynik; z tego da się wyznaczyć profile `strict` / `balanced` / `permissive`):

| Dopuszczalny FPR | `neuralchemy`: próg | recall (CI) | FPR trudnych | nasze ręczne: próg | recall (CI) |
|---|---|---|---|---|---|
| 0,5% | 0,9972 | 62,7% (60-66) | 0% | 0,9978 | 58,7% (44-72) |
| 1% | 0,9944 | 66,9% (64-70) | 0% | 0,9978 | 58,7% (44-72) |
| 2% | 0,9658 | 79,0% (76-81) | 10% | 0,9978 | 58,7% (44-72) |
| 5% | 0,4755 | 93,6% (92-95) | 25% | 0,9977 | 58,7% (44-72) |
| 10% | 0,2160 | 95,5% (94-97) | 35% | 0,9963 | 60,9% (46-74) |

Progi z `neuralchemy` **nie przenoszą się** na nasze trudne negatywy: np. próg 0,4755 daje 5% FPR na `neuralchemy`, a na naszym zbiorze ten próg
oznaczałby ok. 29% (próg 0,5). Nasze ręczne mają 46 ataków i 52 negatywy (29 trudnych), więc przedziały ufności są szerokie.

## Poprawki po pierwszym pomiarze (2026-10-03)

- **Błąd okien (naprawiony).** W `transformers 5.18` opcja `stride` + `return_overflowing_tokens` zwracała tylko 2 okna (np. znaki 0-74 z 384),
  więc reszta długiego tekstu nie była oceniana. Znaleziony, bo mutacja „wyłącz limit okien" nie wywalała żadnego testu. Dotyczył 1,5% przypadków
  (94 z 6274, same ataki, najdłuższy 3989 tokenów). Okna układamy teraz ręcznie, z nakładaniem, a gdy jest ich więcej niż `max_windows` (domyślnie 8),
  oceniamy równomiernie rozłożone (z pierwszym i ostatnim) i zwracamy `coverage` < 1. Dla krótkich tekstów wyniki są identyczne jak wcześniej.
  Rekalibracja po naprawie praktycznie nic nie zmieniła (`protectai`: b = 1,9458 vs 1,9461).
- **Kalibracja `Jailbreak-Detector`** (Platt, `config/calibration/jailbreak_classifier.json`, a=0,359, b=1,700):

  | Zbiór oceny | Brier przed → po | Log-loss przed → po | ECE przed → po | AUROC |
  |---|---|---|---|---|
  | `neuralchemy` val+test (1872) | 0,407 → 0,208 | 1,789 → 0,608 | 0,428 → 0,079 | 0,715 |
  | nasze ręczne (71) | 0,261 → 0,196 | 1,076 → 0,567 | 0,277 → 0,174 | 0,829 |

  Wiarygodność wyniku wyraźnie lepsza, ale **sam detektor nadal jest słaby**: przy FPR ≤ 1% łapie 2,1% ataków z `neuralchemy` (40,6% naszych).
  Ma sens co najwyżej jako sygnał pomocniczy, nie jako bramka.
- **`fmops` usunięty** z dysku, konfiguracji i manifestu (pin rewizji w komentarzu w `config/models.yaml`, można przywrócić). `models/` ma teraz 1,2 GB (4 modele).
- **`label` w odpowiedzi** jest ustawiany tylko przy podanym `label_threshold` (domyślnie nigdy). Skalibrowany wynik 0,5 nic nie znaczy dla progu z FPR ≈ 1%, więc decyzję zostawiamy gatewayowi.
- **Rozgrzanie modeli przy starcie** (błąd przerywa start) i `/health` z wersjami, punktami kontroli i czasem rozgrzewki.

**Pamięć w kontenerze (RSS procesu, linux/arm64 w Docker Desktop):** 1,07 GiB (RssAnon 459 MiB + RssFile 610 MiB zmapowanych wag), po 15 kolejnych żądaniach bez zmian. `docker stats` pokazuje tylko ok. 490 MiB, bo strony pliku są księgowane na kontener init: do planowania pamięci używaj RSS.

**Pamięć (macOS arm64, dwa modele `protectai` + `Jailbreak-Detector`, torch CPU):** RSS ok. **1,06 GB po starcie i 1,09 GB po 11 żądaniach**; rozgrzewka 681 ms + 309 ms, gotowość ok. 7 s od uruchomienia (modele w cache dysku). Na Linuksie/arm64 liczby mogą się różnić.

Pomiar end-to-end po poprawkach (2005 przypadków, Mac, CPU): `protectai` AUROC 0,970, recall 62,8% przy FPR ≤ 1% (próg 0,9971), p50 49 ms / p95 87 ms.

## Tier 2: nie teraz (duże albo niepotrzebne, dopóki Tier 1 nie zostanie zmierzony)

| Model | Rozmiar | Licencja | Po co | Dlaczego odłożone |
|---|---|---|---|---|
| `vllm-sr/mmbert32k-jailbreak-detector-merged` | ok. 1,23 GB | Apache-2.0 | detektor jailbreaków z kontekstem 32 tys. tokenów (długie teksty bez okien) | duży, dane treningowe to zbiory toksyczności i bezpieczeństwa (`toxic-chat`, `Salad-Data`), nie czysto jailbreaki. Kandydat, jeśli #2 zawiedzie |
| `Qwen/Qwen3Guard-Gen-0.6B` | ok. 1,5 GB | Apache-2.0 | guard dla P4 i szarej strefy | to moderacja treści, nie injection. Po Tier 1 |
| `Qwen/Qwen3-1.7B` | ok. 4,06 GB | Apache-2.0 | LLM jako sędzia na logitach (eskalacja) | ciężki, tylko jeśli pomiar pokaże potrzebę |
| `answerdotai/ModernBERT-base` | ok. 600 MB | Apache-2.0 | baza do własnego douczenia klasyfikatora | tylko jeśli gotowe okażą się za słabe |

## Odrzucone

| Model | Powód |
|---|---|
| `meta-llama/Llama-Prompt-Guard-2-22M` | bramka z ręcznym zatwierdzeniem, licencja „other": jury nie zatwierdzi dostępu |
| `rogue-security/prompt-injection-jailbreak-sentinel-v2` | licencja „other", bramka automatyczna, 1,2 GB |
| `protectai/deberta-v3-small-prompt-injection-v2` | bramka automatyczna (wymaga logowania) |
| `deepset/deberta-v3-base-injection` | trenowany na `deepset/prompt-injections`, więc zanieczyszcza ewaluację na tym zbiorze |
| `jackhhao/jailbreak-classifier` | trenowany na `jackhhao/jailbreak-classification` (ten sam problem) |

## Co zrobimy po pobraniu

1. Skrypt `models/fetch_models.py`: pobranie z `allow_patterns`, przypiętymi rewizjami i zapisem sum kontrolnych do manifestu.
2. Detektory w sidecarze: `injection_classifier_protectai` (jedyny; `jailbreak_classifier` usunięty), `known_attack_similarity` (kNN odpadł jako sposób na fałszywe alarmy, zob. `knn-experiment.md`).
3. **Pomiar na naszym zbiorze**: recall przy FPR ≤ 1%, osobno na atakach czystych i zaszumionych, FPR na trudnych negatywach,
   latencja. Detektor zostaje w systemie tylko wtedy, gdy ablacja pokaże, że poprawia wynik.
4. Dopiero potem Tier 2, jeśli pomiar wykaże lukę.

## Czego nie wiemy

- Jak te modele zachowają się na naszych danych i na docelowym sprzęcie (Raspberry Pi?). Latencja nie była mierzona.
  `VISION.md` §10 mówi o wdrożeniu bazy, backendu i Ollamy na Pi, a miejsce sidecara nie jest tam ustalone.
- ~~Czy `Jailbreak-Detector` w ogóle działa sensownie.~~ Zmierzone: nie wystarczająco, usunięty.
- Jakość etykiet w danych treningowych poszczególnych modeli (nie audytowałem licencji danych treningowych).

## Porównanie kandydatów (2026-10-03, próg 0,5, bez kalibracji; `config/semantic.{models,piguard,horizon}.yaml`)

Modele: `protectai` (obecny), PIGuard (`leolee99/PIGuard`, MIT, rev. dd78b24e, własny loader `app/detectors/piguard_model.py`, bez `trust_remote_code`),
Horizon `prompt-injection-guard-small` (Apache-2.0, rev. 3215a27e, ModernBERT/mmBERT-small, standardowa klasa).
**Zanieczyszczenie danymi (z kart/paperów): PIGuard trenowany na `deepset` i `jackhhao`; Horizon na `neuralchemy` i `in-the-wild`; `protectai` na `jackhhao`.
Wyniki na takim zbiorze dla danego modelu są bez wartości (oznaczone †).**

| Zbiór | Metryka | protectai | PIGuard | Horizon small |
|---|---|---|---|---|
| NotInject (339 trudnych negatywów) | FPR | 49,9% | 11,5% | **10,0%** |
| Nasze ręczne (122) | AUROC / recall przy FPR≤1% | 0,930 / 58,7% | 0,913 / 47,8% | **0,983 / 82,6%** |
| deepset (n=662) | AUROC / recall @0,5 / FPR | 0,885 / 55% / 2,2% | † | **0,982 / 82,5% / 0,0%** |
| in-the-wild (n=600) | AUROC / FPR | 0,871 / 32% | 0,924 / 21% | † 0,946 / 5,8% |
| neuralchemy (n=1883) | AUROC | 0,974 | 0,871 | † 0,992 |
| Latencja detektora p50 (Mac) | | ok. 47 ms | ok. 45 ms | **ok. 14 ms** |
| Szczytowe RSS procesu (Mac) | | 0,91 GB | 1,73 GB (loader ładuje wagi dwa razy, do poprawy) | 0,84 GB |

Zastrzeżenie: nasze 122 przypadki są małe (szerokie przedziały ufności), a przypadki Horizonu o tej samej taksonomii mogą być podobne do jego danych syntetycznych.

## Uczciwe porównanie `protectai` vs Horizon small (2026-10-03, `scripts/compare_detectors.py`)

Porównanie rankingów (marginesy logitów, więc niezależne od kalibracji i progu) wyłącznie na zbiorach **czystych dla obu modeli** (zob. nagłówek skryptu):
NotInject, OR-Bench-hard (600), nasze trudne negatywy; deepset, Lakera (400), Simsonsun (400), nasze ataki; BIPIA i PIArena (P2, próbki zrównoważone).
Bootstrap 1000 powtórzeń, 95% przedziały ufności, różnice sparowane.

| Scenariusz | Metryka | protectai | Horizon small | Różnica (95% CI) |
|---|---|---|---|---|
| ataki bezpośrednie (1109) vs TRUDNE negatywy (968) | AUROC | 0,848 | **0,972** | +0,124 [+0,110, +0,139] |
| | recall @ FPR 1% | 39,5% | **69,5%** | +31,9 pp [+18, +42] |
| | recall @ FPR 5% | 48,9% | **88,4%** | |
| ataki bezpośrednie vs ZWYKŁE negatywy (422) | AUROC | 0,962 | **0,991** | +0,029 [+0,021, +0,038] |
| | recall @ FPR 1% | 74,8% | **92,4%** | +20,4 pp [+11, +29] |
| P2 BIPIA (kontekst zaatakowany vs czysty) | AUROC | 0,456 (poziom losowy) | 0,760 | |
| P2 PIArena | AUROC | 0,753 | **0,983** | |
| Latencja detektora, krótkie teksty (p50 / p95, Mac) | ms | 46 / 61 | **16 / 23** | |

Recall per zbiór przy progu dającym FPR 1% na puli trudnych negatywów: deepset 8% → 57%, Simsonsun 9% → 49%, Lakera 90% → 95%, nasze ataki 50% → 96%,
PIArena 0,3% → 86%, BIPIA 0,6% → 22%. FPR na zbiorach negatywów: porównywalny (NotInject 2,4% w obu).

Zastrzeżenia: BIPIA i PIArena zbudowali pozytywy/negatywy autorzy Horizona (z cudzych danych), a Horizon trenował na podobnych, własnych dokumentach z wstrzyknięciami,
więc przewaga w P2 może być zawyżona. Próg FPR 1% dobrano na tych samych negatywach, na których liczony jest recall (obciąża oba modele jednakowo).
`protectai` nie jest przystosowany do P2 (karta: nie wykrywa jailbreaków, odradza system prompty), co tłumaczy jego wynik na BIPIA.

## Zespół Horizon small + base (2026-10-04)

Detektor `injection_classifier_ensemble` (`kind: hf_ensemble`) liczy średnią marginesów logitów obu modeli, a wynik kalibruje jedną funkcją Platta
(`config/calibration/injection_classifier_ensemble.json`, dopasowanie na deepset-train + NotInject, ocena na deepset-test i naszych przypadkach).
Pomiary i zastrzeżenia: `docs/decisions.md` (wpis z 2026-10-04). Próg blokady w Javie: 0,8.

