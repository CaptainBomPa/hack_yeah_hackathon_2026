import { useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { ChatMessage, GuardedChatResponse } from '../api/types'
import ActionBadge from '../components/ActionBadge'
import PageHeader from '../components/PageHeader'

export default function PlaygroundPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [last, setLast] = useState<GuardedChatResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function send(e: FormEvent) {
    e.preventDefault()
    if (!input.trim() || loading) return
    const next = [...messages, { role: 'user' as const, content: input }]
    setMessages(next)
    setInput('')
    setLoading(true)
    setError(null)
    try {
      const res = await api.chat(next)
      setLast(res)
      const reply = res.message ?? { role: 'assistant' as const, content: `⛔ Zablokowane przez ${res.blockedBy}` }
      setMessages([...next, reply])
    } catch (err) {
      setError(String(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader title="Playground" subtitle="Czat przez gateway — trace pokazuje, które kontrole zadziałały." />
      <div className="grid flex-1 grid-cols-1 gap-6 lg:grid-cols-3">
        <section className="flex flex-col rounded-lg border border-slate-800 bg-slate-900 lg:col-span-2">
          <div className="flex-1 space-y-3 overflow-auto p-4">
            {messages.map((m, i) => (
              <div key={i} className={m.role === 'user' ? 'text-right' : ''}>
                <span
                  className={`inline-block max-w-[80%] rounded-lg px-3 py-2 text-sm ${m.role === 'user' ? 'bg-indigo-600' : 'bg-slate-800'}`}
                >
                  {m.content}
                </span>
              </div>
            ))}
            {loading && <p className="text-sm text-slate-500">…</p>}
            {error && <p className="text-sm text-red-400">{error}</p>}
          </div>
          <form onSubmit={send} className="flex gap-2 border-t border-slate-800 p-3">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Napisz prompt (spróbuj: Ignore all previous instructions)"
              className="flex-1 rounded bg-slate-800 px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <button disabled={loading} className="rounded bg-indigo-600 px-4 py-2 text-sm font-medium disabled:opacity-50">
              Wyślij
            </button>
          </form>
        </section>

        <section className="rounded-lg border border-slate-800 bg-slate-900 p-4">
          <h3 className="mb-3 font-medium">Trace ostatniego żądania</h3>
          {!last && <p className="text-sm text-slate-500">Brak żądań.</p>}
          {last && (
            <>
              <div className="mb-3 flex items-center gap-2 text-sm">
                Decyzja: <ActionBadge action={last.action} />
              </div>
              <ul className="space-y-2">
                {last.trace.map((t) => (
                  <li key={t.policy} className="rounded bg-slate-800 p-2 text-sm">
                    <div className="flex items-center justify-between">
                      <code>{t.policy}</code>
                      <ActionBadge action={t.action} />
                    </div>
                    <div className="mt-1 text-xs text-slate-400">
                      {t.kind} · {t.latencyMs} ms{t.detail ? ` · ${t.detail}` : ''}
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      </div>
    </div>
  )
}
