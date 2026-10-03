// Kontrakt frontend <-> gateway: docs/frontend-flows-and-api.md.
// Typy czatu odpowiadają 1:1 backend/src/main/java/pl/hackyeah/controllayer/chat/*.java;
// pola oznaczone "rozszerzenie" są proponowane w kontrakcie §5.1 i backend może ich jeszcze nie wysyłać.

/** Akcja decyzji (VISION.md §4). Backend dziś zwraca tylko allow/block. */
export type GuardAction = 'allow' | 'monitor' | 'redact' | 'require_approval' | 'block'

/** Status techniczny kontroli/zależności (VISION.md §4). */
export type TechStatus = 'ok' | 'degraded' | 'error'

export interface TextSpan {
  start: number
  end: number
  label: string
}

/** Wynik pojedynczej kontroli dla jednego żądania — ControlTrace.java. */
export interface ControlTrace {
  policy: string // np. "model.allowlist", "semantic.injection"
  kind: 'deterministic' | 'semantic'
  action: GuardAction
  latencyMs: number
  detail: string | null
  // rozszerzenia (kontrakt §5.1)
  stage?: 'input' | 'output'
  mode?: 'off' | 'monitor' | 'redact' | 'require_approval' | 'block'
  confidence?: number
  threshold?: number
  status?: TechStatus
  provider?: string
  spans?: TextSpan[]
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

export interface ChatUsage {
  promptTokens: number
  completionTokens: number
}

/**
 * Odpowiedź POST /v1/chat/completions — GuardedChatResponse.java.
 * Ten sam kształt przychodzi dla 200, 400 (walidacja), 403 (polityka) i 502 (model nie odpowiada).
 */
export interface GuardedChatResponse {
  requestId: string
  action: GuardAction
  message: ChatMessage | null // null, gdy action === 'block'
  blockedBy: string | null
  trace: ControlTrace[]
  usage: ChatUsage | null
  // rozszerzenia (kontrakt §5.1)
  policyVersion?: string
  policyHash?: string
  status?: TechStatus
  latency?: { totalMs: number; upstreamMs?: number }
  shadow?: { policyVersion: string; action: GuardAction; blockedBy?: string | null }
}

export interface ModelOption {
  tag: string
  provider: string
  enabled: boolean
  status?: 'available' | 'unavailable'
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
