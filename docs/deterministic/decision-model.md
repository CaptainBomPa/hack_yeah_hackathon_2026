# Decision Model

## 1. Decyzje

| Decyzja | Rdzeń? | Znaczenie | HTTP / efekt | Kiedy |
|---|---|---|---|---|
| `ALLOW` | tak | Przepuść bez zmian | 200 / pass | Brak trafień lub wyjątek |
| `REDACT` | tak | Zamaskuj fragment i kontynuuj | 200, treść zmodyfikowana (`[REDACTED:PII-001]`) | PII/sekret/obraz-exfil w treści, której reszta jest bezpieczna |
| `BLOCK` | tak | Zatrzymaj, zwróć odmowę | 403 (policy) / 400 (schema) | Naruszenie twardej reguły (SSRF, traversal, brak uprawnień) |
| `RATE_LIMIT` | tak (wariant BLOCK) | Odrzuć tymczasowo | 429 + `Retry-After` | Przekroczenie RPM/TPM/współbieżności; budżet okresowy |
| `QUARANTINE` | zalecane | Zablokuj request **i** obiekt/źródło (klucz, serwer MCP, narzędzie, sesja) do czasu przeglądu | 403 + wpis w rejestrze kwarantanny | Drift definicji narzędzia, pętla, canary, anomalia kosztu, podejrzany klucz |
| `REVIEW` | tak | Wstrzymaj/oznacz do decyzji człowieka lub eskalacji do sidecara; domyślnie *monitor + flag* | 202 (async) lub przepuszczenie z flagą w audit | Sygnał niejednoznaczny (sam score entropii, PII medyczne) |
| `CHALLENGE` | opcjonalne | Wymagaj dodatkowego potwierdzenia (human-in-the-loop, step-up auth) | 401/403 + `challenge_id`, wznowienie po zatwierdzeniu | Akcja wysokiego wpływu dozwolona regułą (zapis do prod, wysyłka na zewnątrz) |

### Ocena dla naszego projektu
- **MVP (hackathon):** ALLOW, REDACT, BLOCK, RATE_LIMIT. To pokrywa test-suite z `VISION.md` §6.
- **QUARANTINE:** tani w implementacji (flaga w Postgres: `quarantined_until`, `reason`), a bardzo dobrze wygląda w dashboardzie → wdrożyć w fazie 2 dla MCP-INT, LOOP, OUT (canary).
- **REVIEW:** w MVP jako *flag-only* (decyzja przepuszczająca z `review=true` i widoczna w audit/dashboardzie); kolejka zatwierdzeń — NICE.
- **CHALLENGE:** w MVP pomijamy w runtime, ale zostawiamy w modelu (UI: przycisk „zatwierdź"). Dla SEQ i prod-destrukcyjnych akcji daje lepszy kompromis niż twardy BLOCK (mniej zmęczenia FP).
- Modyfikator `clamp` (np. obcięcie `max_tokens`, `num_predict`) to nie osobna decyzja, tylko `ALLOW` z `modified=true` i listą zmian.

## 2. Kolejność surowości i agregacja

```
BLOCK > QUARANTINE > CHALLENGE > RATE_LIMIT > REVIEW > REDACT > ALLOW
```
(Uwaga: `QUARANTINE` implikuje blokadę bieżącego żądania; „surowszy" = większy zasięg skutków.)

- Deny-overrides: najsurowsza decyzja ze wszystkich trafionych reguł wygrywa.
- `REDACT` kumuluje się (wszystkie zakresy), chyba że ktoś zwróci surowszą decyzję.
- Fail-mode: błąd ewaluatora/sidecara → decyzja z `on_error` reguły; domyślnie `fail_closed` dla etapów bezpieczeństwa, `fail_open` dla `edge` rate limitu.
- Sidecar nigdy nie zwraca `ALLOW` unieważniającego regułę deterministyczną; może tylko podnieść severity lub dodać `REVIEW`/`BLOCK` przez score hybrydowy.

## 3. Format wyniku pojedynczej kontroli

```json
{
  "decision": "BLOCK",
  "rule_id": "NET-001",
  "reason": "Tool argument URL resolves to link-local address 169.254.169.254",
  "severity": "CRITICAL",
  "confidence": 1.0,
  "latency_ms": 0.8,
  "stage": "tool_call",
  "category": "network",
  "matched": { "view": "canonical", "path": "$.args.url", "span": [12, 45], "fragment_hmac": "hm_9f3a…" },
  "modifications": [],
  "signals": [],
  "policy_version": "2026-10-03.4",
  "mode": "enforce"
}
```
- `confidence`: 1.0 dla twardych reguł (parser/checksum); <1 dla heurystyk i score (PI, entropia). Nie jest prawdopodobieństwem skalibrowanym.
- `matched.fragment_hmac`: HMAC z kluczem serwera — **nigdy surowa wartość** w logu.
- `modifications`: dla REDACT lista `{path, span, replacement}`; dla clamp `{param, from, to}`.
- `signals`: wkład sidecara (`{name, score}`) — widoczny, ale niesamodzielny.

## 4. Format zagregowanej decyzji requestu

```json
{
  "request_id": "req_01J…",
  "trace_id": "…",
  "final_decision": "REDACT",
  "caller": { "principal": "agent:support-bot", "tenant": "acme", "env": "prod" },
  "results": [ { "…": "wyniki pojedynczych kontroli (tylko trafione + decydujące)" } ],
  "evaluated_rules": 42,
  "total_latency_ms": 6.1,
  "sidecar_called": false,
  "policy_version": "2026-10-03.4",
  "audit_id": "aud_…"
}
```

## 5. Odpowiedź do klienta (przy BLOCK/RATE_LIMIT)

```json
{ "error": { "type": "policy_violation", "code": "NET-001", "message": "Request blocked by policy", "request_id": "req_01J…", "retry_after_s": null } }
```
Nie ujawniamy klientowi wzorca ani progów reguły (ułatwiałoby adaptacyjne obejścia — „The Attacker Moves Second"); szczegóły tylko w audit/dashboardzie. `Retry-After` dla RATE_LIMIT zawsze.

## 6. Semantyka per decyzja (checklist implementacyjna)

- **REDACT**: stabilny placeholder `[REDACTED:<rule_id>]`; w streamingu redakcja po hold-bufferze (OUT-001); redakcja nigdy nie zmienia struktury JSON (wartość → placeholder).
- **BLOCK** w streamingu: jeśli część już wysłana — zakończ strumień zdarzeniem błędu i zapisz `partial_sent=true` w audit.
- **QUARANTINE**: wpis `(subject_type, subject_id, reason, rule_id, until)`; zwolnienie ręczne z UI; audit zawiera kto zwolnił.
- **REVIEW**: `review=true` w audit; dashboard filtruje; opcja eskalacji do sidecara/LLM-judge dla granicznych.
- **CHALLENGE**: `challenge_id` ważny N minut, powiązany z hashem argumentów (zatwierdzenie nie przechodzi na inne wywołanie).
- **RATE_LIMIT**: nagłówki `Retry-After`, `X-RateLimit-*` (+ draft `RateLimit`/`RateLimit-Policy`).
