import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, AUTH_REQUIRED_EVENT } from '../api/client'
import type { CurrentUser } from '../api/types'
import { clearPersistedPlayground } from '../playground/PlaygroundContext'

type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous'; expired: boolean }
  | { status: 'authenticated'; user: CurrentUser }

interface AuthContextValue {
  state: AuthState
  login(login: string, password: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * Sesja przeglądarki (POST /api/auth/login → ciasteczko HttpOnly). Front nie trzyma żadnego
 * tokenu — o zalogowaniu decyduje GET /api/auth/me. Każde 401 z API (AUTH_REQUIRED_EVENT)
 * wraca do ekranu logowania.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  useEffect(() => {
    api
      .me()
      .then((user) => setState({ status: 'authenticated', user }))
      // 401 = brak sesji; inny błąd (backend wyłączony) też kończy na ekranie logowania,
      // a powód pokaże próba zalogowania.
      .catch(() => setState({ status: 'anonymous', expired: false }))
  }, [])

  useEffect(() => {
    const onAuthRequired = () =>
      setState((current) => (current.status === 'authenticated' ? { status: 'anonymous', expired: true } : current))
    window.addEventListener(AUTH_REQUIRED_EVENT, onAuthRequired)
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, onAuthRequired)
  }, [])

  const login = useCallback(async (login: string, password: string) => {
    const user = await api.login(login, password)
    setState({ status: 'authenticated', user })
  }, [])

  const logout = useCallback(async () => {
    await api.logout().catch(() => undefined)
    // Rozmowy mogą zawierać dane wrażliwe — nie zostawiamy ich w przeglądarce po wylogowaniu.
    clearPersistedPlayground()
    setState({ status: 'anonymous', expired: false })
  }, [])

  return <AuthContext.Provider value={{ state, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth poza AuthProvider')
  return ctx
}

/** Zalogowany użytkownik — tylko pod <RequireAuth>, gdzie stan jest zawsze `authenticated`. */
export function useCurrentUser(): CurrentUser {
  const { state } = useAuth()
  if (state.status !== 'authenticated') throw new Error('useCurrentUser bez zalogowania')
  return state.user
}
