# AI Control Layer — analiza zadania i wstępny plan implementacji

Najważniejszym dokumentem źródłowym zadania, nadrzędnym wobec tego planu, jest
[`CRITERIA AI Control Layer.pdf`](../project-spec/CRITERIA%20AI%20Control%20Layer.pdf).
Uzupełniają go formalne [`RULES AI Control Layer.pdf`](../project-spec/RULES%20AI%20Control%20Layer.pdf).

## Podsumowanie zadania

Bramka (gateway/proxy) między agentami a LLM/MCP, łącząca kontrole deterministyczne i semantyczne (AI), z centralną polityką przeładowywaną na żywo, budżetami, dashboardem i automatycznym zestawem testów uruchamianym przez jury bez przygotowania.

Wymagane artefakty:

1. Działająca warstwa kontrolna + demo agent + diagram architektury.
2. Udokumentowany plik polityki (poziomy strictness, budżety).
3. Interaktywny dashboard (kontrole, postura, zablokowane zagrożenia, koszty).
4. Wykonywalny zestaw testów (pozytywne i negatywne przypadki, budżety, exploity).

## Punktacja

| Kryterium | CRITERIA | RULES |
|---|---|---|
| Robustness / jakość guardrails | 30% | 30% |
| Architektura i wydajność | 20% | 20% |
| Security Reporting | 20% | 20% |
| Self-Testing Suite | 15% | 20% |
| Implementowalność i skalowalność | 15% | 10% |

Przy rozbieżności rozstrzyga RULES (dokument formalny).

Wnioski:

- Testy i raportowanie to łącznie 40% — robimy je od początku, nie na końcu.
- Ocena 2-fazowa: zgłoszenie na HackTribe (opis + PDF max 10 slajdów), potem pitch finalistów. Do nagrody potrzeba min. 50% punktów w fazie 1, więc slajdy i diagram są kluczowe.
- Okno czasowe w RULES: „od 11:00 PM 3.10 do 11:00 PM 4.10” — prawdopodobnie literówka (AM), do potwierdzenia u organizatorów.
- Brak płatnych API — wszystko lokalnie (Ollama).
- Jury będzie: uruchamiać nasze testy, wpisywać własne prompty na żywo, edytować config (usuwać kontrole, zmieniać progi) i sprawdzać reakcję w czasie rzeczywistym, oglądać telemetrię wydajności. To jest de facto scenariusz demo.

## Podejście

Proxy zgodne z OpenAI API (`/v1/chat/completions`) przed Ollamą oraz endpoint/proxy dla wywołań narzędzi MCP. Integracja dla dewelopera = zmiana `base_url`.

```
Agent ──> [AuthN/AuthZ] ──> [Budget/Rate] ──> [Input: deterministic] ──> [Input: semantic] ──> LLM/MCP
                                                                                              │
Agent <── [Output: deterministic + semantic (redakcja/blok)] <── [Tool-call governance] <────┘
                    │
                    └──> Audit log (DB) ──> Dashboard / eksport / Prometheus
```

### Kontrole

Każda kontrola ma tryb `off | monitor | redact | block` i próg z polityki.

1. **Tożsamość i dostęp** — klucz API per agent, RBAC: dozwolone modele i narzędzia MCP; destrukcyjne narzędzia (`delete_*`, `send_email`) wymagają flagi/zatwierdzenia.
2. **Deterministyczne** — regexy PII (PESEL z sumą kontrolną, IBAN, karty z Luhnem, email, telefon), sekrety (klucze AWS, JWT, klucze prywatne, wysoka entropia), deny-listy, limity długości i kodowania (base64/unicode smuggling).
3. **Sygnatury historycznych ataków** — zewnętrzny feed (plik/URL, przeładowywany): pickle/`__reduce__`, `trust_remote_code=True`, `curl | sh`, `os.system`, podejrzane modele/paczki (typosquatting HF/PyPI), znane jailbreaki. Każda sygnatura ma ID i referencję (CVE / OWASP LLM Top 10).
4. **Semantyczne (AI)** — klasyfikator prompt injection (Prompt Guard / Llama Guard 3 1B przez Ollamę lub mały LLM z wymuszonym JSON), podobieństwo embeddingów do korpusu ataków, ocena wyjścia (wyciek system promptu przez canary token, treści szkodliwe).
5. **Budżety** — tokeny i koszt ($ wg cennika w polityce; „wirtualny” koszt modeli lokalnych wg czasu obliczeń) per agent/zespół/dzień, rate limit, limit kroków agenta, wykrywanie pętli (te same wywołania narzędzia N razy z rzędu).
6. **Output** — redakcja PII/sekretów w odpowiedzi, walidacja argumentów tool-calli.

### Wydajność

- Najpierw tanie kontrole deterministyczne z early-exit, potem równoległe (async) kontrole AI.
- Cache werdyktów, timeouty, konfigurowalny fail-open/fail-closed.
- Pomiar latencji każdej kontroli (p50/p95) w dashboardzie i na `/metrics`.

### Polityka

- Jeden plik `policy.yaml`, walidacja pydantic, hot-reload (watcher).
- Niepoprawny config jest odrzucany, zostaje ostatnia dobra wersja.
- Hash/wersja polityki widoczne w dashboardzie i w każdym logu audytowym.
- Profile `strict / balanced / permissive`.

## Stack

- **Backend:** Python + FastAPI, Postgres (audit log, już w `docker-compose.yml`), Ollama jako serwis w compose.
- **Frontend:** React + Vite + Recharts. Widok zarządczy (koszty, % zablokowanych, wynik postury) i widok security (zdarzenia, drill-down, dowody po redakcji, mapowanie na OWASP LLM Top 10 2025). Eksport JSONL/CSV (SIEM-ready).
- **Playground** w dashboardzie: czat przez bramkę z trace'em pokazującym, które kontrole zadziałały i ile trwały — pod testy ad-hoc jury.
- **Demo agent:** prosty agent z narzędziami MCP (pliki, fake DB, „wyślij maila”) przez bramkę.
- **Testy:** pytest + przypadki w YAML (pozytywne/negatywne dla każdej kontroli), jedno polecenie (`make test` / `docker compose run tests`). Testy deterministyczne bez modelu, semantyczne pod osobnym markerem. Raport HTML z macierzą „kontrola × allow/block/redact”, testy hot-reloadu, wyczerpania budżetu i pętli, skrypt red-team przeciw działającej bramce.

## Plan (~24h)

| Faza | Czas | Zakres |
|---|---|---|
| 0 | 1h | Szkielet repo, compose (db, ollama, backend, frontend), kontrakt API, schemat `policy.yaml`, model zdarzenia audytowego |
| 1 | 4h | Proxy OpenAI-compatible, pipeline kontroli (interfejs `Control`), auth per agent, kontrole deterministyczne, audit log do DB, pierwsze testy |
| 2 | 4h | Hot-reload polityki + profile, budżety, rate limit, wykrywanie pętli, feed sygnatur ataków |
| 3 | 4h | Kontrole semantyczne (Ollama), filtrowanie outputu, governance tool-calli MCP, demo agent |
| 4 | 5h | Dashboard (metryki, zdarzenia, koszty, latencja, playground z trace), eksport logów, `/metrics` |
| 5 | 3h | Pełna macierz testów, raport HTML, skrypt red-team, benchmark latencji |
| 6 | 3h | Diagram architektury, README „run in 1 command”, 10 slajdów PDF, próba demo z edycją configu na żywo |

### Podział zespołu

- 1–2 osoby: pipeline i kontrole
- 1 osoba: AI/semantyka + Ollama
- 1 osoba: dashboard
- 1 osoba: testy + red-team
- 1 osoba: dokumentacja i slajdy (od połowy także testy)

### Ryzyka

- **Latencja Ollamy na laptopie** — małe modele (1–3B), cache, semantyka tylko powyżej progu ryzyka.
- **Uruchomienie przez jury bez przygotowania** — compose sam pobiera model, testy deterministyczne niezależne od GPU.
