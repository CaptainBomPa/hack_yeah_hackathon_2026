import type { AuditEvent, ChatMessage, DashboardStats, GuardedChatResponse, PolicyInfo } from './types'
import * as mocks from './mocks'

export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS === 'true'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`)
  return res.json() as Promise<T>
}

// TODO: ścieżki /api/* to propozycja — dopasować do endpointów gatewaya, gdy powstaną.
export const api = {
  chat(messages: ChatMessage[], model = 'qwen2.5:0.5b'): Promise<GuardedChatResponse> {
    if (USE_MOCKS) return mocks.chat(messages)
    return request('/v1/chat/completions', { method: 'POST', body: JSON.stringify({ model, messages }) })
  },
  stats(): Promise<DashboardStats> {
    if (USE_MOCKS) return mocks.stats()
    return request('/api/stats')
  },
  auditEvents(): Promise<AuditEvent[]> {
    if (USE_MOCKS) return mocks.auditEvents()
    return request('/api/audit')
  },
  auditExportUrl(format: 'csv' | 'json'): string {
    return `/api/audit/export?format=${format}`
  },
  policy(): Promise<PolicyInfo> {
    if (USE_MOCKS) return mocks.policy()
    return request('/api/policy')
  },
  updatePolicy(raw: string): Promise<PolicyInfo> {
    if (USE_MOCKS) return mocks.policy(raw)
    return request('/api/policy', { method: 'PUT', body: JSON.stringify({ raw }) })
  },
}
