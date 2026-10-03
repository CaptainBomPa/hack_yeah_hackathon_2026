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

`python -m evaluation.fetch_public` pobiera je do `data/` (katalog w `.gitignore`). Zbiory i licencje są opisane w
nagłówku skryptu. **Skrypt nie został jeszcze uruchomiony.** Pobrane pliki są w tym samym formacie, więc runner
je wczytuje automatycznie. Do P1 (publiczne zbiory nie mają P2-P5). `--max-per-source N` ogranicza ich udział, żeby
nie zdominowały ręcznych przypadków.

## Runner

```bash
python -m evaluation.run --inprocess                    # bez serwera
python -m evaluation.run --url http://localhost:8100    # przeciw żywemu sidecarowi
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
