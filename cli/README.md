# Control Layer CLI

Node >=20, Windows/macOS/Linux. The Java gateway is the enforcement layer; this CLI launches
Codex with a gateway provider for one child process. It never edits Codex configuration,
login files, shell profiles, registry environment variables or PATH.

**Standalone package** (unzip anywhere, no repository needed): `node cli/pack.mjs` builds
`build/llminator-codex.zip` with the launcher, a demo starter, fictional demo data and short
instructions (source: [`package/`](package/)).

This adapter uses your **existing ChatGPT subscription login**, not OpenAI API-key billing.
Run `codex login` if needed. The launcher checks `codex login status`, requires ChatGPT auth,
keeps the original `CODEX_HOME` and lets Codex use/refresh its native file or keyring credentials.
It does not read, copy or restore `auth.json`. The backend forwards request-scoped OAuth to
`https://chatgpt.com/backend-api/codex`; it has no provider API key or API fallback.

From the repository root — that's all you need for the local demo:

```sh
codex login                    # once, if you are not logged in with ChatGPT yet
node cli/control-layer.mjs run codex
node cli/control-layer.mjs run codex -- exec "Explain this repository"
```

Without `install`, `run` uses the demo gateway account `codex-agent` / `codex-agent-123`
(hardcoded, same as `backend/config/users.yaml`) and the deployed gateway `https://apillminator.fmroz.me/v1`.
For a local stack: `node cli/control-layer.mjs install codex --gateway http://localhost:8000/v1`.

Optional — a stored profile with another account, a remote gateway or a pinned model:

```sh
node cli/control-layer.mjs install codex [--user LOGIN] [--gateway https://HOST/v1] [--model TAG]
node cli/control-layer.mjs status codex
node cli/control-layer.mjs disable codex
node cli/control-layer.mjs enable codex
node cli/control-layer.mjs uninstall codex
```

For an account other than the demo one, provide its password through the credential helper below
or a terminal-local `CL_GATEWAY_PASSWORD`. For development in PowerShell 7:

```powershell
$env:CL_GATEWAY_PASSWORD = Read-Host 'Gateway password' -MaskInput
node cli/control-layer.mjs run codex
```

On Linux/macOS, read it without echoing or putting a literal password in shell history:

```sh
printf 'Gateway password: '
stty -echo
IFS= read -r CL_GATEWAY_PASSWORD
stty echo
printf '\n'
export CL_GATEWAY_PASSWORD
node cli/control-layer.mjs run codex
unset CL_GATEWAY_PASSWORD
```

`install` stores only our routing profile: gateway, account, billing mode, an optional pinned model (`--model`; without it Codex keeps its default and `/model` works as usual) and an optional credential helper.
`run` applies provider options through Codex `-c`, with no persistent routing environment variables.
Normal `codex` always uses its original configuration. `disable` makes our launcher use that original
configuration as well. `uninstall` removes our own profile; user edits to Codex made after installation
are retained because no restore of old agent files is necessary. Repeating uninstall is safe.
Installation refuses to overwrite an existing profile, and uninstall refuses foreign files.
Writes are atomic and serialized by a lock file. If the installer is killed during a write,
inspect and remove its stale `codex.json.lock` before retrying; no agent config needs recovery.

Configuration location:

| OS | Profile |
|---|---|
| Windows | `%APPDATA%/ai-control-layer/codex.json` |
| macOS | `~/Library/Application Support/ai-control-layer/codex.json` |
| Linux | `$XDG_CONFIG_HOME/ai-control-layer/codex.json` or `~/.config/ai-control-layer/codex.json` |

For production, fetch the gateway password from a trusted password manager or OS credential helper.
`--credential-command` is a JSON array of executable and arguments. It runs without a shell,
must return the password on stdout and must contain no literal secret in its arguments. For example,
with an existing 1Password CLI entry (the command requires your normal 1Password sign-in):

```sh
node cli/control-layer.mjs install codex --user AGENT_LOGIN --credential-command '["op","read","op://Vault/Gateway/password"]'
```

Shell quoting differs; in PowerShell pass the JSON as a single-quoted string as shown above.
For local development, `CL_GATEWAY_PASSWORD` may instead exist in the launching process's environment.
The repository also provides `cli/gateway-password.mjs`, a local helper that reads
`CL_GATEWAY_PASSWORD` from the ignored root `.env.local`, regardless of the working directory.
Configure `credentialCommand` as `["/absolute/path/to/node", "/absolute/path/to/cli/gateway-password.mjs"]`.
With that profile, `node cli/control-layer.mjs run codex` needs no manual password export.
The helper writes the secret to stdout for the launcher; do not run it directly in a terminal.
The CLI does not set it globally, does not persist it and removes the raw password, `OPENAI_API_KEY`,
`CODEX_API_KEY` and `OPENAI_BASE_URL` from the enabled agent's environment. Only the derived gateway
header is passed to the child through `CL_CODEX_AUTH`. Codex's `Authorization` remains OAuth;
the separate `X-Control-Layer-Authorization` header authenticates the gateway. The gateway strips
its own credential and cookies before forwarding the allowlisted native headers to ChatGPT.
The parent environment remains untouched; the header disappears when the child exits. The gateway
must use HTTPS outside loopback. Basic is the current gateway auth; future per-agent revocable tokens
can replace it without changing the install/uninstall lifecycle.

An optional local command alias can be installed with `npm link` from `cli/` (or package tooling).
Then use `control-layer` instead of `node cli/control-layer.mjs`. To remove the linked executable,
run `npm unlink --global @ai-control-layer/cli`; that is separate from uninstalling the routing profile.
No global installation or PATH modification is required for the repository commands above.

This adapter targets subscription-backed Codex CLI. Native model discovery and context compaction
are routed through the gateway too. The model must be allowed in the active gateway policy, with
the subscription upstream base URL. Set suitable input/output token limits for coding history and
tool schemas. The backend does not inject API-only `max_output_tokens`; output limits are checked
after generation. Provider 401/429 statuses are preserved for OAuth recovery and subscription quotas.
Desktop apps, Claude Code, WebSockets, hosted web search and images/files are not implemented here.
The launcher disables WebSockets and hosted web search. The gateway buffers SSE before output checks;
see [backend configuration](../backend/README.md).

Tests: `node --test cli/control-layer.test.mjs` from the repository root.
For the native protocol test, install Codex CLI 0.155.0 into a temporary directory and set
`CL_CODEX_TEST_ENTRY` to its `node_modules/@openai/codex/bin/codex.js`, then run
`node --test cli/control-layer.test.mjs cli/codex-protocol.test.mjs`.
The native test uses fake subscription credentials and a loopback fixture, not your account.
CI runs both suites on Windows, macOS and Linux. A real ChatGPT account has not been used in these tests.
