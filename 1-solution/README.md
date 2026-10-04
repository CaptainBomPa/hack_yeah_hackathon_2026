# 1. Solution

## Overview

**LLMinator (AI Control Layer)** is a security gateway that sits in front of any LLM — a local
model (Ollama) or an external one (ChatGPT via Codex CLI) — and enforces guardrails on every
request and response before/after the model ever sees or produces anything.

- **Java is the decision-maker.** All deterministic checks and the final allow/redact/block
  decision are plain Java (Spring Boot/WebFlux). A semantic classifier (Python/FastAPI sidecar)
  is consulted as one more **signal**, never a sole point of authorization — "semantics is a
  signal, Java decides" (`VISION.md` §4).
- **Policy is data, not code.** The whole policy (roles, model allowlist, guard thresholds,
  budgets, rate limits) is one versioned document. An admin edits it in the UI; the change is
  validated, versioned (append-only, with a hash/author/comment) and **active for the very next
  request — no restart, no redeploy**.
- **Two live integrations, same pipeline.** A web playground (`/v1/chat/completions` → Ollama)
  and **OpenAI Codex CLI** (`/v1/responses` → ChatGPT, via the user's own subscription OAuth) run
  side by side through the *identical* guard chain, budgets and audit log.
- **Deployed and running:** [https://llminator.fmroz.me](https://llminator.fmroz.me) — see
  `3-reporting/` for live screenshots and `/README.md` for demo accounts.

## Implemented controls / guardrails

Guards run in a configurable **chain of responsibility**: `Allow` → next guard, `Redact` →
rewrite the text and continue, `Block` → stop immediately. An exception inside a guard is treated
as `Block` (fail-closed). Order below is the live default (lower runs first).

**Checked on both directions of traffic, not just the prompt.** Each guard declares which
stage(s) it runs on — `INPUT` (the user's message), `OUTPUT` (the model's response) and
`TOOL_CALL` (tool/function-call arguments, for agent traffic). The chain runs **twice** per
request: once on the input before the model is ever called, and again on the model's response
before it reaches the client — so a model that "helpfully" repeats a PESEL or pastes back a
secret from its context gets caught and redacted on the way out too, not just blocked on the way
in. The only exception is `SEM-001`: the semantic sidecar currently only scores the prompt
(`INPUT`) — it doesn't yet classify model output or tool calls.

| Order | Guard ID | Type | Stages | What it does |
|---|---|---|---|---|
| 40 | `SIG-FEED` | deterministic | INPUT, OUTPUT, TOOL_CALL | Historical-attack signatures from an external feed (OSV.dev CVEs + hand-written payload patterns: PyYAML `!!python/*`, pickle RCE, Java deserialization magic bytes, Ollama path traversal). Hot-reloads from a YAML file on disk (mtime check) — a fix takes effect on the next request, independent of policy edits. |
| 50 | `SEC-GITLEAKS` | deterministic | INPUT, OUTPUT, TOOL_CALL | Leaked-secret detection — Java port of the Gitleaks rule pack (217 rules: AWS/GitHub/Slack tokens, JWTs, private keys...). Redacts by default; `private-key` blocks outright. |
| 100 | `PII-RECOGNIZERS` | deterministic | INPUT, OUTPUT, TOOL_CALL | Presidio-style PII engine, 8 recognizers: PESEL, NIP, REGON, Polish ID card, e-mail, phone, payment card (Luhn), IBAN/NRB (mod-97). Pattern + checksum validator + optional context word, same scoring model as Microsoft Presidio. |
| 200 | `SEM-001` | semantic | INPUT only | Prompt-injection/jailbreak classifier (sidecar, see `2-architecture/`). Sidecar returns a calibrated score; Java blocks at a configurable threshold. Fail-closed on sidecar timeout/error/incomplete result. |
| — | `ConversationGuard` | deterministic | INPUT | Re-checks the **full conversation history** sent by the client on every turn (stateless server), not just the latest message; history that would be blocked is replaced with a placeholder instead of reaching the model raw. Verdicts for unchanged history are cache-eligible (see below). |
| — | Model allowlist + per-role access | deterministic | gate (pre-chain) | A model must be both in the deployment catalog *and* enabled by the active policy *and* in the caller's role's model list (or `"*"`). Unknown model and "not allowed" look identical to the client (no enumeration). |
| — | Budget governance | deterministic | gate (pre-chain) | Input-size limit (estimated tokens), per-role daily token cap (atomic DB reservation, `BUDGET-003`), output clamp. A guard-blocked request releases its reservation — it never silently eats budget. |
| — | Rate limiting | deterministic | gate (pre-chain) | Token-bucket (requests/minute + burst) and concurrency caps (per-user and global), `block`/`monitor`/`off` modes, pipeline timeout with lease cleanup on cancel/upstream failure. |
| — | Audit log | deterministic | post-decision | Every decision (including `allow`) is written **before** the client gets a response; if the write fails and the policy is fail-closed, the client gets `503` instead of a model answer that was never recorded. Tamper-evident: HMAC-SHA256 hash chain (`record_hash = HMAC(key, prev_hash + record)`), one-click **"Verify integrity"** in the UI. |

A guard can be turned **off** per policy; a disabled guard still appears in the trace ("off",
with a reason) so a judge who disables a control can see that it is off, not silently missing.

## Configuration

Policy is one document (`PolicyDocument`), source of truth in the `policy_version` table
(append-only; `config/policy.yaml`/`application.yml` only seed version 1 on first boot):

```yaml
policy:
  rateLimit: { mode: block, requestsPerMinute: 30, burstCapacity: 5, maxConcurrentPerUser: 1, maxConcurrentGlobal: 8, pipelineTimeout: 120s }
  roles:
    admin: { models: ["*"] }                                       # no budget = unlimited
    chat:  { models: ["qwen2.5:1.5b-instruct-q4_K_M", "qwen2.5:0.5b"], budget: { dailyTokens: 20000 } }
    agent: { models: ["qwen2.5:1.5b-instruct-q4_K_M"], budget: { dailyTokens: 100000 } }
    codex: { models: ["gpt-6.1-sol", "gpt-5.6-sol", ...], budget: { dailyTokens: 1000000 } }
guards:
  PII-RECOGNIZERS: { enabled: true, order: 100, params: { threshold: 0.5 } }   # per-recognizer action override: block/redact/monitor/off
  SEM-001:         { enabled: true, order: 200, params: { blockThreshold: 0.9, timeoutMs: 4000, failureMode: closed } }
  SIG-FEED:        { enabled: true, order: 40,  params: { feed: config/signatures/active.yaml, disabledSignatures: "" } }
limits: { maxInputTokens: 4000, maxOutputTokens: 1024 }
```

- **Per-recognizer/per-rule granularity**: each of the 8 PII recognizers and each Gitleaks rule
  has its own action (`block`/`redact`/`monitor`/`off`) and can be excluded individually —
  visible and editable live in `/policies` (see `3-reporting/screenshots/policies-guards.jpg`).
- **Roles ↔ models ↔ budgets** are one matrix in the UI, with per-role daily token caps.
- A broken edit (unknown guard, out-of-range threshold, duplicate model, missing `admin` role, …)
  is rejected by `PolicyValidator` and never goes live — the old version keeps governing traffic.
- Every save is a new, hashed, rollback-able version with history (`GET /api/policy/versions`)
  and YAML export/import.
