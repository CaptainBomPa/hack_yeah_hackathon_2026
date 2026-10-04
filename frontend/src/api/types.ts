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

/** Zużycie dziennego budżetu roli wywołującego — BudgetUsage.java. `cap: null` = rola bez limitu. */
export interface ChatBudget {
  used: number
  cap: number | null
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
  budget?: ChatBudget | null // zużycie dziennego budżetu roli; brak = backend go jeszcze nie wysyłał
  policyVersion?: number | string | null // wersja polityki, która podjęła decyzję (GuardedChatResponse.java)
  policyHash?: string | null
  /** Ostatni prompt użytkownika po redakcji (null = bez zmian). Klient wysyła go w historii zamiast oryginału. */
  redactedPrompt?: string | null
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

/**
 * Rekord audytu — AuditEventView.java (GET /api/audit/events). Bez treści promptów i odpowiedzi
 * (VISION.md §6); `recordHash` to ogniwo łańcucha HMAC, sprawdzane przez GET /api/audit/verify.
 */
export interface AuditEvent {
  seq: number
  requestId: string
  timestamp: string
  principal: string | null
  role: string | null
  sessionId: string | null
  model: string | null
  action: GuardAction
  blockedBy: string | null
  httpStatus: number
  latencyMs: number
  usage: ChatUsage | null
  messageCount: number
  trace: ControlTrace[]
  /** wersja polityki, która podjęła decyzję (null w rekordach sprzed wersjonowania polityk) */
  policyVersion: number | null
  recordHash: string
}

export interface AuditPage {
  items: AuditEvent[]
  nextCursor: number | null
}

/** Filtry audytu: listy = „którakolwiek z wartości” (wybór z facets), sessionId = „zawiera”. */
export interface AuditFilters {
  action?: string[]
  principal?: string[]
  model?: string[]
  blockedBy?: string[]
  /** Kontrola, która zablokowała albo zredagowała żądanie. */
  reason?: string[]
  sessionId?: string
}

/** GET /api/audit/facets — wartości występujące w audycie, do list wyboru. */
export interface AuditFacets {
  actions: string[]
  principals: string[]
  models: string[]
  blockedBy: string[]
  reasons: string[]
}

/** GET /api/audit/verify — AuditService.VerifyResult. */
export interface AuditVerifyResult {
  valid: boolean
  checked: number
  brokenAtSeq: number | null
  reason: string | null
}

/** GET /api/dashboard — DashboardView.java. Liczone z audytu w oknie czasowym; budżety z budget_counter + policy.yaml. */
export type DashboardWindow = '1h' | '24h' | '7d'

export interface DashboardData {
  window: DashboardWindow
  from: string
  to: string
  /** true = w oknie było więcej rekordów niż limit agregacji; liczby są dolną granicą. */
  truncated: boolean
  totals: { requests: number; byAction: Record<string, number>; errors: number }
  /** Czas odpowiedzi żądań, które doszły do modelu (allow/monitor/redact). null = brak próbek. */
  latency: { p50: number | null; p95: number | null; max: number | null; samples: number }
  tokens: { prompt: number; completion: number }
  timeline: { start: string; byAction: Record<string, number> }[]
  controls: { policy: string; action: GuardAction; count: number }[]
  models: { model: string; requests: number; blocked: number; tokens: number }[]
  principals: { principal: string; role: string | null; requests: number; blocked: number; tokens: number }[]
  /** cap null = rola bez dziennego limitu. */
  budgets: { role: string; usedTokens: number; reservedTokens: number; cap: number | null }[]
}

/** Polityka gatewaya — PolicyDocument.java. Źródło prawdy: tabela policy_version (docs/policy-management-plan.md). */
export interface PolicyDocument {
  /** rola konta → dozwolone modele ("*" = wszystkie) i dzienny limit tokenów (null = bez limitu) */
  roles: Record<string, { models: string[]; dailyTokens: number | null }>
  /** allowlista: które tagi z katalogu modeli są włączone */
  models: { tag: string; enabled: boolean }[]
  /** id guarda → włączony, kolejność, parametry */
  guards: Record<string, { enabled: boolean; order: number; params: Record<string, unknown> }>
  limits: { maxInputTokens: number; maxOutputTokens: number }
}

/** Z czego UI może wybierać przy edycji polityki (PolicyController.Catalog). */
export interface PolicyCatalog {
  models: { tag: string; baseUrl: string }[]
  guards: { id: string; kind: string; stages: string[] }[]
  piiRecognizers: { id: string; name: string; entity: string; defaultAction: string }[]
  /** rola → liczba kont; roli z kontami nie da się usunąć */
  roleAccounts: Record<string, number>
}

export interface PolicyVersionSummary {
  version: number
  hash: string
  author: string
  source: 'seed' | 'ui' | 'import' | 'restore' | string
  comment: string | null
  createdAt: string
}

/** GET /api/policy — PolicyController.PolicyView. */
export interface PolicyView extends PolicyVersionSummary {
  document: PolicyDocument
  catalog: PolicyCatalog
}

export interface PolicyError {
  path: string
  message: string
}

/** Zalogowany użytkownik — AuthController.CurrentUser (GET /api/auth/me). Role z backend/config/policy.yaml. */
export interface CurrentUser {
  login: string
  role: 'admin' | 'chat' | 'agent' | string | null
}
