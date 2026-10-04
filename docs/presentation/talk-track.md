# LLMinator · talk track (about 5 minutes, English)

1. **Title (15 s).** We are Team Corsbusters. LLMinator is the security gateway between your AI agents and everything they could break. It is live on a Raspberry Pi right now.
2. **Problem (30 s).** AI reads language as code, so a sentence can rewrite an agent's rules. PII and keys leak, loops burn tokens, and the AI stack itself is a target: Ollama alone has 16 published CVEs.
3. **Architecture (40 s).** One gateway for every caller: the web chat, Codex CLI and agents. Each request passes identity and budget checks, two guard lanes (deterministic and AI), the model, output guards, and ends in a verdict plus an audit record. A versioned policy steers every step.
4. **Hybrid defense (35 s).** Rules cost microseconds: a secrets scan with 222 rules takes 0.03 ms. The AI classifier is used only where meaning matters: on the public deepset test it catches 95% of explicit injection attempts with no false alarms on its benign prompts. and repeat verdicts come from cache in tens of milliseconds. If the AI layer is down, we fail closed. The models in this demo are test-grade; a stronger one drops in with a config change and a recalibrated threshold.
5. **X-ray (35 s).** Every decision shows its path, score vs. threshold and the time of each check, without raw PII. Here the PESEL prompt was blocked in 20 ms and zero tokens reached the model.
6. **Policy (30 s).** Policy is data: versioned, live from the next request, no restart. Each control has a mode: off, monitor, redact, block. A broken policy never goes live.
7. **Budget and reporting (35 s).** Daily token budgets return 429 before the model is touched. Every request, allow or deny, goes into a hash-chained audit log, so tampering is detectable. The dashboard shows blocks, redactions and p50/p95.
8. **Known exploits and a real agent (40 s).** A threat feed from OSV.dev, approved by a human and regression-tested, hot-reloads in about a second. Here Codex CLI, set up on a local stack, tried to read customer records and was blocked at input.
9. **Proof (30 s).** Real requests against the live gateway: allow, redact, block. 118 Cucumber scenarios pass in 49 seconds, positive and negative, with CI on every push.
10. **Why we win (30 s).** It maps to every judging criterion. Try to break it at llminator.fmroz.me; demo accounts are on the slide.

## If asked
- **What are the weaknesses?** The semantic classifier is weaker on role-play jailbreaks and on instructions hidden inside long documents; we measured this on public datasets and tuned the threshold for few false positives. It covers English only. Deterministic rules do not depend on it.
- **Why is p95 high on the dashboard?** The model runs on a Raspberry Pi and Codex sends very large prompts. Blocks do not touch the model, so they are fast.
- **Is Codex running on the Pi?** No, we set it up and tested it on a local stack. The Pi runs the gateway, database, sidecar and Ollama.
- **Where is the Red Team Arena?** It is next on the roadmap: replaying an attack corpus against unprotected vs. protected traffic.
- **Can I swap the AI model?** Yes. The semantic guard sits behind a Java interface; the sidecar is one implementation.
