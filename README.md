# HackYeah Hackathon 2026 — AI Control Layer

[![Backend tests](https://github.com/CaptainBomPa/hack_yeah_hackathon_2026/actions/workflows/backend-tests.yml/badge.svg?branch=main)](https://github.com/CaptainBomPa/hack_yeah_hackathon_2026/actions/workflows/backend-tests.yml)

A security gateway in front of LLMs: deterministic and semantic guardrails, budget governance,
audit logging and a dashboard.

## Deployed application

**[https://llminator.fmroz.me/](https://llminator.fmroz.me/)**

Demo accounts (`backend/config/users.yaml`):

| Login | Password | Role | Permissions |
|---|---|---|---|
| `admin` | `admin` | admin | full access: every model, the `/dashboard`, `/audit` and `/policies` panels |
| `chat1`, `chat2`, `chat3` | same as login | chat | `/playground` on `qwen2.5:1.5b-instruct-q4_K_M` and `qwen2.5:0.5b`, 20,000 tokens/day limit |
| `agent-runner` | `agent-runner-123` | agent | machine account (HTTP Basic), no frontend access |
| `agent-sdk` | `agent-sdk-123` | agent | same as above |

What the views are for:

- **Playground** — a plain chat sandbox: send any prompt and immediately see how the gateway
  reacted (allow / redact / block) and why, for quick manual testing.
- **Dashboard**, **Audit log** and **Policies** (admin only) — these cover what the task required:
  live performance telemetry and dashboards, a full decision/audit log (with integrity
  verification), and real-time policy editing (thresholds, guards, budgets) with no restart.

## Tests (Cucumber / self-testing suite)

Scenarios: [`backend/src/test/resources/features/`](backend/src/test/resources/features/).
Step definitions: [`backend/src/test/java/.../chat/bdd/`](backend/src/test/java/pl/hackyeah/controllayer/chat/bdd/).

```bash
cd backend
./gradlew test              # full suite (JUnit + Cucumber), no Docker needed
```

Extended version with the real semantic sidecar (instead of an HTTP fake) — needs Docker,
**first run downloads a ~600MB model from Hugging Face and can take a few minutes**:

```bash
./gradlew testWithSidecar
```

## Project structure

- [`backend/`](backend/) — Java 25 / Spring Boot 4: gateway, guards (PII, secrets, semantic,
  budget, rate limit), live-editable policy
- [`frontend/`](frontend/) — React + TypeScript: playground, dashboard, audit log, policy editor
- [`semantic-sidecar/`](semantic-sidecar/) — Python/FastAPI prompt-injection classifier
- [`docs/`](docs/) — architecture and decision docs
- [`project-spec/`](project-spec/) — competition materials (CRITERIA, RULES)
- [`docker-compose.yml`](docker-compose.yml) — the whole stack

## Running it (Docker Compose)

```bash
docker compose up -d --build
```

First start downloads images and models (Ollama ~1.4GB, sidecar ~600MB) — takes a few minutes.
Then: frontend on `localhost:3000`, backend on `localhost:8000`.
