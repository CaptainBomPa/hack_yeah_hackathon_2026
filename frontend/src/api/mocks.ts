import { redactedBy } from '../lib/decision'
import { AuthRequiredError, LoginError, PolicyConflictError, PolicyInvalidError, type ChatParams } from './client'
import { newId } from '../lib/id'
import type {
  AuditEvent,
  AuditFacets,
  AuditFilters,
  AuditPage,
  AuditVerifyResult,
  ControlTrace,
  CurrentUser,
  DashboardData,
  DashboardWindow,
  GuardAction,
  GuardedChatResponse,
  PolicyCatalog,
  PolicyDocument,
  PolicyError,
  PolicyVersionSummary,
  PolicyView,
  TextSpan,
} from './types'

const delay = <T,>(value: T, ms = 300) => new Promise<T>((r) => setTimeout(() => r(value), ms))

function abortableDelay<T>(value: T, ms: number, signal?: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => resolve(value), ms)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

const MOCK_MODELS = ['qwen2.5:1.5b-instruct-q4_K_M', 'qwen2.5:0.5b']
const POLICY = { policyVersion: 1, policyHash: 'seedmock' }

/** Symuluje rosnące dzienne zużycie budżetu (BudgetUsage.java) w trybie mock. */
const MOCK_BUDGET_CAP = 20000
let mockBudgetUsed = 1180
function nextMockBudget() {
  mockBudgetUsed += 36
  return { used: mockBudgetUsed, cap: MOCK_BUDGET_CAP }
}

function spansOf(text: string, re: RegExp, label: string): TextSpan[] {
  return [...text.matchAll(new RegExp(re, 'g'))].map((m) => ({
    start: m.index ?? 0,
    end: (m.index ?? 0) + m[0].length,
    label,
  }))
}

/** Odwzorowuje ChatCompletionController.java + rozszerzenia trace z kontraktu §5.1. */
export function chat({ model, messages, signal }: ChatParams): Promise<GuardedChatResponse> {
  const last = messages[messages.length - 1]?.content ?? ''
  const requestId = newId()
  const allowlist: ControlTrace = {
    policy: 'model.allowlist',
    kind: 'deterministic',
    stage: 'input',
    mode: 'block',
    action: 'allow',
    latencyMs: 1,
    detail: null,
    status: 'ok',
  }

  if (!MOCK_MODELS.includes(model)) {
    return abortableDelay(
      {
        requestId,
        action: 'block',
        message: null,
        blockedBy: 'model.allowlist',
        trace: [{ ...allowlist, action: 'block', detail: `model not allowed: ${model}` }],
        usage: null,
        ...POLICY,
      },
      150,
      signal,
    )
  }

  const pii: ControlTrace = {
    policy: 'pii.detect',
    kind: 'deterministic',
    stage: 'input',
    mode: 'redact',
    action: 'allow',
    latencyMs: 2,
    detail: null,
    status: 'ok',
  }
  const semantic: ControlTrace = {
    policy: 'semantic.injection',
    kind: 'semantic',
    stage: 'input',
    mode: 'block',
    action: 'allow',
    latencyMs: 44,
    detail: null,
    confidence: 0.04,
    threshold: 0.8,
    status: 'ok',
    provider: 'mock-local',
  }

  if (/ignore (all )?previous instructions|zignoruj (wszystkie )?poprzednie/i.test(last)) {
    const spans = spansOf(last, /ignore (all )?previous instructions|zignoruj (wszystkie )?poprzednie/i, 'INJECTION')
    return abortableDelay(
      {
        requestId,
        action: 'block',
        message: null,
        blockedBy: 'semantic.injection',
        trace: [
          allowlist,
          pii,
          { ...semantic, action: 'block', confidence: 0.97, detail: 'instruction override attempt', spans },
        ],
        usage: null,
        ...POLICY,
        status: 'ok',
        latency: { totalMs: 52 },
      },
      400,
      signal,
    )
  }

  const pesel = spansOf(last, /\b\d{11}\b/, 'PII:PESEL')
  if (pesel.length > 0) {
    return abortableDelay(
      {
        requestId,
        action: 'redact',
        blockedBy: null,
        message: {
          role: 'assistant',
          content: 'I can see a number [REDACTED:PII:PESEL]. Please do not share such data in the chat.',
        },
        trace: [
          allowlist,
          { ...pii, action: 'redact', detail: `${pesel.length} × PESEL (suma kontrolna OK)`, spans: pesel },
          semantic,
          { ...pii, policy: 'pii.output', stage: 'output', latencyMs: 1 },
        ],
        usage: { promptTokens: 22, completionTokens: 18 },
        ...POLICY,
        status: 'ok',
        latency: { totalMs: 1630, upstreamMs: 1570 },
      },
      1600,
      signal,
    )
  }

  if (/system prompt|jailbreak|DAN/i.test(last)) {
    return abortableDelay(
      {
        requestId,
        action: 'monitor',
        blockedBy: null,
        message: { role: 'assistant', content: '(mock) I cannot reveal my system instructions.' },
        trace: [
          allowlist,
          pii,
          { ...semantic, mode: 'monitor', action: 'monitor', confidence: 0.86, detail: 'possible jailbreak (monitor only)' },
        ],
        usage: { promptTokens: 15, completionTokens: 11 },
        ...POLICY,
        status: 'ok',
        latency: { totalMs: 1420, upstreamMs: 1360 },
      },
      1400,
      signal,
    )
  }

  if (/timeout|awaria/i.test(last)) {
    return abortableDelay(
      {
        requestId,
        action: 'block',
        message: null,
        blockedBy: 'semantic.injection',
        trace: [
          allowlist,
          pii,
          {
            ...semantic,
            action: 'block',
            confidence: undefined,
            latencyMs: 2000,
            status: 'error',
            detail: 'provider timeout after 2000 ms (fail-closed)',
          },
        ],
        usage: null,
        ...POLICY,
        status: 'degraded',
        latency: { totalMs: 2004 },
      },
      2000,
      signal,
    )
  }

  return abortableDelay(
    {
      requestId,
      action: 'allow',
      blockedBy: null,
      message: { role: 'assistant', content: `(mock) Model ${model} answer to: "${last}"` },
      trace: [allowlist, pii, semantic, { ...pii, policy: 'pii.output', stage: 'output', latencyMs: 1 }],
      usage: { promptTokens: 12, completionTokens: 24 },
      budget: nextMockBudget(),
      ...POLICY,
      status: 'ok',
      latency: { totalMs: 1290, upstreamMs: 1240 },
    },
    1300,
    signal,
  )
}

/** Agregaty jak DashboardService.java, liczone z MOCK_AUDIT — spójne z ekranem audytu w trybie mock. */
export function dashboard(window: DashboardWindow): Promise<DashboardData> {
  const lengthMs = { '1h': 3_600_000, '24h': 86_400_000, '7d': 604_800_000 }[window]
  const bucketMs = { '1h': 300_000, '24h': 3_600_000, '7d': 21_600_000 }[window]
  const to = Date.now()
  const from = (Math.floor((to - lengthMs) / bucketMs) + 1) * bucketMs
  const events = MOCK_AUDIT.filter((e) => Date.parse(e.timestamp) >= from)
  const zero = () => ({ allow: 0, monitor: 0, redact: 0, require_approval: 0, block: 0 }) as Record<string, number>
  const byAction = zero()
  const buckets = new Map<number, Record<string, number>>()
  for (let s = from; s < to; s += bucketMs) buckets.set(s, zero())
  const controls = new Map<string, number>()
  const models = new Map<string, { requests: number; blocked: number; tokens: number }>()
  const principals = new Map<string, { role: string | null; requests: number; blocked: number; tokens: number }>()
  const latencies: number[] = []
  let prompt = 0
  let completion = 0
  for (const e of events) {
    byAction[e.action]++
    const b = buckets.get(Math.floor(Date.parse(e.timestamp) / bucketMs) * bucketMs)
    if (b) b[e.action]++
    const tokens = (e.usage?.promptTokens ?? 0) + (e.usage?.completionTokens ?? 0)
    prompt += e.usage?.promptTokens ?? 0
    completion += e.usage?.completionTokens ?? 0
    if (e.action !== 'block') latencies.push(e.latencyMs)
    for (const t of e.trace) if (t.action !== 'allow') controls.set(`${t.policy}|${t.action}`, (controls.get(`${t.policy}|${t.action}`) ?? 0) + 1)
    const blocked = e.action === 'block' ? 1 : 0
    if (e.model) {
      const s = models.get(e.model) ?? { requests: 0, blocked: 0, tokens: 0 }
      models.set(e.model, { requests: s.requests + 1, blocked: s.blocked + blocked, tokens: s.tokens + tokens })
    }
    if (e.principal) {
      const s = principals.get(e.principal) ?? { role: e.role, requests: 0, blocked: 0, tokens: 0 }
      principals.set(e.principal, { ...s, requests: s.requests + 1, blocked: s.blocked + blocked, tokens: s.tokens + tokens })
    }
  }
  latencies.sort((a, b) => a - b)
  const pct = (p: number) => (latencies.length ? latencies[Math.max(Math.ceil((p / 100) * latencies.length) - 1, 0)] : null)
  return delay({
    window,
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    truncated: false,
    totals: { requests: events.length, byAction, errors: 0 },
    latency: { p50: pct(50), p95: pct(95), max: latencies.at(-1) ?? null, samples: latencies.length },
    tokens: { prompt, completion },
    timeline: [...buckets].map(([start, counts]) => ({ start: new Date(start).toISOString(), byAction: counts })),
    controls: [...controls]
      .map(([key, count]) => ({ policy: key.split('|')[0], action: key.split('|')[1] as GuardAction, count }))
      .sort((a, b) => b.count - a.count),
    models: [...models].map(([model, s]) => ({ model, ...s })).sort((a, b) => b.requests - a.requests),
    principals: [...principals].map(([principal, s]) => ({ principal, ...s })).sort((a, b) => b.requests - a.requests),
    budgets: [
      { role: 'admin', usedTokens: 0, reservedTokens: 0, cap: null },
      { role: 'agent', usedTokens: 12_400, reservedTokens: 0, cap: 100_000 },
      { role: 'chat', usedTokens: 17_300, reservedTokens: 1_100, cap: 20_000 },
    ],
  })
}

const MOCK_AUDIT: AuditEvent[] = Array.from({ length: 120 }, (_, i) => {
  const seq = 120 - i
  const kind = seq % 5
  const action = (['allow', 'redact', 'block', 'allow', 'block'] as const)[kind]
  const blockedBy = kind === 2 ? 'model.allowlist' : kind === 4 ? 'policy.model-access' : null
  const trace: ControlTrace[] = [
    { policy: 'model.allowlist', kind: 'deterministic', action: kind === 2 ? 'block' : 'allow', latencyMs: 0, detail: kind === 2 ? 'model not allowed: llama3:70b' : null },
    ...(kind === 4
      ? [{ policy: 'policy.model-access', kind: 'deterministic' as const, action: 'block' as const, latencyMs: 0, detail: 'role agent may not use model qwen2.5:0.5b' }]
      : []),
    ...(kind === 1 ? [{ policy: 'PII-001', kind: 'deterministic' as const, action: 'redact' as const, latencyMs: 1, detail: '1 PESEL' }] : []),
  ]
  return {
    seq,
    requestId: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
    timestamp: new Date(Date.now() - i * 47_000).toISOString(),
    principal: ['chat1', 'chat2', 'agent-runner', 'admin'][seq % 4],
    role: ['chat', 'chat', 'agent', 'admin'][seq % 4],
    sessionId: `sess-${(seq % 6) + 1}`,
    model: kind === 2 ? 'llama3:70b' : kind === 4 ? 'qwen2.5:0.5b' : 'qwen2.5:1.5b-instruct-q4_K_M',
    action,
    blockedBy,
    httpStatus: action === 'block' ? 403 : 200,
    latencyMs: action === 'block' ? 2 : 900 + ((seq * 137) % 2400),
    usage: action === 'block' ? null : { promptTokens: 20 + (seq % 40), completionTokens: 30 + (seq % 90) },
    messageCount: 1 + (seq % 5),
    trace,
    policyVersion: 1,
    recordHash: (seq * 2654435761).toString(16).padStart(64, 'a').slice(0, 64),
  }
})

export function auditEvents(filters: AuditFilters, before?: number | null): Promise<AuditPage> {
  const anyOf = (wanted: string[] | undefined, value: string | null) => !wanted?.length || (value !== null && wanted.includes(value))
  const session = filters.sessionId?.trim().toLowerCase()
  const matches = MOCK_AUDIT.filter(
    (e) =>
      (!before || e.seq < before) &&
      anyOf(filters.action, e.action) &&
      anyOf(filters.principal, e.principal) &&
      anyOf(filters.model, e.model) &&
      anyOf(filters.blockedBy, e.blockedBy) &&
      (!filters.reason?.length || [e.blockedBy, ...redactedBy(e.action, e.trace)].some((r) => r !== null && filters.reason!.includes(r))) &&
      (!session || (e.sessionId ?? '').toLowerCase().includes(session)),
  )
  const items = matches.slice(0, 50)
  return delay({ items, nextCursor: matches.length > 50 ? items[items.length - 1].seq : null })
}

export function auditFacets(): Promise<AuditFacets> {
  const distinct = (values: (string | null)[]) => [...new Set(values.filter((v): v is string => !!v))].sort()
  return delay({
    actions: distinct(MOCK_AUDIT.map((e) => e.action)),
    principals: distinct(MOCK_AUDIT.map((e) => e.principal)),
    models: distinct(MOCK_AUDIT.map((e) => e.model)),
    blockedBy: distinct(MOCK_AUDIT.map((e) => e.blockedBy)),
    reasons: distinct(MOCK_AUDIT.flatMap((e) => [e.blockedBy, ...redactedBy(e.action, e.trace)])),
  })
}

export function auditEvent(requestId: string): Promise<AuditEvent> {
  const event = MOCK_AUDIT.find((e) => e.requestId === requestId)
  return event ? delay(event) : Promise.reject(new Error('404'))
}

export function auditVerify(): Promise<AuditVerifyResult> {
  return delay({ valid: true, checked: MOCK_AUDIT.length, brokenAtSeq: null, reason: null })
}

// --- polityka (tryb mock): wersje w pamięci, zachowanie jak PolicyStore.java ---
const MOCK_CATALOG: PolicyCatalog = {
  models: [
    { tag: 'qwen2.5:0.5b', baseUrl: 'http://ollama:11434' },
    { tag: 'qwen2.5:1.5b-instruct-q4_K_M', baseUrl: 'http://ollama:11434' },
  ],
  guards: [
    { id: 'PII-RECOGNIZERS', kind: 'deterministic', stages: ['INPUT', 'OUTPUT', 'TOOL_CALL'] },
    { id: 'SEM-001', kind: 'semantic', stages: ['INPUT'] },
  ],
  piiRecognizers: [
    { id: 'PII-001', name: 'PESEL', entity: 'PL_PESEL', defaultAction: 'redact' },
    { id: 'PII-002', name: 'Email', entity: 'EMAIL_ADDRESS', defaultAction: 'redact' },
    { id: 'PII-007', name: 'Payment card', entity: 'CREDIT_CARD', defaultAction: 'redact' },
  ],
  roleAccounts: { admin: 1, chat: 3, agent: 2 },
}
const MOCK_SEED: PolicyDocument = {
  roles: {
    admin: { models: ['*'], dailyTokens: null },
    agent: { models: ['qwen2.5:1.5b-instruct-q4_K_M'], dailyTokens: 100000 },
    chat: { models: ['qwen2.5:0.5b', 'qwen2.5:1.5b-instruct-q4_K_M'], dailyTokens: 20000 },
  },
  models: MOCK_CATALOG.models.map((m) => ({ tag: m.tag, enabled: true })),
  guards: {
    'PII-RECOGNIZERS': { enabled: true, order: 100, params: { threshold: 0.5, blockRecognizers: [], monitorRecognizers: [], disabledRecognizers: [] } },
    'SEM-001': { enabled: true, order: 200, params: { blockThreshold: 0.998, timeoutMs: 4000, failureMode: 'closed' } },
  },
  limits: { maxInputTokens: 4000, maxOutputTokens: 1024 },
}
const mockVersions: PolicyView[] = [
  { version: 1, hash: 'seedmock', author: 'seed', source: 'seed', comment: 'initial policy', createdAt: new Date().toISOString(), document: MOCK_SEED, catalog: MOCK_CATALOG },
]
const latest = () => mockVersions[mockVersions.length - 1]
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T

export function policy(): Promise<PolicyView> {
  return delay(clone(latest()))
}

export function validatePolicy(document: PolicyDocument): Promise<{ valid: boolean; errors: PolicyError[] }> {
  const errors: PolicyError[] = []
  if (!document.roles.admin) errors.push({ path: 'roles', message: "role 'admin' is required" })
  for (const [role, count] of Object.entries(MOCK_CATALOG.roleAccounts)) {
    if (count > 0 && !document.roles[role]) errors.push({ path: `roles.${role}`, message: `role '${role}' is used by ${count} account(s)` })
  }
  return delay({ valid: errors.length === 0, errors }, 150)
}

export async function savePolicy(baseVersion: number, document: PolicyDocument, comment: string, source: string): Promise<PolicyView> {
  if (baseVersion !== latest().version) throw new PolicyConflictError(latest().version)
  const { errors } = await validatePolicy(document)
  if (errors.length) throw new PolicyInvalidError(errors)
  const next = { ...clone(latest()), version: latest().version + 1, hash: Math.random().toString(16).slice(2, 10), author: 'mock-admin', source, comment: comment || null, createdAt: new Date().toISOString(), document: clone(document) }
  mockVersions.push(next)
  return delay(clone(next))
}

export function policyVersions(): Promise<PolicyVersionSummary[]> {
  return delay(mockVersions.map(({ document: _d, catalog: _c, ...summary }) => summary).reverse())
}

export function policyVersion(version: number): Promise<PolicyView> {
  const found = mockVersions.find((v) => v.version === version)
  return found ? delay(clone(found)) : Promise.reject(new Error('404'))
}

export async function restorePolicy(version: number): Promise<PolicyView> {
  const found = await policyVersion(version)
  return savePolicy(latest().version, found.document, `restored from version ${version}`, 'restore')
}

// --- logowanie (tryb mock): dowolne hasło; rola z prefiksu loginu (chat*/agent*), reszta = admin ---
const MOCK_SESSION_KEY = 'mock-auth-user'

function readMockUser(): CurrentUser | null {
  try {
    const raw = sessionStorage.getItem(MOCK_SESSION_KEY)
    return raw ? (JSON.parse(raw) as CurrentUser) : null
  } catch {
    return null
  }
}

export function me(): Promise<CurrentUser> {
  const user = readMockUser()
  return user ? delay(user, 100) : Promise.reject(new AuthRequiredError())
}

export function login(login: string, password: string): Promise<CurrentUser> {
  if (!login.trim() || !password) return Promise.reject(new LoginError('invalid_credentials', 'Invalid username or password.'))
  const role = login.startsWith('chat') ? 'chat' : login.startsWith('agent') ? 'agent' : 'admin'
  const user = { login: login.trim(), role }
  try {
    sessionStorage.setItem(MOCK_SESSION_KEY, JSON.stringify(user))
  } catch {
    // tryb prywatny bez storage: sesja mock tylko do odświeżenia strony
  }
  return delay(user, 300)
}

export function logout(): Promise<void> {
  try {
    sessionStorage.removeItem(MOCK_SESSION_KEY)
  } catch {
    // brak storage
  }
  return delay(undefined, 100)
}
