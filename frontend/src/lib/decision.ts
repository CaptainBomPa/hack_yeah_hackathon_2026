import type { ControlTrace, GuardAction } from '../api/types'

/** Wpis trace o czyszczeniu wcześniejszych wiadomości (ConversationGuard) — nie decyduje o akcji żądania. */
const HISTORY_POLICY = 'input.history'

/**
 * Kontrole, które zredagowały bieżącą treść — powód akcji `redact` (blockedBy jest tylko dla blokad).
 * Ta sama reguła co filtr `reason` w backendzie (AuditQuery).
 */
export function redactedBy(action: GuardAction, trace: ControlTrace[]): string[] {
  if (action !== 'redact') return []
  return [...new Set(trace.filter((t) => t.action === 'redact' && t.policy !== HISTORY_POLICY).map((t) => t.policy))]
}
