// Kontrakt frontend <-> gateway. Szkic — do uzgodnienia z zespołem Java (VISION.md §4–5).

export type GuardAction = 'allow' | 'redact' | 'block'

/** Wynik pojedynczej kontroli (deterministycznej lub semantycznej) dla jednego żądania. */
export interface ControlTrace {
  policy: string // np. "pii.credit_card", "semantic.jailbreak"
  kind: 'deterministic' | 'semantic'
  action: GuardAction
  latencyMs: number
  detail?: string
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

/** Odpowiedź gatewaya na /v1/chat/completions wzbogacona o trace kontroli. */
export interface GuardedChatResponse {
  requestId: string
  action: GuardAction
  message?: ChatMessage // brak, gdy action === 'block'
  blockedBy?: string
  trace: ControlTrace[]
  usage?: { promptTokens: number; completionTokens: number }
}

/** Wpis audit logu — nigdy surowe PII, tylko hash zredagowanego fragmentu (VISION.md §5). */
export interface AuditEvent {
  id: string
  timestamp: string
  callerId: string
  sessionId?: string
  policy: string
  action: GuardAction
  redactedHash?: string
  policyVersion: string
}

export interface DashboardStats {
  totalRequests: number
  blocked: number
  redacted: number
  budgetUsedPct: number
  latencyP50Ms: number
  latencyP95Ms: number
  hitsPerPolicy: { policy: string; count: number }[]
  timeline: { time: string; allow: number; redact: number; block: number }[]
}

export interface PolicyInfo {
  version: string
  hash: string
  updatedAt: string
  raw: string // YAML polityki
}
