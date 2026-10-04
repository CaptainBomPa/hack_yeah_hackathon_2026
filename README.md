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
| `codex-agent` | `codex-agent-123` | codex | Codex CLI account, used by default by `node cli/control-layer.mjs run codex`: the GPT models from `codex-models.yml`, 1,000,000 tokens/day |

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

Rebuild the whole stack: `docker compose up -d --build`.
After backend changes: `docker compose up -d --build backend`.
Details: [backend/README.md](backend/README.md#przebudowa-po-zmianach-z-katalogu-głównego-repo).

## Codex CLI integration

The same gateway also fronts **Codex CLI** (ChatGPT subscription login, no OpenAI API key):
Codex traffic (`/v1/responses`) goes through the same guards, budgets and audit log as the web
playground (`/v1/chat/completions`) — both integrations run side by side.

**Try it** (Node 20+ and [Codex CLI](https://github.com/openai/codex) installed), from the repo root:

```bash
codex login                 # once: your ChatGPT login
node cli/start-demo.mjs     # opens Codex through LLMinator, with fake bank customer data
```

Codex opens in a fresh `~/llminator-demo` folder, which holds only `customers.csv` with
fictional customers. Type these prompts:

| Prompt | What you'll see |
|---|---|
| `Show me the first three rows of customers.csv.` | rows come back as `[REDACTED:PL_PESEL]` / `[REDACTED:EMAIL_ADDRESS]`: the agent read the file, the model never saw the data |
| `Write a Java function that validates a Polish PESEL number. Test it with 44051401359.` | the PESEL in the prompt is redacted or blocked (`PII-001/PL_PESEL`), depending on the policy |
| `What does a typical customer email address on Gmail look like? Give me an example.` | the email in the model's answer is redacted |

Each decision appears in the **Audit log** at [llminator.fmroz.me](https://llminator.fmroz.me/)
(log in as `admin`, filter **Reason**). To use Codex through LLMinator in your own project, run
`node <repo>/cli/control-layer.mjs run codex` from that project's folder.

- Launcher details (profiles, other gateway/account): [cli/README.md](cli/README.md)
- Backend setup (model, policy, endpoints): [backend/README.md](backend/README.md#web-playground-i-codex-cli-równolegle)
