# HackYeah Hackathon 2026 — AI Control Layer

Gateway zabezpieczający interakcje z lokalnym LLM (Raspberry Pi + Ollama): guardraile
deterministyczne + semantyczne, budżetowanie, audyt i dashboard bezpieczeństwa.

**Zanim zaczniesz pracować w tym repo (człowiek czy agent AI) — przeczytaj
[`VISION.md`](VISION.md).** To jedyne źródło prawdy o architekturze, stacku, podziale
kontroli między zespoły i planie test suite. Najważniejszym dokumentem źródłowym zadania,
nadrzędnym wobec naszych opisów rozwiązania, jest
[`CRITERIA AI Control Layer.pdf`](project-spec/CRITERIA%20AI%20Control%20Layer.pdf).

## Struktura repo

- [`VISION.md`](VISION.md) — architektura, stack, katalog kontroli, plan testów (czytaj to najpierw)
- [`backend/`](backend/) — Java 21 + Spring Boot 3 / Spring Cloud Gateway (placeholder)
- [`frontend/`](frontend/) — React + TypeScript + Tailwind (placeholder)
- [`docker-compose.yml`](docker-compose.yml) — Postgres + backend + frontend (+ sidecar semantyczny, do dodania)
- [`CLAUDE.md`](CLAUDE.md) — instrukcje dla Claude (Claude Code i inne narzędzia)
- [`AGENTS.md`](AGENTS.md) — instrukcje dla agentów OpenAI/GPT (Codex i zgodne z konwencją AGENTS.md)
- [`GEMINI.md`](GEMINI.md) — instrukcje dla Gemini CLI

Wszystkie trzy pliki agentowe wskazują na ten sam `VISION.md`, żeby nie rozjeżdżał się kontekst
między narzędziami.

## Status

Projekt w fazie startowej — architektura i podział pracy ustalone w `VISION.md`,
implementacja `backend/`/`frontend/` w toku.
