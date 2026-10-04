# semantic-sidecar

Sidecar semantyczny AI Control Layer. Dostaje tekst od gatewaya (Java) po HTTP, uruchamia detektory i zwraca
**sygnały ryzyka** (score, etykieta, dowód, status, latencja). **Nie podejmuje decyzji** allow/redact/block, bo robi to
agregator w gatewayu według polityki. Niski wynik oznacza „nic nie wykryto", nie „bezpiecznie".

Pełne uzasadnienie i analiza: [`../docs/ai-control-layer/semantic-validator-analysis.md`](../docs/ai-control-layer/semantic-validator-analysis.md).
Kontekst projektu: [`../VISION.md`](../VISION.md).

## Status

**Gotowe: szkielet (0), normalizacja tekstu (1), zbiór ewaluacyjny i runner (2), utwardzenie sidecara (A).** Jest kontrakt, interfejs detektora,
endpointy, normalizacja, jeden detektor (`obfuscation`, oparty na regułach), ręczny zbiór 122 przypadków i runner z
metrykami. **Klasyfikatory semantyczne (modele AI) jeszcze nie istnieją**, więc w konfiguracji domyślnej sidecar nie ma żadnego
detektora.
Technologie dla poszczególnych elementów wybieramy po kolei, zob. [`docs/decisions.md`](docs/decisions.md).

## Docker

Usługi `semantic-sidecar-init` i `semantic-sidecar` są w `../docker-compose.yml` (port **8001**, sieć wspólna z backendem, adres `http://semantic-sidecar:8001`).

```bash
docker compose up -d --build semantic-sidecar     # z korzenia repo; init pobiera modele do wolumenu sidecar_models
curl -s localhost:8001/health
```

- **Pierwszy start wymaga internetu** (Hugging Face, ok. 600 MB: model Horizon small). Kolejne starty działają offline: `semantic-sidecar-init` pomija modele już obecne w wolumenie (`--if-missing`, sprawdza rozmiary z `models/MANIFEST.json`).
- **Praca w pełni offline od pierwszego razu:** `docker build --build-arg BAKE_MODELS=1 -t semantic-sidecar semantic-sidecar/` (modele trafiają do obrazu).
- Obraz używa **CPU-owego PyTorcha** (`download.pytorch.org/whl/cpu`, działa na arm64 i x86_64) i przypiętych wersji z `constraints.txt`. Domyślne koło z PyPI na x86 ciągnie biblioteki CUDA (ok. 2,5 GB).
- `config/` jest montowane z hosta tylko do odczytu (progi, kalibracja). **Zmiana wymaga restartu** (hot-reload jeszcze nie istnieje).
- Klucz HMAC dowodów: zmienna `SEMANTIC_EVIDENCE_KEY` (domyślnie `change-me-evidence-key`), liczba wątków PyTorcha: `SIDECAR_THREADS`.
- **Obraz zbudowany i sprawdzony** w Docker Desktop (linux/arm64, Mac), **nie na Raspberry Pi**. Rozmiar obrazu: 1,15 GB (bez modeli). Zweryfikowane: `up` (init pobiera 1013 MB, sidecar zdrowy), start i odpowiedzi **bez sieci** (`--network none`), działanie jako użytkownik `app`, wyniki zgodne z uruchomieniem natywnym (margines `protectai` 15,464493 vs 15,464491, `float32` w obu).
- **Pamięć:** proces zajmuje ok. **0,88 GiB RSS** (441 MiB sterty + 442 MiB wag zmapowanych z pliku; jeden model). Uwaga: `docker stats` pokazuje mniej, bo strony pliku z wagami są księgowane na kontener init, który je zapisał. Do planowania pamięci (Raspberry Pi) używaj RSS, nie `docker stats`.

## Uruchomienie bez Dockera

```bash
cd semantic-sidecar
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
python -m pytest -q
uvicorn app.main:app --port 8001
```

Port 8001 zgodnie z `VISION.md` §7 (gateway na 8000).

## Kontrakt wejścia: tekst jest już znormalizowany

Ustalenie z zespołem: **sidecar dostaje tekst znormalizowany przez gateway** i go nie rusza (`input.pre_normalized: true`,
domyślnie). Normalizacja to kod deterministyczny, więc należy do warstwy Java. Szczegóły, mapowanie na `ControlResult`
i otwarte pytania: [`docs/input-contract.md`](docs/input-contract.md).

| Tryb | `input.pre_normalized` | Co robi sidecar |
|---|---|---|
| **Docelowy** (domyślny) | `true` | niczego nie normalizuje. Detektor `obfuscation` jest niedostępny (sidecar odmówi startu, jeśli go włączysz) |
| Samodzielny (demo, ewaluacja bez gatewaya) | `false` | normalizuje sam referencyjną implementacją z `app/normalize/` |

`app/normalize/` zostaje jako **referencyjna implementacja i specyfikacja do przeniesienia do Javy**, razem z testami
i przypadkami `evaluation/cases/p1_obfuscation.yaml`.

## Ewaluacja

Zbiór i runner służą do wyboru detektorów na podstawie liczb. Szczegóły: [`evaluation/README.md`](evaluation/README.md).

```bash
python -m evaluation.run --inprocess                 # na szkielecie, bez serwera
python -m evaluation.run --url http://localhost:8001 --json report.json
```

## API

| Endpoint | Opis |
|---|---|
| `GET /health` | status i lista włączonych detektorów |
| `POST /classify` | `{checkpoint: P1..P5, text, context}` -> `{checkpoint, results, complete, missing_checks, normalization}` |

`checkpoint` to punkt kontroli: P1 prompt, P2 dane niezaufane, P3 wywołanie narzędzia, P4 odpowiedź modelu, P5 pamięć.
Jeden endpoint z punktem kontroli w treści (prościej niż pięć endpointów).

### Kontrakt odpowiedzi: brak wyniku to nie wynik 0

- `results[].status`: `ok`, `error`, `timeout`, `skipped`. Gdy status != `ok`, `score` jest `null`, a `reason` podaje powód
  (`normalization_failed`, `normalization_timeout`, `timeout`, `deadline_exceeded`, `exception`).
- `missing_checks` i `complete`: które kontrole miały się wykonać, a nie dały wyniku. **Gateway musi to obsłużyć**
  (domyślnie fail-closed). Sidecar nigdy nie decyduje za gateway.
- `evidence` nie zawiera treści: `variant`, `span`, `length`, `digest` (HMAC-SHA256, skrócony) i opcjonalnie
  zamaskowany `preview` (domyślnie wyłączony). Powód: fragment może być sekretem odkodowanym z base64. Klucz HMAC ustaw
  w zmiennej środowiskowej `SEMANTIC_EVIDENCE_KEY`, inaczej skróty są losowe i zmieniają się po restarcie.
- Detektor zwraca `RawEvidence` (z tekstem), a runner zamienia go na `Evidence`. Surowy tekst nie opuszcza procesu.
- Treść wyjątku detektora nie trafia do odpowiedzi ani do logów.

Przykład:

```bash
curl -s localhost:8001/classify -H 'content-type: application/json' \
  -d '{"checkpoint":"P1","text":"hello"}'
```

## Struktura

```
semantic-sidecar/
  app/
    contract.py        kontrakt wejścia i wyjścia (pydantic)
    normalize/         referencyjna normalizacja (tylko stdlib), używana w trybie samodzielnym; specyfikacja dla Javy
    detectors/base.py  interfejs Detector, nowy model = nowa implementacja
    detectors/obfuscation.py  detektor oparty na sygnałach normalizacji
    registry.py        fabryki detektorów (nazwa -> budowa z params w konfiguracji)
    runner.py          uruchamia detektory z deadline'ami, brak wyniku = status != ok i `missing_checks`
    evidence.py        zamiana surowego dowodu na bezpieczny (długość, zakres, HMAC)
    config.py          wczytanie config/semantic.yaml
    main.py            FastAPI
  config/semantic.yaml konfiguracja jako dane (limity, włączone detektory)
  evaluation/          zbiór przypadków (dane), metryki i runner ewaluacji
  tests/               testy potoku i ewaluacji na detektorach atrapowych
  docs/decisions.md    dziennik decyzji technologicznych
  docs/input-contract.md  kontrakt wejścia: co robi gateway, co zwraca sidecar, pytania do zespołu
```

## Zasady

- Konfiguracja to dane (YAML), nie kod. Nieznany detektor w konfiguracji przerywa start.
- Błąd detektora to status `error`, nigdy wynik 0.
- Detektor zostaje w systemie tylko wtedy, gdy ablacja pokaże, że poprawia wynik.
- Wszystko offline, bez płatnych usług (`CRITERIA`, rozdz. 7).

## Czego jeszcze nie ma (kolejne kroki)

Filtr w gatewayu (krok B), twardy deadline po stronie gatewaya, hot-reload konfiguracji, detektory dla P3/P4 i sesji, **zbudowanie i sprawdzenie
obrazu Dockera** (pliki są gotowe, zob. sekcja Docker, ale obraz nie był jeszcze budowany), telemetria, integracja z gatewayem.

## Logi

Strukturalne (JSON, jedna linia na zdarzenie) na stdout. `SEMANTIC_LOG_LEVEL` (domyślnie `INFO`), `SEMANTIC_LOG_FORMAT` (`json` albo `text`).
Każde `/classify` daje jedną linię `classified`: `checkpoint`, `text_chars`, `text_digest` (HMAC, do korelacji powtórzeń), wyniki i czasy detektorów,
`covered`, `complete`, `missing`, `total_ms`. Poza tym: `sidecar_started` (konfiguracja, wersje, rozgrzewka), `check_missing`, `input_too_large`,
`http_error`, `invalid_request`, `slow_request`, `unhandled_error`. Identyfikator żądania pochodzi z nagłówka `X-Request-ID` (po walidacji) albo jest
generowany, wraca w nagłówku odpowiedzi i jest w każdym logu. **Treść promptu i tekst wyjątków nigdy nie trafiają do logów** (test: `tests/test_logging.py`).
Klucz skrótu: `SEMANTIC_EVIDENCE_KEY` (bez niego skróty zmieniają się po restarcie).
