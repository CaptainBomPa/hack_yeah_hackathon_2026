# LLMinator for Codex CLI

Runs **OpenAI Codex CLI** through **LLMinator**, an AI control layer. Every prompt, every file
or command output the agent sends to the model, and every model answer goes through the
company's policies: PII and secret detection, model allowlist, token budgets. Each decision is
recorded in a tamper-evident audit log.

- Uses your existing **ChatGPT subscription login**. No OpenAI API key, no extra billing.
- Your Codex configuration and login files are **never modified**. Plain `codex` keeps working
  as before; only commands started through this launcher go through LLMinator.
- Gateway: `https://apillminator.fmroz.me/v1`, demo account `codex-agent`. Both are built in,
  so there is nothing to configure.

## Requirements

- Node.js 20 or newer: `node --version`
- Codex CLI: `npm install -g @openai/codex`
- A ChatGPT login in Codex, done once: `codex login`

## Quick start: the demo

```sh
node start-demo.mjs
```

This creates a fresh `~/llminator-demo` folder with a fictional `customers.csv` (fake PESEL
numbers and `example.com` emails) and opens Codex there. The folder is recreated on every start.
To use another location: `node start-demo.mjs --dir PATH`.

Try these prompts in order:

```
Show me the first three rows of customers.csv.
Write a Java function that validates a Polish PESEL number. Test it with 44051401359.
Read customers.csv and write a unit test that checks every PESEL in the file.
What does a typical customer email address on Gmail look like? Give me an example.
```

What you should see:

1. The agent reads the file on your machine, but the model gets `[REDACTED:PL_PESEL]` and
   `[REDACTED:EMAIL_ADDRESS]` instead of the data.
2. The PESEL typed in the prompt never leaves your machine unredacted. Depending on the policy,
   it is redacted or the request is blocked with a `PII-001/PL_PESEL` error.
3. The agent can still finish the task, for example by checking the data locally in a shell. The
   model never sees a single PESEL.
4. An email in the model's answer comes back as `[REDACTED:EMAIL_ADDRESS]`, and the conversation
   goes on.

Admins see every one of these decisions in the LLMinator panel (Audit log, Dashboard). Changes
made in Policies apply from the next request.

## Use it in your own project

```sh
cd path/to/your/project
node path/to/llminator-codex/control-layer.mjs run codex
node path/to/llminator-codex/control-layer.mjs run codex -- exec "Explain this repository"
```

Codex works in the folder you start it from.

## Optional: a saved profile

Use a saved profile for another gateway, another account or a pinned model:

```sh
node control-layer.mjs install codex [--gateway URL] [--user LOGIN] [--model TAG]
node control-layer.mjs status codex
node control-layer.mjs disable codex      # launcher uses your original Codex config
node control-layer.mjs enable codex
node control-layer.mjs uninstall codex    # back to the built-in defaults
```

- A saved profile takes precedence over the built-in defaults.
- A local gateway is `--gateway http://localhost:8000/v1`.
- For an account other than `codex-agent`, set the password for the session only:
  - PowerShell: `$env:CL_GATEWAY_PASSWORD = Read-Host -MaskInput`
  - bash: `read -rs CL_GATEWAY_PASSWORD; export CL_GATEWAY_PASSWORD`
- The profile file is in `%APPDATA%\ai-control-layer\codex.json` on Windows,
  `~/Library/Application Support/ai-control-layer/codex.json` on macOS, and
  `~/.config/ai-control-layer/codex.json` on Linux.

## Troubleshooting

| Message in Codex | Meaning |
|---|---|
| `A ChatGPT login is required` | run `codex login` (API-key login is not supported) |
| `401` from `.../v1/responses` | wrong gateway account or password (saved profile?) |
| `403 Account is not allowed to use the requested model` | the model is not allowed for the role in Policies |
| `413 Request exceeds the input token limit` | raise the input limit in Policies (Codex sends long instructions) |
| `400 Content blocked at input/output by ...` | a policy decision; the details are in the Audit log |
| `503 Security check unavailable` | the semantic check (SEM-001) timed out; raise `timeoutMs` in Policies |
| still going to the wrong address | check `node control-layer.mjs status codex`: a saved profile overrides the defaults |
