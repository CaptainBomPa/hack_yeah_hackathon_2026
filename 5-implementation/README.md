# 5. Implementation

## Code

- [`backend/`](../backend/) — Java 25 / Spring Boot 4 / WebFlux gateway: guards, policy engine,
  budget/rate-limit gates, audit, dashboard API, web (`/v1/chat/completions`) and Codex
  (`/v1/responses`) integrations. Build: `./gradlew bootRun` (profile `local`, H2).
- [`frontend/`](../frontend/) — React + TypeScript SPA (Playground, Dashboard, Audit log, Policies).
- [`semantic-sidecar/`](../semantic-sidecar/) — Python/FastAPI classifier provider (interchangeable; a
  plain HTTP contract, see `2-architecture/`).
- [`cli/`](../cli/) — Node launcher that fronts an **existing agentic CLI** (Codex) with the gateway
  (see below).
- [`backend/config/`](../backend/config/) — `policy.yaml`, `users.yaml` (demo accounts),
  `signatures/active.yaml` (SIG-FEED feed) — all hand-editable, hot-reloaded or versioned.
- [`backend/src/test/resources/features/`](../backend/src/test/resources/features/) — the
  self-testing suite (`4-testing/`).

## Additional implementation considerations

- **Fail-closed by default, everywhere.** An unhandled exception in any guard → `Block` (not
  skip). Audit write failure → `503` instead of a silently-unrecorded response. Semantic sidecar
  timeout/error/incomplete result → `Block` unless the policy explicitly sets `failureMode: open`.
  Rate-limit store unavailable → `503`, fail-closed. This is a deliberate, consistent policy
  across the whole gateway, not guard-by-guard improvisation.
- **Stateless-history re-validation.** The client resends the full conversation on every turn
  (no server session state); the gateway re-checks *every* message each time, not just the new
  one, so content redacted/blocked two turns ago can't resurface unredacted through later
  context — handled by `ConversationGuard`, with cacheable per-message verdicts (see below) to
  keep this affordable.
- **Caching vs. hot-reload — a real bug we found and fixed via testing.** A generic
  `(policy hash, stage, text)` result cache was added for performance. It implicitly assumed
  every guard's output depends only on `(policy, text)` — false for `SIG-FEED`, whose hot-reload
  is file-based and **independent of the policy**. The Cucumber suite caught this (a disabled
  signature appeared to "stay blocked" for 30 minutes). Fix: `Guard.cacheable()` (default `true`),
  overridden `false` on `SIG-FEED`; the cache is bypassed entirely for any stage a non-cacheable
  guard participates in. Covered by regression scenarios in `4-testing/`.
- **Visible "off", not silent absence.** A disabled guard still appears in the per-request trace
  as `off` with a reason — a judge toggling a control off can verify from the trace that it's
  really off, rather than guessing from its absence.
- **Tamper-evident audit, not just an append-only table.** HMAC-SHA256 hash chain
  (`record_hash = HMAC(key, prev_hash ‖ record)`), verifiable on demand without exposing content.
- **Everything editable without redeploy** is backed by `PolicyValidator`, so a bad edit can
  never go live — not a "best-effort UI validation", a hard server-side gate before the version
  is activated.

## Deploying to existing agentic ecosystems

The gateway isn't limited to its own playground. **OpenAI Codex CLI is fronted live today**,
proving the pattern works against a real, unmodified, third-party agent:

- `cli/control-layer.mjs` is a cross-platform (Windows/macOS/Linux) Node launcher that starts
  Codex with a **custom provider pointed at the gateway** — it never edits Codex's own config,
  login files, shell profile, PATH or registry. `install`/`run`/`disable`/`uninstall` manage an
  isolated routing profile; Codex's native configuration is untouched and still works normally
  when disabled.
- Uses the user's **existing ChatGPT subscription OAuth** (`codex login`), not an API key —
  `Authorization` stays Codex's own OAuth header; the gateway account is authenticated separately
  via `X-Control-Layer-Authorization`. The raw gateway secret is never written to the Codex
  profile and is stripped from the child process environment after derivation.
- The backend speaks Codex's **native wire protocol** (`POST /v1/responses`,
  `/v1/responses/compact`, `GET /v1/models`) — same guard chain, budgets, rate limiter and audit
  log as the web playground, applied to coding-agent traffic (tool calls, file contents, shell
  output) instead of chat messages. Streamed responses are buffered server-side and re-assembled
  after OUTPUT checks, so a redaction can't leak through individual SSE deltas.
- Tested cross-platform in CI (Windows/macOS/Linux) against both a protocol fixture and
  `node --test`, with fake credentials — no real account used in tests.
- **Try it**: `node cli/start-demo.mjs` (see root `README.md`, "Codex CLI integration") opens
  Codex against the deployed gateway with a folder of fake customer data; asking it to read the
  file shows PII coming back redacted in the audit log in real time.

This demonstrates the practical answer to "how does this attach to an agentic ecosystem that
already exists": **no agent-side code changes, no plugin, no MCP server** — a drop-in transport
adapter plus a policy that already knows about that integration's models and budgets. The same
pattern (custom provider / base-URL override + a gateway account) generalizes to any agent CLI
that supports pointing at an alternate API endpoint.

## Scalability notes

- Policy, audit, budget and rate-limit state live in PostgreSQL — the gateway itself is
  stateless and horizontally scalable; rate-limit admission uses a DB-serialized lease, not
  in-process memory, so it's correct across multiple gateway instances.
- The semantic provider is a separate HTTP service by design (`VISION.md` §2) — swappable for a
  hosted API or a bigger/smaller local model without touching the Java decision layer.
- Guard chain construction is cached per policy version/hash, not rebuilt per request.
