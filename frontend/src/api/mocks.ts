import type { AuditEvent, ChatMessage, DashboardStats, GuardedChatResponse, PolicyInfo } from './types'

const delay = <T,>(value: T, ms = 300) => new Promise<T>((r) => setTimeout(() => r(value), ms))

export function chat(messages: ChatMessage[]): Promise<GuardedChatResponse> {
  const last = messages[messages.length - 1]?.content ?? ''
  const requestId = crypto.randomUUID()

  if (/ignore (all )?previous instructions/i.test(last)) {
    return delay({
      requestId,
      action: 'block',
      blockedBy: 'semantic.jailbreak',
      trace: [
        { policy: 'pii.detect', kind: 'deterministic', action: 'allow', latencyMs: 1 },
        { policy: 'semantic.jailbreak', kind: 'semantic', action: 'block', latencyMs: 42, detail: 'score 0.97' },
      ],
    })
  }
  if (/\b(?:\d[ -]?){13,16}\b/.test(last)) {
    return delay({
      requestId,
      action: 'redact',
      message: { role: 'assistant', content: 'Nie podawaj numeru karty [REDACTED] w czacie.' },
      trace: [
        { policy: 'pii.credit_card', kind: 'deterministic', action: 'redact', latencyMs: 1, detail: 'Luhn OK' },
        { policy: 'semantic.jailbreak', kind: 'semantic', action: 'allow', latencyMs: 38 },
      ],
      usage: { promptTokens: 18, completionTokens: 12 },
    })
  }
  return delay({
    requestId,
    action: 'allow',
    message: { role: 'assistant', content: `(mock) Odpowiedź modelu na: "${last}"` },
    trace: [
      { policy: 'pii.detect', kind: 'deterministic', action: 'allow', latencyMs: 1 },
      { policy: 'semantic.jailbreak', kind: 'semantic', action: 'allow', latencyMs: 40 },
    ],
    usage: { promptTokens: 12, completionTokens: 24 },
  })
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
