# Dziennik decyzji technologicznych

Każdy element dostaje wpis. Status: **WYBRANE**, **DO WYBORU** (kandydaci i plan pomiaru) albo **ODRZUCONE**.
Wybór detektora i modelu zapada na podstawie pomiaru na naszym zbiorze i sprzęcie, nie przypuszczeń.
Licencje i wersje trzeba sprawdzić przed wyborem (`CRITERIA`, rozdz. 5).

## Zatwierdzone

| Element | Wybór | Uzasadnienie |
|---|---|---|
| Język i serwer | Python 3.11+, FastAPI, uvicorn | zgodne z `VISION.md` §3, bogaty ekosystem NLP |
| Kontrakty i konfiguracja | pydantic v2 + YAML | schematy wejścia/wyjścia i walidacja konfiguracji |
| Komunikacja z gatewayem | HTTP/JSON na localhoście, port 8001 | najprostsze, narzut do zmierzenia, gRPC dopiero gdy będzie potrzeba. Sidecar to opcjonalny adapter providera semantycznego (`VISION.md` §2) |
| Testy potoku | pytest + httpx | testy na detektorach atrapowych |
| Normalizacja tekstu (referencyjna, docelowo w Javie) | **tylko biblioteka standardowa** (`unicodedata`, `base64`, `codecs`, `html`, `re`) + własna tabela homoglifów | zero zależności, pełna kontrola nad limitami i zachowaniem. Odrzucone: `ftfy` (naprawia mojibake, czego nie potrzebujemy), `confusable-homoglyphs` (nie daje wprost zamiany na ASCII, a tabela dla cyrylicy i greki jest mała) |

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

- 2026-10-03: **Integracja z gatewayem (guard SEM-001), na prośbę Anny.** Backend miał już `GuardChain`, więc sidecar wpięto jako zwykły `Guard` (`backend/.../guard/semantic/`), a nie osobny mechanizm. Dwie wstecznie zgodne zmiany wspólnych klas: `Guard.kind()` (domyślnie "deterministic", SEM-001 zwraca "semantic") i `Verdict.Allow(detail)` (wynik semantyczny widoczny w trace także przy przepuszczeniu). Decyzję podejmuje Java z progu (`blockThreshold` 0,998, `failureMode: closed`, `timeoutMs` 4000), sidecar daje sygnały. Pusta lista wyników = brak kontroli (fail-closed), co zamyka pułapkę `results: []` + `complete: true`. Guard domyślnie wyłączony w `application.yml` (`SEMANTIC_GUARD_ENABLED`), włączony w compose. Testy Javy w Dockerze (JDK 25): 51 przechodzi, 20 nowych. Mutacje: 4 z 4 wykryte po dołożeniu testu (jedna przeżyła: `complete=false` przy wyniku poniżej progu). Na prawdziwym stacku: atak → 403 SEM-001 (0,9988), licznik wywołań Ollamy bez zmian; sidecar zatrzymany → fail-closed 403 po 4 s (cały timeout); prompt niewinny → 200 z pełnym trace. Znaleziska dla zespołu: `SeedUsers` zakłada konta z `users.yaml` także w prod mimo komentarza; przy martwym sidecarze każde żądanie czeka cały timeout (4 s).
- 2026-10-03: **Usunięto `Jailbreak-Detector`** (decyzja zespołu po pomiarach: recall 2,1% przy FPR ≤ 1% na `neuralchemy`, nie łapał wyciągania promptu ani zakodowanych ataków). Skasowano detektor, model (252 MB), kalibrację, konfigurację, usługę w compose i wpis w Dockerfile, plik z wolumenu Dockera. Pin rewizji w komentarzu `config/models.yaml`. Pamięć z jednym modelem: RSS ok. 883 MiB (było 1,07 GiB), `healthy` po ok. 13 s. Zostaje jeden klasyfikator injection (`protectai`). Zauważona pułapka kontraktu: punkt kontroli bez detektora zwraca `results: []` i `complete: true`.
- 2026-10-03: Docker zweryfikowany w Docker Desktop (linux/arm64, Mac): obraz 1,15 GB zbudowany, `compose up` (init 1013 MB, sidecar healthy), start i odpowiedź offline (`--network none`, 5 s), kontener jako użytkownik `app`, marginesy zgodne z natywnymi do 6 cyfr (float32 w obu). Pamięć: RSS 1,07 GiB (RssAnon 459 + RssFile 610 MiB); `docker stats` zaniża do ok. 490 MiB (strony pliku księgowane na kontener init). **Nie sprawdzono na Raspberry Pi.**
- 2026-10-03: **Docker:** `semantic-sidecar/Dockerfile` (multi-stage, python:3.12-slim, CPU-owy torch z indeksu pytorch.org/whl/cpu, przypięte wersje, użytkownik nie-root), usługi `semantic-sidecar-init` (pobiera modele, `--if-missing` = start offline po pierwszym razie) i `semantic-sidecar` (healthcheck `/health`, config z hosta read-only) w `docker-compose.yml`. Backendu nie ruszono (jeszcze nie woła sidecara). **Obraz niezbudowany** (demon wyłączony). Zmierzone lokalnie: start 7 s, RSS ok. 1,06-1,09 GB. 107 testów.
- 2026-10-03: **eksperyment kNN: hipoteza niepotwierdzona** (24 konfiguracje, protokół val/test/own). Zysk na `neuralchemy` wynika z szablonowości (24% zapytań ma niemal duplikat w indeksie), na naszych danych stack ≈ `protectai` (AUROC 0,933 vs 0,928 na niepodobnych), trudne negatywy bez zmian, próg się nie przenosi. Stacker nie wchodzi do produkcji. Najsilniejszy czynnik: dane (trudne negatywy w indeksie), z zastrzeżeniem o wspólnej taksonomii. FAISS niepotrzebny (numpy brute-force wystarcza do dziesiątek tysięcy wektorów).
- 2026-10-03: priorytet 1 backlogu. Znaleziony i naprawiony błąd okien (`transformers 5.18` zwracał tylko 2 okna), testy `hf_classifier` na malutkim modelu (98 testów), rozgrzanie przy starcie, `/health.details`, `label_threshold`, `coverage`, kalibracja jailbreaka, `fmops` usunięty, manifest modeli naprawiony (scalanie zamiast nadpisywania), `fetch_models` bezpieczny przy ponownym uruchomieniu.
- 2026-10-03: kalibracja `protectai`: Platt na marginesie logitów, dopasowanie neuralchemy/train. Poprawia log-loss/Brier/ECE na tym samym rozkładzie (0,72→0,21), nie poprawia na naszych trudnych negatywach. AUROC bez zmian (monotoniczne). Skalibrowany wynik ≠ prawdopodobieństwo ataku w produkcji (prior 60% w danych). Progi z docelowego FPR. Wynik zmienia się z `raw_score` w odpowiedzi.
- 2026-10-03: **wybór: `protectai/deberta-v3-base-prompt-injection-v2` jako klasyfikator injection** (pobrany po pomiarze `fmops`). Zmierzone: AUROC 0,97, FPR 4%, recall 85% na 2005 przypadkach (nasze 122: recall 91%, FPR 19%). `fmops` wyłączony. Słabości: trudne negatywy 26-35% FP, jailbreaki 49%, nasycone prawdopodobieństwa (potrzebna kalibracja), latencja p50 56 ms na Macu.
- 2026-10-03: pobrano 4 modele (735 MB). Generyczny detektor `hf_classifier`. Pomiar: `fmops/distilbert-prompt-injection` FPR 75% na naszych niewinnych (47,5% na całości), nieprzydatny jako bramka bez wymiany lub kalibracji; `madhurjindal/Jailbreak-Detector` FPR 0% ale recall 34%. Do sprawdzenia `protectai` (wymaga zgody na 750 MB).
- 2026-10-03: **ustalenie z zespołem: sidecar dostaje tekst znormalizowany.** `input.pre_normalized: true` (domyślnie): sidecar niczego nie normalizuje. `app/normalize/` zostaje referencyjną implementacją do przeniesienia do Javy i trybem samodzielnym. `obfuscation` (reguły) niedostępny przy pre_normalized (start odmawia). Reguły leksykalne odrzucone w sidecarze (warstwa deterministyczna). Port 8001. Eval-runner udaje normalizator gatewaya. 71 testów.
- 2026-10-03: krok A (utwardzenie): deadline'y (pula wątków + `wait` z timeoutem), `missing_checks`/`complete`, dowody bez treści (HMAC), brak treści wyjątków w odpowiedzi i logach. 65 testów, 4 mutacje wykryte. Ograniczenie: wątku nie da się przerwać, twardy deadline ma egzekwować gateway.
- 2026-10-03: pobrano `neuralchemy/Prompt-injection-dataset` (core, validation+test, 1883 przypadki). Zbiór premiuje szum (29% ataków vs 1% niewinnych zaszumionych): wyniki rozbijać na czyste i zaszumione. `microsoft/llmail-inject-challenge` odrzucony (rozmiar). Pozostałe zbiory nie pobrane.
- 2026-10-03: krok 1: normalizacja (`app/normalize/`) i detektor `obfuscation`, tylko stdlib. 51 testów. Zbiór ewaluacyjny rozszerzony do 122 przypadków.
- 2026-10-03: krok 2 (v0): `evaluation/` z 100 ręcznymi przypadkami, metrykami (recall, FPR, trudne negatywy, AUROC, przedziały Wilsona, próg przy zadanym FPR, per rodzina, latencja) i runnerem. Publiczne zbiory: skrypt gotowy, **nie uruchomiony** (czeka na zgodę na pobranie).
- 2026-10-03: szkielet (krok 0), jeden endpoint `/classify` z punktem kontroli w treści zamiast pięciu endpointów.
- 2026-10-03: **wybór: `Horizon-Labs/prompt-injection-guard-small` zastępuje `protectai`** (uczciwe porównanie `scripts/compare_detectors.py`, zob. `docs/models.md`). Ataki bezpośrednie vs trudne negatywy: AUROC 0,972 vs 0,848, recall przy FPR 1% 69,5% vs 39,5%; vs zwykłe negatywy 0,991 vs 0,962; latencja ok. 3x niższa (16 vs 46 ms p50 na Macu), RAM porównywalny (0,84 vs 0,91 GB). Tylko zbiory czyste dla obu modeli. Kalibracja Platta na deepset (część treningowa) + NotInject (`--fit heldout`), bo Horizon trenował na neuralchemy. Próg blokady w Javie 0,998 -> 0,9 (`application.yml`). PIGuard odrzucony (lepszy tylko na over-defense, gorszy gdzie indziej, wymagał kodu zdalnego); Prompt Guard v1/v2 odrzucone (fałszywe alarmy/gated). `protectai` zostaje w `config/semantic.protectai.yaml` tylko do porównań. Zastrzeżenie: przewaga w P2 (BIPIA, PIArena) może być zawyżona (Horizon trenował na podobnych dokumentach).
