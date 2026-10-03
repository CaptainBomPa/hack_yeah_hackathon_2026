# Frontend — AI Control Layer

React 18 + TypeScript + Vite 6 + Tailwind 3 + Recharts + React Router 6. Działa na Node ≥ 18.
Wiążący kontrakt architektury i decyzji znajduje się w [`VISION.md`](../VISION.md).

## Uruchomienie

```bash
npm install
cp .env.example .env    # VITE_USE_MOCKS=true → praca bez gatewaya
npm run dev             # http://localhost:3000
```

Dev-server proxuje `/v1` i `/api` na `GATEWAY_URL` (domyślnie `http://localhost:8000`).
W Dockerze to samo robi nginx (`nginx.conf` → serwis `backend:8000`).

| Zmienna | Znaczenie |
|---|---|
| `VITE_USE_MOCKS=true` | mocki (`src/api/mocks.ts`) dla endpointów, których backend jeszcze nie ma |
| `VITE_LIVE_FEATURES` | opcjonalnie nadpisuje listę funkcji na żywym gatewayu; domyślnie `chat` (`IMPLEMENTED_IN_BACKEND` w `client.ts`). Pusta wartość = wszystko na mockach |
| `VITE_MODELS` | tagi modeli w Playground, dopóki gateway nie wystawi `GET /api/models` |

**Czat zawsze woła żywy backend** (`POST /v1/chat/completions`), więc do Playground potrzebny jest
uruchomiony backend (`backend/README.md`) z dostępną Ollamą. Gdy powstanie kolejny endpoint, dopisz
funkcję do `IMPLEMENTED_IN_BACKEND`. Vite czyta `.env` tylko przy starcie.

## Widoki

| Ścieżka | Co robi |
|---|---|
| `/playground` | Czat przez gateway (`/v1/chat/completions`) + Explainable Verdict (X-ray) wybranej wiadomości |
| `/dashboard` | Kafelki (blokady, redakcje, budżet, p50/p95) + wykresy |
| `/audit` | Audit log + eksport CSV/JSON |
| `/policies` | Edycja polityki YAML z hot-reloadem |

## Struktura

- `src/api/types.ts` — **kontrakt z gatewayem**; typy czatu 1:1 z `backend/.../chat/*.java`, reszta wg `docs/frontend-flows-and-api.md`
- `src/api/client.ts` — wywołania HTTP; mock/live per funkcja (`isMocked()`)
- `src/components/DecisionXray.tsx` — Explainable Verdict, używany też w audycie i Arenie
- `src/pages/` — jeden plik na widok, `src/components/` — wspólne komponenty
