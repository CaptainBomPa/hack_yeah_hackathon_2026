# Rule Model

Uniwersalny model reguły dla wszystkich kontroli z katalogu. Reguły to **dane** (YAML w pliku / JSON w Postgres), nigdy kod.
Model wynika z case files 01–27: wspólny szkielet + `matcher` jako unia typów.

## 1. Pola

| Pole | Typ | Opis | Uzasadnienie z researchu |
|---|---|---|---|
| `id` | string | Unikalne, stabilne: `PREFIX-NNN` (PII-001, MCP-ARG-003) | Test-catalog i audit odwołują się po `id` |
| `name` | string | Czytelna nazwa | UI do zarządzania regułami |
| `category` | enum | pii, secrets, authn, authz, mcp, network, filesystem, command, resource, state, output, threat-intel, supply-chain, input, governance | Dashboard grupuje po kategorii |
| `enabled` | bool | Wyłączenie bez usuwania | Jury podmienia politykę na żywo |
| `priority` | int | Mniejsza = wcześniej; wskazówka w ramach etapu | Kolejność z architecture.md §1 |
| `stage` | enum | `edge, canonical, authn, policy, input, tool_call, session, output` | Reguła działa w konkretnym punkcie pipeline |
| `scope` | object | `direction[input/output/tool_call], tenants, agents, users/roles, tools, models, environments` | Per-agent/tenant/env (TENANT, AUTHZ) |
| `conditions` | list | Warunki kontekstowe (ABAC): atrybuty sesji/principala/flagi taint | SEQ (trifecta), AUTHZ |
| `matcher` | object | `type` + parametry (patrz §2) | Jedna reguła = jeden matcher |
| `view` | enum | na którym widoku tekstu: `raw, canonical, decoded, hidden_ascii` | CANON: reguły na widokach |
| `action` | enum | ALLOW, REDACT, BLOCK, RATE_LIMIT, QUARANTINE, REVIEW, CHALLENGE (+ `clamp` jako modyfikator) | decision-model.md |
| `severity` | enum | INFO, LOW, MEDIUM, HIGH, CRITICAL | Raportowanie, agregacja |
| `threshold` | object | Progi licznikowe / score: `count, window, score_min` | RATE, LOOP, PI hybrid |
| `on_error` | enum | `fail_closed` \| `fail_open` | Fail-mode jawny (RATE, AUDIT) |
| `exceptions` | list | Wyłączenia (np. test-card `4111…`, pola `example`, polling tool) | Redukcja FP |
| `mode` | enum | `enforce` \| `monitor` (log-only) \| `shadow` | Bezpieczne wdrażanie nowych reguł |
| `metadata` | object | `owasp, mitre_atlas, cwe, cve, references, version, owner, tests[]` | Ślad dowodowy |

Zasady: `id` niezmienne; zmiana semantyki → nowa wersja (`metadata.version`). Reguły wersjonowane razem z polityką (`policy_version`).

## 2. Typy matcherów

| `matcher.type` | Parametry | Użycie |
|---|---|---|
| `regex` | `pattern` (RE2J), `flags` | SEC, PI, DESER |
| `regex+validator` | `pattern`, `validator` (pesel, nip, regon, luhn, iban_mod97, entropy) | PII, SEC |
| `multi_pattern` | `patterns[]`/plik (Aho-Corasick) | SIG, PI |
| `json_schema` | `schema` (strict, bez zdalnych `$ref`) | MCP-ARG, EXF |
| `field_path` | `paths[]`, `inner` matcher | PII/SEC w JSON/YAML/XML |
| `allowlist` / `denylist` | `values[]`, `match: exact|prefix_segments|cidr|glob` | MODEL, MCP-ALLOW, NET, PKG |
| `url_policy` | `schemes, hosts, resolve_dns, block_cidrs, max_redirects` | NET, EXF |
| `path_policy` | `roots_read, roots_write, deny_globs, follow_symlinks:false` | FS |
| `command_policy` | `binaries{name: allowed_flags}`, `shell:false`, `ast_parser` | CMD |
| `counter` | `key (caller/agent/tool/…)`, `window`, `limit`, `algorithm` | RATE, CHAIN, LOOP |
| `budget` | `unit (tokens/usd)`, `period`, `limit`, `reserve` | BUDGET |
| `repeat_hash` | `hash_of: [tool, args_canonical]`, `window`, `max_repeats` | LOOP |
| `sequence_dfa` | `states`, `transitions`, `forbidden` | SEQ |
| `taint_gate` | `requires_labels`, `forbids_combination` | SEQ (lethal trifecta) |
| `pin_compare` | `source: tools_list`, `hash: sha256(jcs)` | MCP-INT, MODEL-SC |
| `signature_feed` | `feed`, `version`, `type (ioc/hash/cve)` | SIG |
| `magic_bytes` | `hex`, `base64_prefixes` | DESER, MODEL-SC |
| `canary` | `token_source`, `encodings` | OUT |
| `semantic_signal` | `sidecar_endpoint`, `min_score`, `role: signal` | wejście dla hybrid scoringu — nigdy jedyna bramka |

## 3. Semantyka ewaluacji

- Ewaluacja w kolejności `stage` → `priority`. Pierwsze `BLOCK`/`QUARANTINE` kończy; `REDACT` kumuluje się; `ALLOW` nie unieważnia późniejszego `BLOCK` (deny-overrides).
- Reguły o tym samym `scope` nie nadpisują się — wygrywa najsurowsza akcja (patrz decision-model.md).
- `exceptions` są sprawdzane po matcherze; wyjątek zapisuje się w audit (`suppressed_by`).
- `mode: monitor` zwraca decyzję w audit, ale nie egzekwuje.

## 4. Przykłady YAML

```yaml
# PII — PESEL z checksumą (input + output)
- id: PII-001
  name: Polish PESEL with checksum
  category: pii
  stage: input
  enabled: true
  priority: 100
  scope: { direction: [input, output], agents: ["*"], environments: ["*"] }
  view: canonical
  matcher: { type: regex+validator, pattern: '\b\d{11}\b', validator: pesel_checksum, context_keywords: [pesel, "nr ewidencyjny"] }
  action: REDACT
  severity: HIGH
  exceptions: [{ value_regex: '^(00000000000)$' }]
  metadata: { owasp: [LLM02], tests: [PII-T001] }
```
```yaml
# Secrets — AWS access key (zrekonstruowany w runnerze w testach)
- id: SEC-001
  name: AWS access key id
  category: secrets
  stage: input
  priority: 90
  scope: { direction: [input, output, tool_call] }
  view: decoded
  matcher: { type: regex, pattern: '\b(AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}\b' }
  action: REDACT
  severity: CRITICAL
  metadata: { owasp: [LLM02], source: gitleaks (MIT) }
```
```yaml
# AuthZ — narzędzie dozwolone tylko dla roli, w środowisku nieprodukcyjnym
- id: AUTHZ-003
  name: exec_command only for role devops outside prod
  category: authz
  stage: tool_call
  priority: 20
  scope: { tools: [exec_command] }
  conditions:
    - { attr: principal.roles, not_contains: devops }
    - { attr: env, equals: prod, or: true }
  matcher: { type: denylist, values: ["*"], match: exact }
  action: BLOCK
  severity: HIGH
  on_error: fail_closed
```
```yaml
# MCP argument validation — strict schema
- id: MCP-ARG-002
  name: read_file arguments schema
  category: mcp
  stage: tool_call
  scope: { tools: [read_file] }
  matcher:
    type: json_schema
    schema:
      type: object
      additionalProperties: false
      required: [path]
      properties: { path: { type: string, maxLength: 512, pattern: '^[^\x00]+$' } }
  action: BLOCK
  severity: MEDIUM
```
```yaml
# Network — SSRF guard
- id: NET-001
  name: SSRF guard for tool URL args
  category: network
  stage: tool_call
  scope: { tools: ["*"] }
  matcher:
    type: url_policy
    schemes: [https]
    hosts_allow: ["api.example.com"]
    resolve_dns: true
    block_cidrs: [127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, "::1/128", "fc00::/7"]
    max_redirects: 0
  action: BLOCK
  severity: CRITICAL
```
```yaml
# Resource — token budget z rezerwacją
- id: BUDGET-002
  name: Daily token budget per caller
  category: resource
  stage: policy
  matcher: { type: budget, unit: tokens, period: day, limit: 200000, reserve: max_tokens }
  action: BLOCK
  severity: MEDIUM
  on_error: fail_closed
  threshold: { warn_at: 0.8 }
```
```yaml
# Rate limit
- id: RATE-001
  name: 60 RPM per api key
  category: resource
  stage: edge
  matcher: { type: counter, key: principal.id, window: 60s, limit: 60, algorithm: token_bucket }
  action: RATE_LIMIT      # 429 + Retry-After
  severity: LOW
  on_error: fail_open
```
```yaml
# State — lethal trifecta
- id: SEQ-001
  name: Block egress after private data + untrusted content
  category: state
  stage: session
  matcher: { type: taint_gate, forbids_combination: [untrusted_content, private_data, egress_capable_call] }
  action: BLOCK           # lub CHALLENGE (zatwierdzenie człowieka)
  severity: CRITICAL
```
```yaml
# Loop detection
- id: LOOP-001
  name: Identical tool call repeated
  category: resource
  stage: session
  matcher: { type: repeat_hash, hash_of: [tool, args_canonical], window: 120s }
  threshold: { max_repeats: 4 }
  action: QUARANTINE
  severity: HIGH
  exceptions: [{ tool: job_status }]
```
```yaml
# MCP integrity
- id: MCP-INT-001
  name: Tool definition drift
  category: mcp
  stage: tool_call
  matcher: { type: pin_compare, source: tools_list, hash: sha256_jcs }
  action: QUARANTINE
  severity: HIGH
```
```yaml
# Threat signatures z feedu (hot reload)
- id: SIG-001
  name: Known payload signatures
  category: threat-intel
  stage: input
  view: decoded
  matcher: { type: signature_feed, feed: feeds/payloads.yml, version_pinned: false }
  action: BLOCK
  severity: HIGH
```
```yaml
# Output — markdown image exfil
- id: EXF-002
  name: Strip images and links to non-allowlisted hosts
  category: output
  stage: output
  matcher: { type: url_policy, parse: commonmark_ast, hosts_allow: ["docs.example.com"] }
  action: REDACT
  severity: HIGH
```
```yaml
# Prompt-attack: sygnał do hybrid scoringu
- id: PI-001
  name: Instruction override phrases
  category: input
  stage: input
  view: canonical
  matcher: { type: multi_pattern, patterns_file: feeds/pi-phrases.txt }
  action: REVIEW          # score += 0.4; samo trafienie nie blokuje
  severity: MEDIUM
  threshold: { score_weight: 0.4, block_at: 0.8 }
```

## 5. Walidacja reguł przy ładowaniu

Schemat reguły (JSON Schema), kompilacja regexów RE2J (limit rozmiaru automatu), unikalność `id`, odwołania do feedów/walidatorów istnieją, `action` zgodna z `stage` (np. REDACT nie ma sensu w `edge`), wymagane `tests[]` dla reguł MUST. Błąd → odrzuć całą wersję, zachowaj poprzednią.
