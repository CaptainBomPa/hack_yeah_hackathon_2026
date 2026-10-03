import type { ChatParams } from './client'
import { newId } from '../lib/id'
import type {
  AuditEvent,
  ControlTrace,
  DashboardStats,
  GuardedChatResponse,
  PolicyInfo,
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
const POLICY = { policyVersion: 'v3', policyHash: 'a1b2c3d' }

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
          content: 'Widzę numer [REDACTED:PII:PESEL]. Nie przekazuj takich danych w czacie.',
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
        message: { role: 'assistant', content: '(mock) Nie mogę ujawnić instrukcji systemowych.' },
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
      message: { role: 'assistant', content: `(mock) Odpowiedź modelu ${model} na: "${last}"` },
      trace: [allowlist, pii, semantic, { ...pii, policy: 'pii.output', stage: 'output', latencyMs: 1 }],
      usage: { promptTokens: 12, completionTokens: 24 },
      ...POLICY,
      status: 'ok',
      latency: { totalMs: 1290, upstreamMs: 1240 },
    },
    1300,
    signal,
  )
}

export function stats(): Promise<DashboardStats> {
  return delay({
    totalRequests: 1284,
    blocked: 97,
    redacted: 143,
    budgetUsedPct: 37,
    latencyP50Ms: 58,
    latencyP95Ms: 210,
    hitsPerPolicy: [
      { policy: 'pii.email', count: 64 },
      { policy: 'pii.credit_card', count: 41 },
      { policy: 'semantic.jailbreak', count: 55 },
      { policy: 'deterministic.code_injection', count: 23 },
      { policy: 'ssrf.denylist', count: 12 },
    ],
    timeline: Array.from({ length: 12 }, (_, i) => ({
      time: `${String(8 + i).padStart(2, '0')}:00`,
      allow: 60 + ((i * 17) % 40),
      redact: 5 + ((i * 7) % 15),
      block: 3 + ((i * 5) % 12),
    })),
  })
}

export function auditEvents(): Promise<AuditEvent[]> {
  const policies = ['pii.email', 'pii.credit_card', 'semantic.jailbreak', 'deterministic.code_injection']
  const actions = ['redact', 'redact', 'block', 'block'] as const
  return delay(
    Array.from({ length: 20 }, (_, i) => ({
      id: `evt-${i}`,
      timestamp: new Date(Date.now() - i * 60_000).toISOString(),
      callerId: `agent-${(i % 3) + 1}`,
      sessionId: `sess-${(i % 4) + 1}`,
      policy: policies[i % 4],
      action: actions[i % 4],
      redactedHash: `sha256:${(i * 2654435761).toString(16).slice(0, 12)}`,
      policyVersion: 'v3',
    })),
  )
}

let mockPolicy = `version: v3
pii:
  credit_card: { action: redact }
  email: { action: redact }
semantic:
  jailbreak: { action: block, threshold: 0.8 }
budget:
  daily_cap_tokens: 100000
`

export function policy(raw?: string): Promise<PolicyInfo> {
  if (raw !== undefined) mockPolicy = raw
  return delay({ version: 'v3', hash: 'a1b2c3d', updatedAt: new Date().toISOString(), raw: mockPolicy })
}
