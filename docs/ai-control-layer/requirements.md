# AI Control Layer — prioritized requirements and acceptance tests

Read with the [research report](README.md), [Jev assessment](jev-assessment.md), and [sources](sources.md).

## 1. How to use this catalog

- **P0 — baseline:** demonstrate a narrow working version in the hackathon. These are behavioral checks, not separate services or integrations.
- **P1 — stretch:** implement after the P0 vertical slice works; disclose omissions.
- **P2 — production:** required before making the corresponding enterprise claim, not required to finish the demo.
- **B:** explicitly requested by the [brief](sources.md#b0), including its context/evaluation sections.
- **D:** derived security/engineering requirement, recommended to make the stated control credible.

Every requirement must have an owner, implementation status, and linked test before the team declares it delivered. Those implementation assignments are not known yet. No feature in this document is claimed to be implemented.

**Central acceptance principle:** an `ALLOW` label does not prove an action is safe, and a `BLOCK` label does not prove it was prevented. Tests must observe the actual upstream invocation/side effect and accounting state.

## 2. Integration and policy

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R01 | P0 · B | A developer can route a sample agent's interactions through the control layer using a documented adapter/base URL/configuration change | A real end-to-end agent task succeeds; architecture diagram and supported-interface list match the running system |
| R02 | P0 · B/D | Govern one real MCP server's discovery, invocation, arguments, and returned content, using an explicitly supported protocol revision and transport | Allowed tool executes; denied tool does not execute even if invoked by name after being hidden from discovery; unsafe result does not enter agent context |
| R03 | P0 · B | Support a local model and a configured commercial-API route with model allowlists and resource accounting | Local inference runs without paid credentials; remote adapter passes contract tests against a metered stub and, if credentials exist, a separately labelled live check; unknown model is denied |
| R04 | P0 · D | In the demonstrated deployment, the agent cannot bypass mandatory enforcement using upstream credentials or direct networking | Agent lacks provider/tool credentials; direct network/path access fails while gateway-mediated access succeeds. Document residual host/admin bypass |
| R05 | P0 · B | One authoritative configuration defines controls, model/tool permissions, detector thresholds, action modes, and budget/resource rules | A policy schema rejects unknown/malformed fields; a documented example exercises allow, block, redact, and budget limits |
| R06 | P0 · B/D | Apply valid live configuration/feed changes atomically and retain last-known-good state after invalid updates | Valid change affects new decisions within proposed 2 s target; concurrent requests see complete old/new versions, never partial state; malformed file yields visible error without disabling protection |
| R07 | P0 · B/D | Support configurable strictness without confusing probabilistic thresholds with permissions or randomly skipping mandatory checks | Strict/standard/observe profiles produce documented outcomes; observing a semantic alert does not bypass mandatory authentication, authorization, or configured budget caps. Authorized control removal is reflected in posture and audit |
| R08 | P0 · D | Restrict policy administration and reporting access to authenticated, scoped actors | Non-admin cannot alter policy/feed or inspect another tenant's events; admin changes have actor, timestamp, version, and diff/hash evidence |

**Strictness interpretation:** the brief mentions sensitivity/adherence percentages. Implement these as documented detector thresholds and action policies, not “skip security on 10% of calls.” Administrators may intentionally change configurable controls, but the dashboard must distinguish disabled, observe-only, enforced, and unavailable controls.

## 3. Identity, actions, and trust boundaries

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R09 | P0 · B/D | Authenticate each protected request and derive user, tenant, agent, and scopes from verified application credentials | Missing/expired/wrong-audience credentials fail; a prompt or tool argument claiming another role cannot change identity; no upstream token passthrough |
| R10 | P0 · B/D | Enforce tenant/resource permissions for files, records, and exposed memory operations before reads/writes | User A can access A's allowed record but not B's; changing a resource ID, workflow handle, or memory key does not cross the boundary |
| R11 | P0 · B/D | Authorize the specific tool/action and validate complete arguments, not just tool name | An allowed read succeeds; a forbidden delete, oversized amount, unexpected JSON field, disallowed recipient, or path escape never reaches the tool; exact numerical checks run in code |
| R12 | P0 · D | Restrict outgoing destinations and prevent governed fetch/API tools from becoming open proxies | Approved destination succeeds; unapproved host, metadata/link-local target, malicious redirect, and DNS-to-private-address fixture fail. Internal services require explicit allowlisting |
| R13 | P0 · D | Execute only the exact action approved by the decision engine, once, under still-valid authority | Modifying arguments, principal, tool version, or expiry invalidates authorization; duplicate mutation request does not repeat the side effect; emergency revocation wins before dispatch |
| R14 | P1 · D | Require out-of-band human approval for specified high-impact actions | Approver sees actual destination/resource/parameters; approval binds canonical action hash, identity, scope, policy, expiry, and nonce; replay/changed-argument approval fails. Until implemented, such actions are denied |
| R15 | P1 · D | Detect changes to approved MCP tool descriptions/schemas and quarantine or reapprove them | Known hash is accepted; changed definition after approval is blocked and logged; caches invalidate. Inspect all relevant metadata, not just the description |
| R16 | P2 · B/D | Preserve authenticated identity and narrowed delegation across agent-to-agent calls | Downstream agent cannot gain scopes, budget, or resource access that the delegating actor lacks; signed/verified context cannot be forged by model text |

Derived guidance: [OWASP MCP](sources.md#s02), [MCP security specification](sources.md#s03), [OWASP agentic risks](sources.md#s01). A workflow or memory handle is not authentication. If legacy MCP sessions are supported, bind them to principals; current MCP revisions may not have protocol sessions at all.

## 4. Data protection and semantic controls

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R17 | P0 · B | Detect configured secret/PII types in prompts, arguments, tool results, and model responses; apply route-specific block/redact actions | Synthetic API token blocks outbound transfer; allowed PII-bearing response is correctly redacted; benign lookalike stays unchanged; raw secrets do not appear in logs or remote classifier payloads |
| R18 | P0 · B/D | Inspect output before release, preserving structural correctness and preventing partial-stream leaks | A blocked bounded response releases no payload bytes; redacted JSON remains schema-valid. MVP may buffer/disable streaming. Never claim “zero leakage” from scanning chunks already released |
| R19 | P0 · B | Use a real semantic evaluator to assess direct/indirect injection and proposed-action alignment at relevant boundaries | Real Jev or local inference runs on user input, retrieved/tool content, and action context; a benign quotation about injection is tested alongside attacks; test doubles are visibly distinguished from real inference |
| R20 | P0 · D | Represent uncertainty, missing evidence, timeout, and detector errors separately from a low-risk result | Borderline score, missing answer, NaN/out-of-range value, timeout, 429/529, and invalid response follow explicit hold/deny/degraded policy; no silent allow |
| R21 | P0 · D | Maintain trusted provenance and a bounded relevant action history; untrusted content cannot rewrite classifier instructions or policy | Tool result containing fake system/policy text remains tagged untrusted; trusted mandate comes from authenticated application state; hard policy denial persists even when semantic response says safe |
| R22 | P1 · B/D | Protect memory integrity and track sensitive/untrusted lineage across multi-step tool flows | Untrusted result cannot overwrite trusted instruction memory; private-read → transformed/external-send scenario is blocked or held; legitimate within-scope retrieval remains allowed |
| R23 | P1 · D | Evaluate supported languages and common obfuscation/long-context evasions rather than silently treating them as covered | Include Polish and English if claimed, encoded/Unicode fixtures, and attack-at-end cases; report per-slice results. Unsupported input is held or explicitly excluded, not declared clean |
| R24 | P0 · D | Bound request size, nesting, decoded size, context length, and media types before expensive inspection | Oversized/compressed/deep JSON inputs fail cheaply; binary/image content is rejected or routed to a separately declared safe parser, never silently omitted; no truncation that hides unchecked content |

Jev-specific constraints: [J02](sources.md#j02), [J03](sources.md#j03), [J05](sources.md#j05). Exact-span redaction is not supplied by Jev's yes/no output. Use recognized spans and deterministic transformations; if safe redaction is impossible, block rather than guess.

**Tool-argument redaction:** do not silently change an email recipient, payment amount, path, or command. Return a controlled denial/correction request, or revalidate and reauthorize the entire modified action.

## 5. Budget and resource governance

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R25 | P0 · B/D | Enforce configured money/token caps with atomic pre-execution reservations at applicable organization, user, agent, and workflow scopes | Exact-limit call admitted; over-limit call denied before provider; at least 50 concurrent attempts cannot oversubscribe a bounded priced route; integer/decimal accounting avoids float drift |
| R26 | P0 · B | Bound local-model resource consumption independently of monetary API spend | Local model with $0 tariff still hits output-token, concurrency, queue, and elapsed-time limits; report observed termination behavior, not assumed GPU cancellation |
| R27 | P0 · B/D | Stop runaway workflows using task deadlines, maximum tool/model calls, recursion/delegation depth, and repeated-failure limits | Benign multi-step task completes; loop stops at configured boundary; new sub-agent/task ID cannot reset the parent cap |
| R28 | P0 · D | Meter and limit the security layer's own detector calls, retries, and queue | Flooded rejected traffic has bounded Jev calls and memory; detector outage does not cause retry amplification; guardrail requests cannot recursively trigger themselves |
| R29 | P0 · D | Reconcile usage durably and safely across timeout, disconnect, cancellation, crash, and duplicate delivery | Restart preserves spend and dispatched holds; duplicate completion does not double-charge; unknown provider billing remains reserved/flagged until reconciliation; non-idempotent action is not blindly retried |
| R30 | P0 · D | Treat unknown prices/usage and unbounded billable routes explicitly; enforce the same caps on fallback providers | Missing tariff is denied in hard-cap mode; provider returning no usage does not become free; expensive fallback must obtain its own valid reservation; changing window/model cannot escape parent budget |
| R31 | P1 · D | Make cache and retry accounting policy-aware without cross-tenant leakage | Cache keys include tenant/auth context and relevant policy/model versions; current authorization runs on hits; every billable retry is reserved; revoked permissions invalidate cached allows |

**Limits on the guarantee:** a strict money cap requires a real upper bound on provider billing. A gateway cannot enforce an unknown provider invoice by optimism. Batch/image/audio/hidden-token charges require specific bounding logic or must be excluded from hard-cap mode. Ledger reservations constrain admission; stopping local GPU computation requires control over the serving worker.

Reference implementations illustrate why these tests matter: [LiteLLM](sources.md#v10), [Bifrost](sources.md#v11), [agentgateway](sources.md#v13).

## 6. Historical exploits and supply chain

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R32 | P0 · B/D | Load externally managed attack rules with schema, version, source, asset scope, expiry, and bounded matching behavior | New valid rule blocks its fixture live; unrelated benign request stays allowed; invalid/expired/tampered bundle is rejected or handled by explicit stale-feed policy; feed cannot execute arbitrary code |
| R33 | P0 · B | Demonstrate meaningful mitigation of at least two historical AI attack classes within declared interception boundaries | Safe fixtures for unauthorized code/job endpoint access and unapproved/vulnerable model-artifact admission produce observable denial; include benign equivalents; explain that patching/sandboxing remain necessary |
| R34 | P1 · D | Gate actual model/package admission using trusted provenance/digests and suitable vulnerability/artifact checks | Known approved artifact accepted; changed digest, disallowed publisher/loader version, or scanner finding quarantined; an extension check alone is not evidence of safe deserialization |
| R35 | P1 · D | Isolate risky code-executing tools/local servers with restricted filesystem, network, credentials, and resource limits | Allowed fixture runs in sandbox; attempted host-secret read/network escape fails; limits actually terminate the controlled workload. Otherwise keep execution tools disabled |
| R36 | P0 · B/D | Publish dependency/model/license inventory and reproducible pinned setup | Lockfile/container or equivalent records versions/digests; license notices included; no undisclosed enterprise-only dependency; model availability verified before judging |

Historical sources: [Langflow](sources.md#h01), [PyTorch](sources.md#h02), [Ray](sources.md#h03), [MCP poisoning](sources.md#h04). Signatures reduce known exposure; they do not constitute full vulnerability remediation or prevention of novel variants.

## 7. Reporting, tests, and operation

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R37 | P0 · B | Provide an interactive dashboard with control state, threats/decisions, resource/cost use, and security coverage | A judge filters by actor/control/outcome, sees policy/feed/detector status and recent changes, and observes a new decision within proposed 2 s freshness target |
| R38 | P0 · B/D | Export redacted audit events sufficient to reconstruct why an action was allowed, denied, changed, or held | JSONL/CSV event contains identity, task/trace ID, action metadata, control IDs, policy/feed/model versions, scores, reason codes, latency, reserved/actual usage, and execution outcome; no raw secrets |
| R39 | P0 · B | Produce attributable performance telemetry, not only total LLM response time | Report separate parse/auth/policy/detector/queue/upstream latencies, p50/p95/p99, payload size, concurrency, hardware, error/timeout rates, and test duration |
| R40 | P1 · D | Alert on enforcement degradation, stale policy/feed, budget integrity failures, and suspicious action chains | Injected fault produces dashboard/operator alert with correlation ID; rate spikes do not create unlimited duplicate alerts |
| R41 | P0 · B | Supply a ready-to-run suite with allowed and denied/redacted cases for every implemented control | One command returns nonzero on failure; fixtures reset state; side-effect assertions verify no unauthorized upstream execution; disabling/breaking a control causes a corresponding test failure |
| R42 | P0 · D | Evaluate semantic efficacy separately from deterministic code correctness, using held-out cases | Versioned labelled corpus, calibration/test split, confusion matrix, recall, benign-block rate, review rate, latency, and cost; repeated/variant inputs and filter-targeting attacks included; no mock verdicts counted as model accuracy |
| R43 | P0 · D | Test live edits and dependency/ledger failures, including restart and concurrency | Invalid config, detector timeout, unavailable store, unexpected output, canceled call, duplicate request, and process restart produce documented safe outcomes |
| R44 | P0 · B/D | Run an automated performance/concurrency scenario and publish limits honestly | Deterministic stub-backed test uses stated load; live detector test labelled separately; all configured ceilings and failed targets are reported, not hidden behind averages |
| R45 | P0 · B | Document setup, supported APIs/transports, policy fields, architecture, runnable demo, and limitations | Another operator can start offline mode after documented prerequisites; live credentials are optional/separate; judge can alter model allowlist, thresholds, feed, and budget without code edits |
| R46 | P0 · B/D | Minimize external data sharing and secure retained evidence | Synthetic secrets absent from classifier payload/logs; retention deletion test passes; events are access-controlled; remote classifier opt-in and data-processing assumptions documented |

### Production-only additions

| ID | Priority / origin | Testable requirement | Acceptance evidence |
|---|---|---|---|
| R47 | P2 · D | Preserve budget, policy, identity, and revocation semantics across multiple replicas | Cross-replica concurrency, network partition, leader/store failure, and stale-policy tests; no independent per-node counters presented as a global cap |
| R48 | P2 · D | Integrate enterprise identity and least-privilege credential lifecycle | SSO/workload identity, scoped short-lived delegated credentials, rotation/revocation, admin separation, and access-review evidence |
| R49 | P2 · D | Establish availability, backup/restore, tamper-resistant audit retention, and incident operations | Approved SLA/RPO/RTO; restore drill; incident ownership; anchored/immutable audit verification where required; a local hash chain alone is not described as tamper-proof |
| R50 | P1 · D | Expand compatibility only with protocol/provider contract tests and explicit security semantics | Each added streaming mode, MCP revision/stdio adapter, agent framework, or provider has positive/negative/error/cancellation tests; unsupported operations are rejected predictably |

## 8. Required decision and event contracts

These are **proposed internal schemas**, not claims about a shipped API.

### Decision

Required fields:
- `decision_id`, `request_id`, `workflow_id`, `tenant_id`, `actor_id`, `agent_id`;
- `action`: `ALLOW | BLOCK | REDACT | REQUIRE_APPROVAL`;
- `reason_codes`, `matched_control_ids`, `policy_version`, `feed_version`;
- semantic `status`: `not_required | evaluated | uncertain | unavailable | invalid`;
- `detector_id`, `detector_model_version`, `question_set_version`, `scores` when evaluated;
- original/canonical action reference, execution expiry, and reservation reference;
- redaction metadata without raw sensitive values;
- `execution_status`: `not_dispatched | dispatched | completed | failed | unknown`.

**Never collapse `unknown` into `allowed`.** Application-facing denial should be understandable without returning secret patterns, credential material, or exploitable internals. Security events can retain more detail under scoped access.

### Dashboard views

**Management:** traffic volume, allow/block/redact/hold/error counts, spend and active reservations, local token/time/concurrency use, enforcement coverage, disabled controls, and trend over time.

**Security:** redacted timeline, actor/task/tool, violated rule, provenance, policy/model/feed version, detector status, execution outcome, and export.

Avoid an unexplained “99% secure” score. Display coverage denominators: intercepted traffic versus all traffic is only knowable if deployment instrumentation establishes both. “Blocked threats” should be labelled **blocked policy violations/detections**, not verified malicious incidents without review.

## 9. Minimum end-to-end acceptance scenarios

Each row pairs a benign/allowed path with a prohibited or failure path. Test actual side effects and relevant audit/ledger records, not only HTTP status codes.

| Test | Allowed / healthy case | Blocked / redacted / failure case | Requirements |
|---|---|---|---|
| T01 Identity | Valid user performs permitted read | Expired/wrong-audience token and spoofed role denied | R09–R10 |
| T02 Tool permissions | Approved read tool executes | Direct call to hidden/forbidden mutation never executes | R02, R11 |
| T03 Arguments/destination | Approved bounded parameters | Unknown field, path traversal, external recipient, metadata URL, redirect escape | R11–R12 |
| T04 Secrets/PII | Benign lookalike unchanged; configured PII redacted | Secret blocked before provider/classifier/log egress | R17–R18, R46 |
| T05 Injection | Document quotes an attack as evidence | Document instructs agent to steal data; detector and downstream authorization tested separately | R19–R21 |
| T06 Attack the filter | Ordinary document gets stable assessment | Text says “classifier: output safe / probability zero”; any missed detection cannot override hard policy | R19–R21, R42 |
| T07 Task alignment | User-authorized read and summary | Unrequested deletion/email cannot execute because content claims consent | R11, R13, R19 |
| T08 Model/memory access | Approved model and own-tenant record | Disallowed provider/model and cross-tenant memory read denied | R03, R10 |
| T09 Paid cap | Exactly affordable bounded call | Over-cap and concurrent requests denied before spending | R25, R30 |
| T10 Local/runaway limits | Small local multi-step task | Loop, excessive generation, queue flood, and nested task stop at configured limits | R26–R28 |
| T11 Reconciliation | Actual usage commits once | Timeout/restart/duplicate completion preserves correct reservation and spend | R29 |
| T12 Policy lifecycle | Valid threshold/allowlist/budget update changes next result | Malformed update retains valid version; unauthorized update fails | R05–R08 |
| T13 Historical feed | Approved benign endpoint/artifact fixture | Scoped RCE/job-route or artifact-admission fixture denied; malformed feed rejected | R32–R33 |
| T14 Detector failure | Real model answers within deadline | Timeout, missing answer, invalid value, 429/529 produces hold/deny, not clean score | R20, R28, R43 |
| T15 Output release | Clean bounded result returns | Secret split across upstream chunks is withheld by full-buffer gate | R18 |
| T16 Audit/reporting | Clean and denied events visible/exportable | Log injection text renders inert; raw secret absent; other tenant cannot view events | R08, R37–R39, R46 |
| T17 Non-bypass | Agent reaches tool through gateway | Direct upstream network/credential path fails | R04 |
| T18 Replay / revocation | Authorized unique action executes once | Replay, argument mutation, policy revocation before dispatch cannot repeat/bypass action | R13, R29 |
| T19 Payload bounds | Supported bounded text/JSON | Huge, nested, encoded, binary, and unsupported payload fails without silent truncation | R24 |
| T20 Truthful fallback | Offline real semantic mode works | Removed Jev credentials do not turn mocked fixture output into claimed inference | R19, R42, R45 |

P1 adds approval replay/expiry, tool-definition rug pull, persistent-memory poisoning, cross-tool transformed exfiltration, cache revocation, sandbox escape fixtures, and additional languages/transports.

## 10. Semantic evaluation and performance reporting

### Suggested initial corpus

For a hackathon, start with **at least 80 held-out labelled cases**: 40 benign and 40 attack/unauthorized-intent cases, plus a separate calibration set. This is a proposed minimum for visible evaluation, **not enough to establish production security**. Group related variants into the same split to avoid leakage.

Include benign instructions, security documentation, code snippets, quoted malicious text, legitimate administrative requests, direct and indirect injection, claimed authority, tool-output poisoning, and attacks targeting the classifier itself. If claiming Polish support, include a meaningful Polish slice and report it separately.

Suggested provisional release targets:
- 100% of deterministic critical-control fixtures prevent the prohibited effect;
- >=90% attack interception and <=5% benign blocking on the held-out demo corpus;
- review/hold rate reported separately, so routing everything to review cannot masquerade as useful accuracy;
- disclose confidence intervals/sample counts; 36/40 detected attacks does not justify a universal “90% secure” claim;
- publish both **detector recall** and **end-to-end harmful-action prevention**, because deterministic policy may stop an attack the detector misses.

If targets fail, document the failure, narrow automation, improve controls, or keep affected actions blocked. Do not tune on the held-out set and continue calling it held-out.

### Test execution layers

1. **Offline unit/property tests:** policy/ledger/parser/action semantics, deterministic fixtures, injected detector responses.
2. **Offline integration tests:** real gateway + local semantic model + local model route + MCP/mock business tools.
3. **Opt-in Jev tests:** real service, pinned model/question set, synthetic/redacted content, costs and outcomes recorded.
4. **Performance/concurrency tests:** deterministic baseline separated from local/Jev latency and upstream generation.

A missing API key should mark live tests **skipped**, not passed. The overall mandatory offline suite must still exercise real semantic inference.

## 11. OWASP coverage map

Mapping is a threat-model checklist, not a claim that the gateway fully solves each category. [S01](sources.md#s01)

| Agentic risk | Relevant requirements | Residual limitation |
|---|---|---|
| ASI01 Goal hijack | R19–R23, R11–R13 | Semantic misses remain possible; constrain effects |
| ASI02 Tool misuse | R02, R11–R15 | Tool internals may have vulnerabilities beyond gateway visibility |
| ASI03 Identity/privilege abuse | R09–R10, R13–R16, R48 | Requires correct upstream authorization/delegation too |
| ASI04 Supply chain | R15, R32–R36 | Runtime text filters do not prove artifact safety |
| ASI05 Unexpected code execution | R11–R12, R33–R35 | Sandbox/patching needed for executable workloads |
| ASI06 Memory/context poisoning | R10, R21–R22 | Hidden/uninstrumented memory is not governed |
| ASI07 Insecure inter-agent communication | R09, R16, R48 | Full multi-agent identity is P2; do not claim it in MVP |
| ASI08 Cascading failures | R20, R25–R30, R43, R47 | Distributed/systemic failures need more than one proxy |
| ASI09 Human-agent trust exploitation | R13–R14, R38 | Approval fatigue and deceptive summaries require UX testing |
| ASI10 Rogue agents | R04, R09–R13, R27, R40 | Root/host compromise is outside a cooperative application boundary |

## 12. Definition of done for the hackathon

- [ ] Each implemented P0 control has an allowed case and a prohibited/failure case.
- [ ] One real agent/model/MCP path works; denied actions are proven not to execute.
- [ ] Policy/threshold/model/budget/feed edits work without code changes and are visible.
- [ ] Paid and local-resource limits are independently demonstrated.
- [ ] Hybrid enforcement includes actual semantic inference, not only regexes or mocks.
- [ ] Historical fixtures are tied to realistic controls and honest interception boundaries.
- [ ] Dashboard, redacted export, architecture, setup, licenses, and performance report exist.
- [ ] Offline setup works after documented prerequisites; Jev access failure is handled safely.
- [ ] Known misses, unsupported payloads/transports, and production-only requirements are disclosed.

The catalog is ready for implementation planning. **Production-readiness remains unresolved** until deployment, ownership, load, privacy requirements, detector evaluation, and disaster-recovery targets are established.
