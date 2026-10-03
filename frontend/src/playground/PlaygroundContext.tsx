import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, AuthRequiredError, GatewayUnavailableError } from '../api/client'
import type { ChatMessage, GuardedChatResponse } from '../api/types'
import { newId } from '../lib/id'

export interface Turn {
  id: string
  user: string
  response?: GuardedChatResponse
  latencyMs?: number
  error?: string
  cancelled?: boolean
}

export const CUSTOM_MODEL = '__custom__'

/** Część stanu, która przetrwa przełączanie zakładek i odświeżenie strony. */
interface Persisted {
  sessionId: string
  turns: Turn[]
  selectedId: string | null
  input: string
  modelChoice: string
  customModel: string
}

interface PlaygroundValue extends Persisted {
  pendingSince: number | null
  setSelectedId(id: string | null): void
  setInput(text: string): void
  setModelChoice(choice: string): void
  setCustomModel(tag: string): void
  send(text: string, model: string): void
  cancel(): void
  newSession(): void
}

const STORAGE_PREFIX = 'playground:'

/** sessionStorage: tylko ta karta przeglądarki, znika po jej zamknięciu i przy wylogowaniu. */
export function clearPersistedPlayground() {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(STORAGE_PREFIX))
      .forEach((key) => sessionStorage.removeItem(key))
  } catch {
    // storage niedostępny (tryb prywatny) — nic do czyszczenia
  }
}

function load(key: string): Persisted {
  const fresh: Persisted = { sessionId: newId(), turns: [], selectedId: null, input: '', modelChoice: '', customModel: '' }
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return fresh
    const saved = { ...fresh, ...(JSON.parse(raw) as Partial<Persisted>) }
    // Żądanie w toku przy odświeżeniu przepadło — oznaczamy je zamiast wiecznego "czekam".
    saved.turns = saved.turns.map((t) =>
      t.response || t.error || t.cancelled ? t : { ...t, error: 'Przerwane przez odświeżenie strony.' },
    )
    return saved
  } catch {
    return fresh
  }
}

/** Tury zablokowane nie trafiają do historii wysyłanej modelowi — inaczej każde kolejne pytanie byłoby blokowane. */
function historyFrom(turns: Turn[]): ChatMessage[] {
  return turns.flatMap((t) =>
    t.response?.message ? [{ role: 'user' as const, content: t.user }, t.response.message] : [],
  )
}

const PlaygroundContext = createContext<PlaygroundValue | null>(null)

/**
 * Rozmowa w Playground żyje tutaj, nad routerem (w Layout), a nie w PlaygroundPage — dzięki temu
 * nie znika przy przejściu do innej zakładki, a żądanie w toku dokończy się w tle.
 */
export function PlaygroundProvider({ owner, children }: { owner: string; children: ReactNode }) {
  const storageKey = STORAGE_PREFIX + owner
  const [state, setState] = useState<Persisted>(() => load(storageKey))
  const [pendingSince, setPendingSince] = useState<number | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const turnsRef = useRef(state.turns)
  turnsRef.current = state.turns

  useEffect(() => {
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(state))
    } catch {
      // brak storage albo przekroczony limit — rozmowa przeżyje i tak przełączanie zakładek
    }
  }, [storageKey, state])

  const patch = useCallback((p: Partial<Persisted>) => setState((s) => ({ ...s, ...p })), [])

  const send = useCallback(
    async (text: string, model: string) => {
      if (!text.trim() || abortRef.current || !model) return
      const turn: Turn = { id: newId(), user: text }
      const history = historyFrom(turnsRef.current)
      setState((s) => ({ ...s, turns: [...s.turns, turn], selectedId: turn.id, input: '' }))

      const controller = new AbortController()
      abortRef.current = controller
      const started = Date.now()
      setPendingSince(started)

      const update = (p: Partial<Turn>) =>
        setState((s) => ({ ...s, turns: s.turns.map((t) => (t.id === turn.id ? { ...t, ...p } : t)) }))

      try {
        const response = await api.chat({
          model,
          messages: [...history, { role: 'user', content: text }],
          sessionId: state.sessionId,
          signal: controller.signal,
        })
        update({ response, latencyMs: Date.now() - started })
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') update({ cancelled: true })
        else if (err instanceof AuthRequiredError) update({ error: 'Sesja wygasła albo brak logowania (401). Zaloguj się ponownie.' })
        else if (err instanceof GatewayUnavailableError) update({ error: `${err.message}. Czy backend działa na :8000?` })
        else update({ error: String(err) })
      } finally {
        abortRef.current = null
        setPendingSince(null)
      }
    },
    [state.sessionId],
  )

  const cancel = useCallback(() => abortRef.current?.abort(), [])

  const newSession = useCallback(() => {
    abortRef.current?.abort()
    setState((s) => ({ ...s, sessionId: newId(), turns: [], selectedId: null }))
  }, [])

  const value: PlaygroundValue = {
    ...state,
    pendingSince,
    setSelectedId: (selectedId) => patch({ selectedId }),
    setInput: (input) => patch({ input }),
    setModelChoice: (modelChoice) => patch({ modelChoice }),
    setCustomModel: (customModel) => patch({ customModel }),
    send,
    cancel,
    newSession,
  }
  return <PlaygroundContext.Provider value={value}>{children}</PlaygroundContext.Provider>
}

export function usePlayground(): PlaygroundValue {
  const ctx = useContext(PlaygroundContext)
  if (!ctx) throw new Error('usePlayground poza PlaygroundProvider')
  return ctx
}
