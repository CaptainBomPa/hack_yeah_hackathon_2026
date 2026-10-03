import { useState, type FormEvent } from 'react'
import { isMocked, LoginError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

/** Logowanie kontem lokalnym (backend/config/users.yaml → baza). Po sukcesie zostajemy na tej samej ścieżce. */
export default function LoginPage({ expired }: { expired: boolean }) {
  const { login } = useAuth()
  const [form, setForm] = useState({ login: '', password: '' })
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login(form.login.trim(), form.password)
    } catch (err) {
      setError(err instanceof LoginError ? err.message : 'Could not reach the gateway.')
      setForm((f) => ({ ...f, password: '' }))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <form onSubmit={onSubmit} className="w-full max-w-sm space-y-4 rounded-lg border border-slate-800 bg-slate-900 p-6">
        <div>
          <h1 className="text-xl font-semibold">AI Control Layer</h1>
          <p className="mt-1 text-sm text-slate-400">Sign in with your gateway account.</p>
        </div>

        {expired && (
          <p className="rounded bg-amber-900/40 px-3 py-2 text-sm text-amber-300">Your session has expired — please sign in again.</p>
        )}

        <label className="block space-y-1 text-sm">
          <span className="text-slate-300">Username</span>
          <input
            autoFocus
            autoComplete="username"
            value={form.login}
            onChange={(e) => setForm((f) => ({ ...f, login: e.target.value }))}
            className="w-full rounded bg-slate-800 px-3 py-2 outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </label>
        <label className="block space-y-1 text-sm">
          <span className="text-slate-300">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={form.password}
            onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
            className="w-full rounded bg-slate-800 px-3 py-2 outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </label>

        {error && (
          <p role="alert" className="rounded bg-red-950/50 px-3 py-2 text-sm text-red-300">
            {error}
          </p>
        )}

        <button
          disabled={submitting || !form.login.trim() || !form.password}
          className="w-full rounded bg-indigo-600 py-2 text-sm font-medium disabled:opacity-50"
        >
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="text-xs text-slate-500">
          {isMocked('auth')
            ? 'Mock mode: any password; username chat* = chat role, anything else = admin.'
            : 'Demo accounts are defined in backend/config/users.yaml.'}
        </p>
      </form>
    </div>
  )
}
