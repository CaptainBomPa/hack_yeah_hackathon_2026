# 4. Testing

## The core pattern: same prompt, live policy edit, different verdict

This is the centerpiece of the self-testing suite and maps directly onto what the task asks for:
*"judges may modify the configuration files/feeds to understand how the control layer behaves
with new configuration — how changes are reflected, can they adjust in real-time."*

Every scenario in
[`live_policy_editing.feature`](../backend/src/test/resources/features/live_policy_editing.feature)
follows the same shape: send a prompt, read the verdict, have **the admin edit the live policy
through the real `PolicyStore`/`PolicyValidator`** (the same path the `/policies` UI uses — not a
restart, not a config file reload, not a mock), send the **exact same prompt again**, and assert
the verdict flipped. If policy edits only changed a config value without actually being re-read
per request, these would fail — they prove the hot-reload is real, end to end.

Two representative scenarios, verbatim:

```gherkin
Scenario: Disabling and re-enabling the PII guard flips the same prompt between redact and allow
  Given policy "chat" allows model "qwen-test"
  And user "chat1" with role "chat"
  And the model responds with "ok"
  When the user sends the prompt "My PESEL is 44051401359"
  Then the response action is "redact"
  When the admin disables guard "PII-RECOGNIZERS"
  And the user sends the same prompt again
  Then the response action is "allow"
  When the admin enables guard "PII-RECOGNIZERS"
  And the user sends the same prompt again
  Then the response action is "redact"

Scenario: Lowering then raising a role's daily budget mid-session blocks and then unblocks it
  Given policy "chat" allows model "qwen-test"
  And user "chat1" with role "chat"
  And the model responds with "ok"
  And role "chat" has a daily budget limit of 20000 tokens
  When the user sends the prompt "Hi"
  Then the response action is "allow"
  When the admin changes role "chat"'s daily budget to 1 tokens
  And the user sends the same prompt again
  Then the response action is "block"
  And it is blocked by "budget.daily_cap"
  And the HTTP status is 429
  When the admin changes role "chat"'s daily budget to 20000 tokens
  And the user sends the same prompt again
  Then the response action is "allow"
```

The full file has **12 of these**, each isolating one knob a judge could plausibly turn in the
Policies screen:

| What the admin changes live | Same prompt, before | After |
|---|---|---|
| Removes a role's access to a model | `allow` | `block` (`policy.model-access`) |
| Grants a role access to a model | `block` (`policy.model-access`) | `allow` |
| Disables a model in the catalog (role still lists it) | `allow` | `block` (`model.allowlist`) |
| Disables the PII guard → re-enables it | `redact` → `allow` | `allow` → `redact` |
| Lowers the PII detection threshold | `allow` (a bare phone number) | `redact` |
| Lowers the semantic block threshold | `allow` | `block` (`SEM-001`) |
| Switches the semantic guard to fail-open during a sidecar outage | `block` (`SEM-001`) | `allow` |
| Lowers, then raises, a role's daily token budget | `allow` → `block` (429) | `block` → `allow` |
| Lowers the max input size | `allow` | `block` (`budget.input_limit`, 413) |
| Adds a PII recognizer to the guard's block list | `redact` (a card number) | `block` (`PII-RECOGNIZERS`) |
| Disables one PII recognizer (others stay active) | `redact` | `allow` |
| Attempts an **invalid** edit (removes the required `admin` role) | `allow` | edit **rejected**; still `allow` — old policy keeps governing |

That last row matters as much as the flips: `PolicyValidator` rejects a broken edit before it can
ever go live, and the suite proves the *next* request is unaffected — a bad edit from a judge
can't take down the gateway.

The same "edit it live, resend, outcome changes" pattern is reused for guardrails outside this one
file too — e.g. `signature_feed.feature` proves a signature added to the SIG-FEED file blocks the
very next request with no restart, and that a corrupted feed file keeps the *last good* version
instead of failing open.

## The rest of the suite, briefly

Beyond the live-editing scenarios, the same Cucumber suite covers **positive and negative cases
per control** — every one of the 8 PII recognizers (valid redact/block vs. wrong checksum/missing
context → allow), secret detection (real AWS/GitHub/JWT/private-key fixtures), historical attack
signatures (real OSV CVEs and payload patterns from the deployed feed), the semantic guard's
boundary and failure-mode behavior, and budget/rate/model-allowlist edge cases — **118 Cucumber
scenarios** in total, all passing, run alongside the JUnit suite as `./gradlew test` (details and
how to run the extended real-model variant: `backend/README.md`).
