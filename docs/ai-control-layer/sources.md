# Research sources

Research date: **2026-10-03**. Product capabilities, pricing, model versions, and licensing can change. These are primary sources unless noted. Product documentation establishes what a vendor documents, not independently measured security effectiveness. No vendor product or Jev API was benchmarked during this research.

## B0

**Hackathon brief — AI Control Layer**, 4 pages. This is the repository's primary challenge
source and is authoritative over the derived proposals and requirements:
[`CRITERIA AI Control Layer.pdf`](../../project-spec/CRITERIA%20AI%20Control%20Layer.pdf).

Read in full. Authority for deliverables, formal requirements, setup constraints, judge interaction, and evaluation weights.

## S01

**OWASP Top 10 for Agentic Applications 2026**:
- [Project publication](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)
- [Full report](https://genai.owasp.org/download/52117)

Risk taxonomy, not a certification or proof of coverage. Categories: ASI01 goal hijack; ASI02 tool misuse; ASI03 identity/privilege abuse; ASI04 supply chain; ASI05 unexpected code execution; ASI06 memory/context poisoning; ASI07 insecure inter-agent communication; ASI08 cascading failures; ASI09 human-agent trust exploitation; ASI10 rogue agents. Report text was available through search; direct PDF extraction was incomplete.

## S02

**OWASP MCP Security Cheat Sheet**:
https://cheatsheetseries.owasp.org/cheatsheets/MCP_Security_Cheat_Sheet.html

Directly read. Supports tool-definition pinning, parameter validation, least privilege, pre-execution approval, output inspection, SSRF prevention, server isolation, supply-chain review, and redacted audit trails. Recommendations are defense in depth; stripping suspicious tags alone is not injection prevention.

## S03

**MCP specification — Security Best Practices**:
https://modelcontextprotocol.io/specification/latest/basic/security_best_practices

Directly read relevant sections. Supports audience validation, prohibition on token passthrough, confused-deputy defenses, SSRF controls, and principal-bound state handles. The current page references revision 2026-07-28 and distinguishes its stateless transport from legacy protocol sessions. Implement against an explicitly pinned revision, not a moving `latest` URL.

## S04

**OWASP Top 10 for LLM Applications**:
https://genai.owasp.org/llm-top-10/

Complementary application risks: prompt injection, sensitive-information disclosure, supply chain, poisoning, improper output handling, excessive agency, system-prompt leakage, vector/embedding weaknesses, misinformation, and unbounded consumption. A gateway cannot fully address all of these by itself.

## V01

**Zenity — Coding and Personal Agents**:
https://zenity.io/use-cases/agent-type/coding-personal-agents

Directly read. Documents native hooks, MCP gateway, execution-path context, deterministic access boundaries, and blocking/modifying tool calls before execution. Marketing effectiveness claims were not independently validated.

## V02

**Noma — Agent Access Control**:
https://noma.security/products/agent-access-control

Directly read. Documents agent/MCP/skill registry, approval status, user-based and tool-based policies, runtime behavior monitoring, and enforcement through gateways/hooks and other infrastructure.

## V03

**Lasso — Agentic AI Risk Management and MCP Gateway**:
- [Product](https://www.lasso.security/use-cases/agentic-ai-risk-management)
- [Gateway repository](https://github.com/lasso-security/mcp-gateway)
- [README](https://raw.githubusercontent.com/lasso-security/mcp-gateway/main/README.md)
- [MIT license](https://raw.githubusercontent.com/lasso-security/mcp-gateway/main/LICENSE)

Directly read product passages and repository README/license. Gateway has a plugin architecture; advanced Lasso guardrails require a Lasso API key. Do not equate the MIT gateway with free access to every commercial detector. Vendor detection/latency percentages are not used as comparative evidence.

## V04

**Snyk / Invariant**:
- [Invariant Guardrails README](https://raw.githubusercontent.com/invariantlabs-ai/invariant/main/README.md)
- [Apache-2.0 license](https://raw.githubusercontent.com/invariantlabs-ai/invariant/main/LICENSE)
- [Snyk acquisition announcement, 2025-06-24](https://snyk.io/news/snyk-acquires-invariant-labs-to-accelerate-agentic-ai-security-innovation/)

Directly read repository material; announcement checked through search. Documents contextual rules over traces/tool sequences, local policy analysis, and LLM/MCP proxy integration. Original marketing page extraction failed; repository evidence was used instead.

## V05

**Check Point AI Guardrails / Lakera**:
- [API overview](https://docs.lakera.ai/docs/api)
- [Prompt Defense](https://docs.lakera.ai/docs/prompt-defense)
- [Data Leakage Prevention](https://docs.lakera.ai/docs/data-leakage-prevention)

API overview directly read; detector pages checked through search. Current API screens messages, tool descriptions/results/calls, data leakage, and actions outside an agent mandate. Documents SaaS and self-hosted operation, regional endpoints, and policy administration. Integration must still enforce the returned decision.

## V06

**Cisco AI Defense**:
- [Inspection API](https://developer.cisco.com/docs/ai-defense-inspection/)
- [Management API](https://developer.cisco.com/docs/ai-defense-management/introduction/)
- [Data sheet](https://www.cisco.com/c/en/us/products/collateral/security/ai-defense/ai-defense-ds.html)

Inspection API directly read; remaining material checked through search. Explicitly separates inspection from application enforcement and control-plane management. Agent/MCP capabilities are documented in the broader product material; do not assume every feature is in the basic inspection endpoint.

## V07

**Palo Alto Networks — Prisma AIRS runtime and model security**:
- [Runtime overview](https://docs.paloaltonetworks.com/prisma-airs/ai-runtime-security/airs-vm-firewall/ai-runtime-security-overview)
- [Model security](https://docs.paloaltonetworks.com/prisma-airs/ai-supply-chain-security/ai-supply-chain-security/model-security-to-secure-your-ai-models)
- [Protect AI acquisition completion, 2025-07-22](https://www.paloaltonetworks.com/company/press/2025/palo-alto-networks-completes-acquisition-of-protect-ai)

Runtime overview fetched; remaining pages checked through search. Distinguishes runtime traffic protection from pre-deployment artifact scanning, including deserialization risks.

## V08

**Portkey / Prisma AIRS AI Gateway**:
- [Guardrails](https://portkey.ai/features/guardrails)
- [Virtual keys](https://portkey.ai/docs/product/ai-gateway/virtual-keys)
- [Access governance](https://portkey.ai/for/manage-access-for-ai-models-and-providers)
- [Gateway configuration](https://github.com/Portkey-AI/gateway/blob/main/cookbook/getting-started/writing-your-first-gateway-config.md)
- [MIT gateway license](https://raw.githubusercontent.com/Portkey-AI/gateway/main/LICENSE)
- [Palo Alto acquisition completion](https://www.paloaltonetworks.com/company/press/2026/palo-alto-networks-completes-acquisition-of-portkey-to-secure-ai-agents)

Guardrails, virtual keys, license, and acquisition announcement fetched; other pages checked through search. Portkey is not treated as an independent company from Palo Alto in this report. OSS gateway licensing does not establish entitlement to every hosted/enterprise governance feature. Acquisition announcement includes forward-looking integration statements; those are not treated as shipped features.

## V09

**SentinelOne — Prompt Security**:
https://www.sentinelone.com/platform/securing-ai-prompt/

Directly read. Documents AI usage discovery, agent/MCP inventory, least-privilege access, prompt/data protection, and searchable action logs. Broader employee/endpoint coverage is relevant to bypass prevention, but exceeds the recommended hackathon scope.

## V10

**BerriAI — LiteLLM**:
- [Budgets and rate limits](https://docs.litellm.ai/docs/proxy/users)
- [Guardrail policies](https://docs.litellm.ai/docs/proxy/guardrails/guardrail_policies)
- [Repository license](https://raw.githubusercontent.com/BerriAI/litellm/main/LICENSE)

Directly read key sections. Current budget documentation requires a database; DB-less global budget enforcement can fail open. Documents pre-call reservations, unpriced/batch limitations, Redis/database failure considerations, and `fail_closed_budget_enforcement`. License is MIT outside the separately licensed enterprise directory. Validate the pinned release: these docs change rapidly.

## V11

**Maxim — Bifrost**:
- [Virtual keys](https://docs.getbifrost.ai/features/governance)
- [Budgets and limits](https://docs.getbifrost.ai/features/governance/budget-and-limits)
- [Overview](https://github.com/maximhq/bifrost/blob/dev/docs/overview.mdx)
- [Apache-2.0 license](https://raw.githubusercontent.com/maximhq/bifrost/main/LICENSE)

Directly read governance and budget sections/license. Documents key/team/customer/provider controls. Virtual-key governance is optional unless explicitly made mandatory. Reviewed pages do not establish a strict atomic pre-reservation guarantee. Enterprise feature boundaries must be checked against the chosen release and commercial offering.

## V12

**CrowdStrike / Pangea — AI Guard**:
- [API guide](https://pangea.cloud/docs/ai-guard/apis)
- [Recipes](https://pangea.cloud/docs/ai-guard/recipes)
- [MCP proxy walkthrough](https://pangea.cloud/blog/secure-mcp-servers-with-ai-guardrails/)

Primary documentation checked through search; not directly read in full. Documents configurable input/output/agent-plan recipes and an MCP proxy backed by the AI Guard service. Use as an additional comparable, not as an independently validated recommendation.

## V13

**agentgateway — open-source project, not a separate company in this comparison**:
- [MCP authorization example](https://raw.githubusercontent.com/agentgateway/agentgateway/main/examples/mcp-authorization/README.md)
- [Budget documentation](https://agentgateway.dev/docs/standalone/latest/documentation/llm/cost-controls/budget-limits/)
- [Apache-2.0 license](https://raw.githubusercontent.com/agentgateway/agentgateway/main/LICENSE)

Directly read. JWT/CEL authorization filters discovery and rejects unauthorized tool calls. Budgets are charged after responses; the crossing request completes, missing usage can go uncharged, and counters are periodically persisted. Excellent enforcement reference, but documented budgets are not a strict no-overspend guarantee.

## J01

**TypeSafe — Introducing System One Models & Jev**:
https://typesafe.ai/blog/introducing-system-one-models-and-jev

Read in full. Vendor claims 70–500 ms end-to-end latency and $0.042/million input tokens with free outputs. Workflow comparisons are vendor-run, use model-reference probabilities, and are not prompt-injection efficacy benchmarks. Its “cannot hallucinate” framing concerns constrained/schema-valid outputs, not immunity to wrong classifications.

## J02

**TypeSafe — Models**:
https://docs.typesafe.ai/models

Read in full. At access time: `jev-1.13.0`; 80 requests/s; 100,000 input tokens/s; 64k total request tokens; 32k state plus longest question; text only; English strongest. Aliases can move. These directly inspected limits supersede search results that incorrectly reported no published limits.

## J03

**TypeSafe — API, primitives, SDK**:
- [API reference](https://docs.typesafe.ai/api)
- [Quickstart](https://docs.typesafe.ai/introduction/quickstart)
- [Noul](https://docs.typesafe.ai/primitives/noul)
- [Choice](https://docs.typesafe.ai/primitives/choice)
- [Python SDK](https://docs.typesafe.ai/sdk/python)

Read API/quickstart/Noul documentation and fetched the other pages. Supports `state`, `model`, `questions`, `answers`; Noul probabilities; Choice distributions; Score distributions; 401/422/429/529 handling. Question identifiers are not sent to the model: instructions must contain the actual meaning.

## J04

**TypeSafe — Confidence**:
https://docs.typesafe.ai/confidence

Read in full. Noul has no separate confidence field. Choice confidence is derived from the largest option probability and option count, not an independent probability that the classifier is correct. Thresholds require task-specific evaluation.

## J05

**TypeSafe — Jev 1.13 jaggedness**, last reviewed by vendor **2026-10-02**:
https://docs.typesafe.ai/model-jaggedness/jev-1.13.md

Read in full. Critical passage: “State is data, and `jev-1.13` does not treat it as hostile by default.” Injected instructions/misleading framing can move answers. Also documents numeric/date weaknesses, irrelevant-context degradation, option-order sensitivity, and lack of text generation. This is central to the security recommendation.

## J06

**TypeSafe — Data handling and commercial terms**:
- [Legal documentation](https://docs.typesafe.ai/legal.md)
- [Privacy policy](https://typesafe.ai/legal/privacy-policy)
- [Master customer agreement](https://typesafe.ai/legal/mca)
- [DPA](https://typesafe.ai/legal/data-processing)

Legal documentation and relevant privacy/MCA sections directly read; DPA checked through search. No-training commitments do not imply default zero retention. Enterprise ZDR is offered separately. Review contractual rights around telemetry/abuse processing, retention, subprocessors, and security testing before production use. This report is not legal advice.

## J07

**TypeSafe — Workflow evaluations**:
- [Evaluation index](https://evals.typesafe.ai/)
- [Agent Trace Observability](https://evals.typesafe.ai/agent_trace_observability)
- [Security Incidents](https://evals.typesafe.ai/security_incidents)

Index fetched, individual tasks checked through search. Relevant to structured decisions, but no dedicated independent adversarial prompt-injection benchmark was found in the reviewed material. This is a bounded research finding, not a claim that none exists anywhere.

## H01

**Langflow CVE-2025-3248**:
- [Maintainer advisory](https://github.com/langflow-ai/langflow/security/advisories/GHSA-rvqx-wpfh-mfx7)
- [GitHub advisory](https://github.com/advisories/GHSA-rvqx-wpfh-mfx7)

Primary advisory checked through search; direct extraction omitted the useful advisory body. Unauthenticated code execution through `/api/v1/validate/code`; affected Langflow before 1.3.0. Historical minimum fixed version is not a recommendation to deploy that old version today.

## H02

**PyTorch CVE-2025-32434**:
https://github.com/pytorch/pytorch/security/advisories/GHSA-53q9-r3pm-6pq6

Advisory fetched. `torch.load(weights_only=True)` RCE; affected <=2.5.1, historical fix 2.6.0. Supports artifact admission and patching requirements, not a claim that a prompt filter fixes deserialization.

## H03

**Ray CVE-2023-48022 / ShadowRay**:
- [Anyscale position and mitigation](https://www.anyscale.com/blog/update-on-ray-cve-2023-48022-new-verification-tooling-available)
- [Oligo campaign research](https://www.oligo.security/blog/shadowray-attack-ai-workloads-actively-exploited-in-the-wild)

Anyscale page fetched; Oligo checked through search. Exposed job-submission infrastructure allows untrusted job execution. Vendor characterizes this as a deployment/security-boundary issue; preserve that nuance.

## H04

**MCP tool poisoning research**:
- [Invariant disclosure](https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks)
- [OWASP tool-poisoning explanation](https://community.owasp.org/attacks/MCP_Tool_Poisoning)

Checked through search and corroborated by S02. Research demonstration of malicious tool metadata steering another tool, not evidence of a WhatsApp encryption break or a claim of a production breach.

## O01

**Microsoft Presidio**:
- [Project](https://github.com/microsoft/presidio)
- [MIT license](https://raw.githubusercontent.com/microsoft/presidio/main/LICENSE)

License fetched; PII capability also referenced by the directly read Lasso and LiteLLM documentation. Recognizers need domain/language tuning; a scanner does not guarantee discovery of all sensitive data.

## O02

**ModelScan**:
- [Project](https://github.com/protectai/modelscan)
- [Apache-2.0 license](https://raw.githubusercontent.com/protectai/modelscan/main/LICENSE)

License fetched. Candidate artifact-scanning component, not an endorsement of current maintenance, complete format coverage, or protection against all malicious models. Verify those before adoption.

## O03

**Protect AI DeBERTa prompt-injection detector**:
- [Model card](https://huggingface.co/protectai/deberta-v3-base-prompt-injection-v2/raw/main/README.md)
- [Configuration](https://huggingface.co/protectai/deberta-v3-base-prompt-injection-v2/blob/main/config.json)

Model card read in full. Apache-2.0; English-specific; example maximum length 512; explicitly excludes jailbreak/non-English coverage and warns about false positives on system prompts. **The current card says the project and associated LLM Guard code are archived and unmaintained.** Suitable only as a disclosed historical baseline, not a recommended production dependency.
