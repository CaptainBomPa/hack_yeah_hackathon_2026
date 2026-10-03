# Dziennik decyzji technologicznych

Każdy element dostaje wpis. Status: **WYBRANE**, **DO WYBORU** (kandydaci i plan pomiaru) albo **ODRZUCONE**.
Wybór detektora i modelu zapada na podstawie pomiaru na naszym zbiorze i sprzęcie, nie przypuszczeń.
Licencje i wersje trzeba sprawdzić przed wyborem (`CRITERIA`, rozdz. 5).

## Zatwierdzone

| Element | Wybór | Uzasadnienie |
|---|---|---|
| Język i serwer | Python 3.11+, FastAPI, uvicorn | zgodne z `VISION.md` §3, bogaty ekosystem NLP |
| Kontrakty i konfiguracja | pydantic v2 + YAML | schematy wejścia/wyjścia i walidacja konfiguracji |
| Komunikacja z gatewayem | HTTP/JSON na localhoście | najprostsze, narzut do zmierzenia, gRPC dopiero gdy będzie potrzeba |
| Testy potoku | pytest + httpx | testy na detektorach atrapowych |
| Normalizacja tekstu | **tylko biblioteka standardowa** (`unicodedata`, `base64`, `codecs`, `html`, `re`) + własna tabela homoglifów | zero zależności, pełna kontrola nad limitami i zachowaniem. Odrzucone: `ftfy` (naprawia mojibake, czego nie potrzebujemy), `confusable-homoglyphs` (nie daje wprost zamiany na ASCII, a tabela dla cyrylicy i greki jest mała) |

## Zrobione: normalizacja (krok 1)

Stan: gotowe (v0). Moduł `app/normalize/`, wejście do każdego detektora. Daje wersje tekstu (`original`, `normalized`,
`deobfuscated`, odkodowane segmenty) oraz sygnały (liczniki). Pierwszy prawdziwy detektor: `obfuscation` (reguły
oparte na sygnałach normalizacji, wagi w konfiguracji).

Co robi: NFKC, usunięcie znaków niewidocznych, **dekodowanie ukrytego tekstu w znakach tagów**, homoglify (tylko w słowach
mieszających łacinę z cyrylicą/grecką), encje HTML, base64 (też url-safe i bez paddingu), hex, URL (tylko gdy zakodowano
głównie bajty ASCII), rot13 i tekst odwrócony (po słowniku częstych angielskich słów), leetspeak, litery rozstrzelone,
ukryty tekst z HTML (komentarze, atrybuty). Limity: głębokość dekodowania, liczba i rozmiar segmentów (przekroczenie
to osobny sygnał). Normalizacja nie rzuca wyjątków.

**Wynik pomiaru (zbiór ręczny, 122 przypadki, to wstępne liczby):** detektor `obfuscation` łapie 15 z 17 ataków z rodziny
obfuskacji (88%, 95% CI ok. 66-97%), 0% fałszywych alarmów na 34 trudnych negatywach. Dwa „chybienia" to przypadki, które
normalizacja rozwiązuje, a detektor obfuskacji nie powinien flagować: pełnoszerokie znaki i tekst w `alt` (trafiają do
wariantów skanowanych przez inne detektory). Poza obfuskacją detektor niczego nie łapie, co jest zamierzone.
Miara skuteczności samej normalizacji: test sprawdza, że ładunek jest czytelny w którymś wariancie dla każdego
przypadku obfuskacji.

**Ograniczenia:** tabela homoglifów obejmuje cyrylicę i grekę, nie cały standard confusables. Rot13 i odwrócony tekst
zależą od małego słownika. Przy zdaniu napisanym wyłącznie lookalike'ami cyrylicy (bez żadnej litery łacińskiej)
homoglify nie są zamieniane (celowo, żeby nie niszczyć zwykłego rosyjskiego tekstu). Dobór wag detektora na 22 nowych
przypadkach z mojej strony to ryzyko przeuczenia na własnym zbiorze, trzeba je zweryfikować na zbiorach publicznych.

## Do wyboru (kolejność robocza)

| # | Element | Kandydaci | Jak wybieramy |
|---|---|---|---|
| 2 | Zbiór ewaluacyjny i runner | **GOTOWE (v0):** ręczne 100 przypadków (P1-P5, trudne negatywy), runner z metrykami. **Do zrobienia:** pobranie zbiorów publicznych, własne mutacje | od tego zależy wybór modeli |
| 3 | Klasyfikator injection (encoder) | gotowe encodery z Hugging Face, własne douczenie na ModernBERT/DeBERTa | recall i odsetek fałszywych alarmów, latencja na naszym sprzęcie |
| 4 | Embeddingi i indeks kNN | `sentence-transformers` + FAISS lub hnswlib | jakość na korpusie ataków i niewinnym, czas zapytania |
| 5 | Bramka języka | `lingua`, fastText lid | dokładność na krótkich promptach |
| 6 | Reguły leksykalne/strukturalne | własny zestaw (dane w YAML) | pokrycie znaczników ról i tokenów specjalnych |
| 7 | Wyjście: canary, odciski, linki | stdlib, MinHash/SimHash, embedding z p.4 | testy wycieku bezpośredniego i parafrazy |
| 8 | PII/NER na wyjściu | Presidio + spaCy, GLiNER | pokrycie i koszt pamięci |
| 9 | Eskalacja (LLM na logitach, guard) | llama-cpp-python, Ollama, transformers | czy poprawia wynik w ablacji i jaka latencja |
| 10 | Kontekst sesji, sondowanie, zapętlenie | pamięć procesu z TTL, embedding z p.4, Redis jeśli trzeba | testy wielotur i pętli |
| 11 | Inferencja CPU | ONNX Runtime + optimum | czy skraca czas i rozmiar bez utraty jakości |
| 12 | Telemetria | prometheus-client, OpenTelemetry | wymóg `CRITERIA` (rozdz. 6) |
| 13 | Hot-reload konfiguracji i feedu | watchfiles | test zmiany na żywo bez restartu |
| 14 | Red-teaming | garak (licencja do sprawdzenia), własne mutacje | raport odporności |
| 15 | Kontener | python-slim, wagi poza obrazem | start bez sieci i bez ręcznych kroków |

## Otwarte decyzje projektowe

Zob. `docs/ai-control-layer/semantic-validator-analysis.md` §10: polityka dla innych języków, fail-open vs fail-closed
dla P1, strumieniowanie odpowiedzi, dostępny sprzęt (GPU), ile ujawniać wywołującemu, źródło danych syntetycznych.

## Dziennik

- 2026-10-03: krok 1: normalizacja (`app/normalize/`) i detektor `obfuscation`, tylko stdlib. 51 testów. Zbiór ewaluacyjny rozszerzony do 122 przypadków.
- 2026-10-03: krok 2 (v0): `evaluation/` z 100 ręcznymi przypadkami, metrykami (recall, FPR, trudne negatywy, AUROC, przedziały Wilsona, próg przy zadanym FPR, per rodzina, latencja) i runnerem. Publiczne zbiory: skrypt gotowy, **nie uruchomiony** (czeka na zgodę na pobranie).
- 2026-10-03: szkielet (krok 0), jeden endpoint `/classify` z punktem kontroli w treści zamiast pięciu endpointów.
