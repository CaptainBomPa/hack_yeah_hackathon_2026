# semantic-sidecar

Sidecar semantyczny AI Control Layer. Dostaje tekst od gatewaya (Java) po HTTP, uruchamia detektory i zwraca
**sygnały ryzyka** (score, etykieta, dowód, status, latencja). **Nie podejmuje decyzji** allow/redact/block, bo robi to
agregator w gatewayu według polityki. Niski wynik oznacza „nic nie wykryto", nie „bezpiecznie".

Pełne uzasadnienie i analiza: [`../docs/ai-control-layer/semantic-validator-analysis.md`](../docs/ai-control-layer/semantic-validator-analysis.md).
Kontekst projektu: [`../VISION.md`](../VISION.md).

## Status

**Gotowe: szkielet (0), normalizacja tekstu (1), zbiór ewaluacyjny i runner (2).** Jest kontrakt, interfejs detektora,
endpointy, normalizacja, jeden detektor (`obfuscation`, oparty na regułach), ręczny zbiór 122 przypadków i runner z
metrykami. Klasyfikatory semantyczne (modele) jeszcze nie istnieją.
Technologie dla poszczególnych elementów wybieramy po kolei, zob. [`docs/decisions.md`](docs/decisions.md).

## Uruchomienie

```bash
cd semantic-sidecar
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
python -m pytest -q
uvicorn app.main:app --port 8100
```

Port 8100 to propozycja robocza (backend używa 8000).

## Ewaluacja

Zbiór i runner służą do wyboru detektorów na podstawie liczb. Szczegóły: [`evaluation/README.md`](evaluation/README.md).

```bash
python -m evaluation.run --inprocess                 # na szkielecie, bez serwera
python -m evaluation.run --url http://localhost:8100 --json report.json
```

## API

| Endpoint | Opis |
|---|---|
| `GET /health` | status i lista włączonych detektorów |
| `POST /classify` | `{checkpoint: P1..P5, text, context}` -> `{checkpoint, results: [...]}` |

`checkpoint` to punkt kontroli: P1 prompt, P2 dane niezaufane, P3 wywołanie narzędzia, P4 odpowiedź modelu, P5 pamięć.
Jeden endpoint z punktem kontroli w treści (prościej niż pięć endpointów).

Przykład:

```bash
curl -s localhost:8100/classify -H 'content-type: application/json' \
  -d '{"checkpoint":"P1","text":"hello"}'
```

## Struktura

```
semantic-sidecar/
  app/
    contract.py        kontrakt wejścia i wyjścia (pydantic)
    normalize/         normalizacja: Unicode, dekodery, deobfuskacja (tylko stdlib)
    detectors/base.py  interfejs Detector, nowy model = nowa implementacja
    detectors/obfuscation.py  detektor oparty na sygnałach normalizacji
    registry.py        fabryki detektorów (nazwa -> budowa z params w konfiguracji)
    runner.py          uruchamia detektory, błąd = status error, a nie score 0
    config.py          wczytanie config/semantic.yaml
    main.py            FastAPI
  config/semantic.yaml konfiguracja jako dane (limity, włączone detektory)
  evaluation/          zbiór przypadków (dane), metryki i runner ewaluacji
  tests/               testy potoku i ewaluacji na detektorach atrapowych
  docs/decisions.md    dziennik decyzji technologicznych
```

## Zasady

- Konfiguracja to dane (YAML), nie kod. Nieznany detektor w konfiguracji przerywa start.
- Błąd detektora to status `error`, nigdy wynik 0.
- Detektor zostaje w systemie tylko wtedy, gdy ablacja pokaże, że poprawia wynik.
- Wszystko offline, bez płatnych usług (`CRITERIA`, rozdz. 7).

## Czego jeszcze nie ma (kolejne kroki)

Równoległość i timeouty detektorów, hot-reload konfiguracji, normalizacja, wszystkie detektory, Dockerfile i wpis w
`docker-compose.yml`, telemetria, integracja z gatewayem.
