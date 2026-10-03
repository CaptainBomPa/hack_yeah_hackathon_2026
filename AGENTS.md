# AGENTS.md

Kontekst i instrukcje dla agentów AI opartych o OpenAI/GPT (np. Codex CLI i inne narzędzia
zgodne z konwencją `AGENTS.md`) pracujących w tym repozytorium.

## Projekt

AI Control Layer na HackYeah 2026: gateway przed lokalnym LLM (Raspberry Pi + Ollama),
egzekwujący guardraile deterministyczne i semantyczne, budżety i audyt bezpieczeństwa.

**Pełny, wiążący opis architektury, stacku, podziału kontroli między zespoły i planu test
suite jest w [`VISION.md`](VISION.md) — przeczytaj go przed jakąkolwiek zmianą w kodzie.**
Nie kopiuj jego treści tutaj ani nie podejmuj decyzji architektonicznych sprzecznych z nim bez
zaktualizowania `VISION.md` najpierw (to jedyne źródło prawdy, wspólne dla Claude/Codex/Gemini).
Najważniejszym dokumentem źródłowym zadania, nadrzędnym wobec naszych opisów rozwiązania, jest
[`CRITERIA AI Control Layer.pdf`](project-spec/CRITERIA%20AI%20Control%20Layer.pdf).

## Struktura repo

- `backend/` — Java 21 + Spring Boot 3, Spring Cloud Gateway (reactive) jako szkielet control layera; kontrole jako własne `GatewayFilterFactory` (szczegóły: VISION.md §3–4)
- `frontend/` — React + TypeScript + Tailwind: demo-chat + dashboard bezpieczeństwa
- sidecar semantyczny (Python/FastAPI, do dodania) — klasyfikatory prompt-injection/jailbreak, wołany przez gateway po HTTP

## Jak uruchomić projekt

`docker-compose.yml` jest na razie szkieletem (Postgres + backend + frontend) — Dockerfile'e
w `backend/` i `frontend/` jeszcze nie istnieją. Przed pierwszym pełnym uruchomieniem
dopisz je zgodnie ze stackiem z `VISION.md` §3. Model LLM uruchamiany jest osobno przez
Ollamę na Raspberry Pi (sieciowo, nie w docker-compose).

## Konwencje kodu

- Backend: standardowe konwencje Spring Boot (pakiety per feature/kontrola, nie per warstwa
  techniczna), każda kontrola deterministyczna jako osobny, testowalny filtr.
- Polityki/config: trzymać jako dane (YAML/JSON w Postgres lub pliku), nigdy hardcoded w kodzie
  Javy — jury będzie podmieniać config na żywo i oczekiwać reakcji bez restartu.
- Test case'y guardrails: dane (YAML/JSON), nie kod testowy — patrz VISION.md §6.

## Testy

Zob. `VISION.md` §6 — test suite to katalog danych (positive/negative cases) + runner
odpalany jednym poleceniem (`./run-tests.sh`) przeciwko żyjącej instancji gateway. Jury
będzie to uruchamiać samodzielnie, więc runner musi działać bez wcześniejszego przygotowania
poza "serwis jest uruchomiony".

## Inne uwagi

- Nie polegać na zewnętrznych, płatnych/hostowanych serwisach AI (np. TypeSafeAI) jako
  głównym mechanizmie — regulamin wymaga działania offline na własnym sprzęcie. Zob. VISION.md
  §4 ("Narzędzia zewnętrzne — decyzja").
- `openjev` i podobne lekkie klasyfikatory używać wyłącznie jako jeden sygnał w hybrydowym
  scoringu, nigdy jako jedyną bramkę decyzyjną (słaba odporność na adversarial input).
- Jeśli zmieniasz decyzję architektoniczną, zaktualizuj `VISION.md` w tym samym commicie —
  to plik, z którym synchronizują się też `CLAUDE.md` i `GEMINI.md`.
