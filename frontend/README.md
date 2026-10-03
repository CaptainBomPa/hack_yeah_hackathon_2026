# Frontend — AI Control Layer

React 18 + TypeScript + Vite 6 + Tailwind 3 + Recharts + React Router 6. Działa na Node ≥ 18.

## Uruchomienie

```bash
npm install
cp .env.example .env    # VITE_USE_MOCKS=true → praca bez gatewaya
npm run dev             # http://localhost:3000
```

Z `VITE_USE_MOCKS=false` dev-server proxuje `/v1` i `/api` na `GATEWAY_URL` (domyślnie `http://localhost:8000`).
W Dockerze to samo robi nginx (`nginx.conf` → serwis `backend:8000`).

## Widoki

| Ścieżka | Co robi |
|---|---|
| `/playground` | Czat przez gateway (`/v1/chat/completions`) + trace kontroli dla ostatniego żądania |
| `/dashboard` | Kafelki (blokady, redakcje, budżet, p50/p95) + wykresy |
| `/audit` | Audit log + eksport CSV/JSON |
| `/sessions` | Session graph (placeholder) |
| `/policies` | Edycja polityki YAML z hot-reloadem |

## Struktura

- `src/api/types.ts` — **kontrakt z gatewayem** (szkic, do uzgodnienia z zespołem Java)
- `src/api/client.ts` — wywołania HTTP; przełącza się na `mocks.ts` przy `VITE_USE_MOCKS=true`
- `src/pages/` — jeden plik na widok, `src/components/` — wspólne komponenty
