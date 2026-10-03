# PII-RECOGNIZERS — silnik PII z konceptami Presidio

Guard `PII-RECOGNIZERS` (`backend/.../guard/pii/`) realizuje rdzeń case file
[01-pii-detection.md](01-pii-detection.md) jako jeden silnik i wiele reguł-danych. Presidio nie jest
uruchamiane w runtime: przejmujemy jego format, semantykę scoringu i część wzorców, a całość
działa w Javie, bez NLP i bez dodatkowego hopu sieciowego.

## Co skąd

| Presidio | U nas |
|---|---|
| `PatternRecognizer` (YAML: `name`, `supported_entity`, `patterns[name, regex, score]`, `context`, `deny_list`) | `rules/pii/recognizers.yaml`, ten sam format + rozszerzenia (`id`, `validator`, `require_context`, `allow_list`, `action`, `operator`) |
| `validate_result`: True → 1.0, False → odrzuć, None → score wzorca | `Validators` (pesel, nip, regon, pl_id_card, luhn, iban, email) |
| `LemmaContextAwareEnhancer` (+0.35, min 0.4, okno słów) | `ContextMatcher`: prefiksy bez diakrytyków zamiast lematów |
| `score_threshold`, `allow_list` | `threshold` w `application.yml`, `allow_list` per recognizer |
| usuwanie nakładających się wyników | wyższy score wygrywa, przy remisie dłuższy zakres |
| anonymizer `replace` / `mask` / `redact` | `Anonymizer`, te same nazwy parametrów |
| `return_decision_process` | `detail` w `trace`, np. `PII-001/PL_PESEL×1 [pattern PESEL 0.40 → pesel:valid 1.00]` |

Wzorce PESEL, karty, e-mail i IBAN pochodzą z predefiniowanych recognizerów Presidio (MIT,
`rules/pii/PRESIDIO-LICENSE`). NIP, REGON, dowód, NRB i telefon PL są nasze (Presidio ich nie ma).

## Jak dodać typ PII

1. Dopisz recognizer do `recognizers.yaml` (wzorzec, score, kontekst). Jeśli potrzebny jest
   checksum, dodaj algorytm do `Validators` — to jedyna część w kodzie.
2. Dodaj wektory pozytywne i negatywne do `PiiRecognizerGuardTest`.

Recognizer z Presidio przenosi się kopiując `PATTERNS` i `CONTEXT` z jego klasy Pythona; regex
trzeba sprawdzić pod `java.util.regex` (np. `\w` jest ASCII, brak `(?P<...>)`).

## Konfiguracja (`control-layer.guards.rules.PII-RECOGNIZERS.params`)

| Parametr | Domyślnie | Znaczenie |
|---|---|---|
| `pack` | `classpath:rules/pii/recognizers.yaml` | paczka; `file:/...` pozwala podmienić ją bez przebudowy (wymaga restartu) |
| `threshold` | 0.5 | minimalny score trafienia |
| `contextPrefixWords` / `contextSuffixWords` | 5 / 3 | okno słów kontekstowych (Presidio: 5 / 0) |
| `disabledRecognizers` | — | id wyłączonych recognizerów |
| `blockRecognizers` / `monitorRecognizers` | — | nadpisanie `action` z paczki |

## Ograniczenia (świadome, na teraz)

- Brak kanonikalizacji (CANON, case 21): zero-width, fullwidth i base64 omijają wzorce.
- Telefon bez libphonenumber: goły 9-cyfrowy numer tylko ze słowem kontekstowym.
- Walidator VALID daje 1.0 bez kontekstu (jak w Presidio): ok. 1/10 losowych 10-cyfrowych ciągów
  przejdzie jako NIP, a 16 cyfr wewnątrz niepoprawnego IBAN może przejść Luhn jako karta.
- Imiona, adresy i dane medyczne w prozie: poza zakresem deterministycznym (case 01 §8).
- Paczka ładowana przy starcie; hot reload dojdzie razem z `policy.yaml`.
- Wartości w `detail` nie są hashowane (HMAC — case 27); `detail` nie zawiera surowych wartości.
