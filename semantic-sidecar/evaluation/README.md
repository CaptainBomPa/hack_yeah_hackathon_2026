# Ewaluacja

Zbiór przypadków i runner do mierzenia detektorów. Wynik to tabela liczb, na podstawie której wybieramy modele
(zob. `../docs/decisions.md`). Przypadki są **danymi** (YAML/JSONL), nie kodem.

## Format przypadku

| Pole | Znaczenie |
|---|---|
| `id` | unikalny identyfikator |
| `checkpoint` | `P1` prompt, `P2` dane niezaufane, `P3` wywołanie narzędzia, `P4` odpowiedź, `P5` pamięć |
| `text` | tekst wejściowy |
| `kind` | `attack`, `benign` albo `hard_negative` |
| `family` | rodzina ataku (np. `override`, `obfuscation`) lub rodzaj trudnego negatywu |
| `context` | opcjonalnie `task`, `source_trust`, `session_id` |
| `source` | `own` albo nazwa zbioru publicznego |

**`hard_negative`** to tekst niewinny, który przypomina atak: tekst *o* injection, kod ze słowem „ignore",
dokumentacja z rozkazami. Bez nich odsetek fałszywych alarmów jest nierealnie niski.

## Co jest w zbiorze

Ręcznie napisane, angielskie, w `cases/` (122 przypadki: 58 ataków, 30 niewinnych, 34 trudne negatywy):

| Plik | Punkt | Zawartość |
|---|---|---|
| `p1_attacks.yaml`, `p1_benign.yaml` | P1 | nadpisanie instrukcji, role-play, wyciąganie promptu, obfuskacja (base64, rot13, leetspeak, homoglify, znaki zerowej szerokości), many-shot, wstrzykiwanie ograniczników |
| `p2_untrusted_data.yaml` | P2 | pośrednia injection w dokumentach i wynikach narzędzi, zatrucie opisu narzędzia MCP, wiadomość „od innego agenta" |
| `p3_actions.yaml` | P3 | nadużycie narzędzi, SSRF, wykonanie kodu, akcje nieodwracalne, zmiana uprawnień (zadanie w `context.task`) |
| `p4_outputs.yaml` | P4 | wyciek promptu (także parafraza), eksfiltracja przez markdown, przejęcie roli, sekrety |
| `p5_memory.yaml` | P5 | zatrucie pamięci |
| `p1_obfuscation.yaml` | P1, P2 | znaki tagów, hex, URL, tekst odwrócony, podwójne base64, pełnoszerokie znaki, encje HTML, ukryty HTML, oraz trudne negatywy (rosyjski, grecki, emoji, hashe, UUID, kod) |

**To zbiór startowy.** Przy tej liczbie przypadków przedziały ufności są szerokie (runner je podaje), więc wnioski
z niego są wstępne. Brakuje m.in. scenariuszy wielotur (wymagają sesji) i danych w innych językach niż angielski.

## Zbiory publiczne

`python -m evaluation.fetch_public` pobiera je do `data/` (katalog w `.gitignore`). Pliki są w tym samym formacie, więc
runner wczytuje je automatycznie. `--max-per-source N` ogranicza ich udział, żeby nie zdominowały ręcznych przypadków.

| Zbiór | Status | Uwagi |
|---|---|---|
| `neuralchemy/Prompt-injection-dataset` (Apache-2.0) | **pobrany** (1883 przypadki: validation+test z `core`, ok. 0,6 MB) | patrz niżej |
| `deepset`, `Lakera/gandalf`, `in-the-wild`, `jackhhao` | skrypt gotowy, **nie pobrane** | czekają na decyzję |
| `microsoft/llmail-inject-challenge` | **odrzucony** | zbyt duży |

### Uwaga o `neuralchemy`: zbiór premiuje wykrywanie szumu

Zmierzone na pobranych danych: **29% ataków ma szum** (homoglify, leet, znaki nieASCII, losowa interpunkcja typu
` _ `), a **tylko 1% niewinnych promptów**. Szum jest więc silnym, fałszywym predyktorem „to atak". Autor opisuje
zbiór jako „high quality, leakage-free", ale tej obserwacji tam nie ma. Konsekwencje:

- Wynik detektora na tym zbiorze trzeba **rozbijać na ataki zaszumione i czyste**. Wynik zbiorczy myli.
- Dla detektora `obfuscation` (v0.1): recall **55%** na zaszumionych atakach (177/320), **1,8%** na czystych (14/766),
  fałszywe alarmy 0,4% (3/797). Detektor łapie szum, a nie ataki, co zgadza się z jego przeznaczeniem.
- Klasyfikator douczany na tym zbiorze nauczy się szumu. Do treningu trzeba szum wyrównać (zaszumić też niewinne
  prompty albo oczyścić ataki).
- W held-out splitach (validation+test) jest tylko 1 przypadek `indirect_injection` i 10 `rag_poisoning`, więc zbiór
  **nie pokrywa P2**, mimo opisu w README zbioru. `rag_poisoning` i `indirect_injection` trafiają do P2 (11 przypadków).
- `edge_case` (20 przypadków) to trudne negatywy. Reszta niewinnych (777) to czyste, zwykłe prompty.

Nie czytałem całego zbioru, tylko próbki z 8 kategorii i statystyki. Ostatecznej oceny jakości etykiet nie mam.

## Runner

```bash
python -m evaluation.run --inprocess                    # bez serwera
python -m evaluation.run --url http://localhost:8001    # przeciw żywemu sidecarowi
python -m evaluation.run --checkpoint P1 --threshold 0.5 --target-fpr 0.01 --json report.json
```

Raport per detektor (oraz `max(all)`, gdy detektorów jest więcej niż jeden):

- recall i FPR z przedziałem ufności Wilsona,
- FPR osobno na trudnych negatywach,
- precyzja i AUROC (niezależny od progu),
- **próg przy zadanym FPR** i recall przy nim (tak dobieramy próg: z docelowego odsetka fałszywych alarmów, nie odwrotnie),
- recall per rodzina ataku,
- identyfikatory chybionych ataków i fałszywych alarmów (do debugowania),
- latencja detektora p50/p95 oraz czas end-to-end.

Awaria detektora liczona jest jako brak sygnału (0) i raportowana osobno (`awarie`). Błąd transportu nie przerywa
przebiegu.

## Czego jeszcze nie ma

Podział na trening/walidację/test i test leave-one-family-out (potrzebne dopiero przy douczaniu modelu),
mutacje ataków, scenariusze wielotur, ablacje (system z detektorem i bez).

## Duża pula testów (generator)

`python -m scripts.build_test_pool` buduje ok. 3,5 tys. przypadków (ziarno 1337, powtarzalnie) do `evaluation/data/pool/` (poza gitem).
Wymiary: rozmiar (300-20000 znaków), pozycja ataku w tekście, format (proza, kod, JSON, logi, markdown, CSV i 14 osadzeń danych), rodzina ataku,
wariant zapisu, obfuskacja (11 rodzajów), dokumenty z wstrzyknięciem pośrednim (P2), pamięć (P5), many-shot, przypadki brzegowe Unicode.
Ziarna: `evaluation/pool_seeds.py`. Uruchomienie: `python -m evaluation.run --inprocess --config config/semantic.models.yaml --cases evaluation/data/pool --threshold 0.9`;
wyniki rozbija się per tag (`size:`, `pos:`, `fmt:`, `obf:`, `var:`, `doc:`, `set:`).

Zastrzeżenia: przypadki są szablonowe, a etykiety wynikają z konstrukcji. Pula mierzy odporność na rozmiar, pozycję, format i obfuskację oraz regresje,
nie uogólnianie na nowe ataki. Część „trudnych negatywów w dokumentach" jest z natury niejednoznaczna (instrukcja dla człowieka w mailu wygląda jak
instrukcja dla AI), więc FPR na nich traktuj jako górne oszacowanie. Testy kontraktu bez modelu: `tests/test_api_edge.py`; na prawdziwym modelu: `tests/test_real_model_edge.py`.

### Zestaw `hard` (trudniejsze przypadki, w `evaluation/data/pool/hard.jsonl`)

Ataki bez słów kluczowych (parafrazy nadpisania reguł, fałszywa władza i „tryby", persona zmieniająca konfigurację, zadanie-przynęta, wieloetapowe,
eksfiltracja, fałszywe tokeny i formaty, ciche zmiany zachowania), transkrypty wieloturowe (3/8/15 tur, atak w pierwszej, środkowej i ostatniej wiadomości),
zagnieżdżone kodowania (podwójne base64, base64 w JSON i komentarzu HTML, odwrócone + base64, przemyt znakami Unicode Tags, rot13 w URL, leet + rozstrzelone litery)
oraz trudne negatywy zgodne z definicją ataku (próba ZMIANY reguł lub zachowania systemu): legalna fikcja i role, „jako badacz napisz książkę", preferencje użytkownika
(„from now on use metric units"), edycja własnego tekstu, pisanie własnego system promptu, rozmowy o bezpieczeństwie AI, zakodowane niewinne treści.
Zestaw NIE był używany do strojenia progów ani modeli. Ziarna: `HARD_ATTACKS`, `HARD_NEGATIVES2` w `evaluation/pool_seeds.py`; testy generatora: `tests/test_pool_builder.py`.

