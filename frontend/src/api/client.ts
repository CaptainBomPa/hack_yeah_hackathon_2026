import type {
  AuditEvent,
  AuditFilters,
  AuditPage,
  AuditVerifyResult,
  ChatMessage,
  CurrentUser,
  DashboardStats,
  GuardedChatResponse,
  ModelOption,
  PolicyInfo,
} from './types'
import * as mocks from './mocks'

export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS === 'true'

export type Feature = 'auth' | 'chat' | 'models' | 'stats' | 'audit' | 'policy'

/**
 * Funkcje, które backend już implementuje — wołają żywy gateway mimo VITE_USE_MOCKS=true.
 * Dopisywać tu kolejne, gdy powstaną ich endpointy. VITE_LIVE_FEATURES nadpisuje tę listę.
 */
const IMPLEMENTED_IN_BACKEND: Feature[] = ['auth', 'chat', 'audit']

const LIVE_FEATURES = new Set(
  import.meta.env.VITE_LIVE_FEATURES !== undefined
    ? import.meta.env.VITE_LIVE_FEATURES.split(',')
        .map((f) => f.trim())
        .filter(Boolean)
    : IMPLEMENTED_IN_BACKEND,
)

/** Czy dana funkcja ma używać mocków (VITE_USE_MOCKS=true minus funkcje działające w backendzie). */
export function isMocked(feature: Feature): boolean {
  return USE_MOCKS && !LIVE_FEATURES.has(feature)
}

const DEFAULT_MODELS = 'qwen2.5:1.5b-instruct-q4_K_M,qwen2.5:0.5b'

/** Gateway nie odpowiedział własnym kontraktem (wyłączony, proxy padło, nieoczekiwany błąd). */
export class GatewayUnavailableError extends Error {
  constructor(readonly status: number | null, detail: string) {
    super(detail)
    this.name = 'GatewayUnavailableError'
  }
}

/** Brak sesji/poświadczeń: 401 z gatewaya (docs/auth). */
export class AuthRequiredError extends Error {
  constructor() {
    super('Wymagane zalogowanie')
    this.name = 'AuthRequiredError'
  }
}

function isGuardedChatResponse(body: unknown): body is GuardedChatResponse {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as GuardedChatResponse).requestId === 'string' &&
    typeof (body as GuardedChatResponse).action === 'string' &&
    Array.isArray((body as GuardedChatResponse).trace)
  )
}

/** Zdarzenie dla AuthProvider: sesja wygasła albo jej nie ma — pokaż ekran logowania. */
export const AUTH_REQUIRED_EVENT = 'auth:required'

function authRequired(): AuthRequiredError {
  window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
  return new AuthRequiredError()
}

/** Błąd logowania z backendu (`{ error: { code, message } }`): złe dane albo limit prób. */
export class LoginError extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
    this.name = 'LoginError'
  }
}

async function loginLive(login: string, password: string): Promise<CurrentUser> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ login, password }),
  })
  if (res.ok) return res.json() as Promise<CurrentUser>
  const body = (await res.json().catch(() => null)) as { error?: { code?: string; message?: string } } | null
  if (body?.error?.message) throw new LoginError(body.error.code ?? 'error', body.error.message)
  throw new LoginError('unavailable', `Logowanie nie powiodło się (HTTP ${res.status}). Czy backend działa?`)
}

/** 403 z /api/** — backend wymaga roli ADMIN (SecurityConfig). */
export class ForbiddenError extends Error {
  constructor() {
    super('Brak uprawnień — ten widok wymaga konta z rolą admin')
    this.name = 'ForbiddenError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (res.status === 401) throw authRequired()
  if (res.status === 403) throw new ForbiddenError()
  if (res.status === 404 && path.startsWith('/api/'))
    throw new Error(`Backend nie ma endpointu ${path.split('?')[0]} — działa starsza wersja? Przebuduj backend.`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`)
  return res.json() as Promise<T>
}

function auditQuery(params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  return query.toString()
}

export interface ChatParams {
  model: string
  messages: ChatMessage[]
  sessionId?: string
  signal?: AbortSignal
}

/**
 * POST /v1/chat/completions. Gateway zwraca GuardedChatResponse także dla 400/403/502,
 * więc to jest decyzja do pokazania, a nie wyjątek. Wyjątek leci tylko, gdy odpowiedź
 * nie jest naszym kontraktem (gateway wyłączony) albo brak sesji (401).
 */
async function chatLive({ model, messages, sessionId, signal }: ChatParams): Promise<GuardedChatResponse> {
  let res: Response
  try {
    res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(sessionId ? { 'X-Session-Id': sessionId } : {}),
      },
      body: JSON.stringify({ model, messages }),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new GatewayUnavailableError(null, 'Brak połączenia z gatewayem')
  }

  if (res.status === 401) throw authRequired()

  const text = await res.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    // nie-JSON, np. strona błędu proxy
  }
  if (isGuardedChatResponse(body)) return body

  throw new GatewayUnavailableError(
    res.status,
    `Gateway odpowiedział HTTP ${res.status} spoza kontraktu${text ? `: ${text.slice(0, 200)}` : ''}`,
  )
}

// TODO: ścieżki /api/* to propozycja z docs/frontend-flows-and-api.md — dopasować, gdy powstaną.
export const api = {
  /** GET /api/auth/me — 401 (AuthRequiredError) oznacza brak sesji. */
  me(): Promise<CurrentUser> {
    if (isMocked('auth')) return mocks.me()
    return request('/api/auth/me')
  },
  login(login: string, password: string): Promise<CurrentUser> {
    if (isMocked('auth')) return mocks.login(login, password)
    return loginLive(login, password)
  },
  async logout(): Promise<void> {
    if (isMocked('auth')) return mocks.logout()
    await fetch('/api/auth/logout', { method: 'POST' })
  },
  chat(params: ChatParams): Promise<GuardedChatResponse> {
    if (isMocked('chat')) return mocks.chat(params)
    return chatLive(params)
  },
  /** Do czasu GET /api/models lista pochodzi z VITE_MODELS (tagi z backend application.yml). */
  async models(): Promise<ModelOption[]> {
    const tags = (import.meta.env.VITE_MODELS ?? DEFAULT_MODELS)
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean)
    return tags.map((tag) => ({ tag, provider: 'ollama', enabled: true }))
  },
  stats(): Promise<DashboardStats> {
    if (isMocked('stats')) return mocks.stats()
    return request('/api/stats')
  },
  /** GET /api/audit/events — najnowsze pierwsze; `before` = `nextCursor` z poprzedniej strony. */
  auditEvents(filters: AuditFilters = {}, before?: number | null): Promise<AuditPage> {
    if (isMocked('audit')) return mocks.auditEvents(filters, before)
    return request(`/api/audit/events?${auditQuery({ ...filters, before: before ?? undefined, limit: 50 })}`)
  },
  auditEvent(requestId: string): Promise<AuditEvent> {
    if (isMocked('audit')) return mocks.auditEvent(requestId)
    return request(`/api/audit/events/${encodeURIComponent(requestId)}`)
  },
  auditVerify(): Promise<AuditVerifyResult> {
    if (isMocked('audit')) return mocks.auditVerify()
    return request('/api/audit/verify')
  },
  auditExportUrl(format: 'csv' | 'json', filters: AuditFilters = {}): string {
    return `/api/audit/export?${auditQuery({ ...filters, format })}`
  },
  policy(): Promise<PolicyInfo> {
    if (isMocked('policy')) return mocks.policy()
    return request('/api/policy')
  },
  updatePolicy(raw: string): Promise<PolicyInfo> {
    if (isMocked('policy')) return mocks.policy(raw)
    return request('/api/policy', { method: 'PUT', body: JSON.stringify({ raw }) })
  },
}
