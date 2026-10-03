import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, AuthRequiredError, ForbiddenError } from '../api/client'
import type { AuditEvent, AuditFilters, AuditVerifyResult, GuardedChatResponse } from '../api/types'
import ActionBadge from '../components/ActionBadge'
import DecisionXray from '../components/DecisionXray'
import PageHeader from '../components/PageHeader'

const FILTER_KEYS = ['action', 'principal', 'model', 'blockedBy', 'sessionId'] as const
const ACTIONS = ['allow', 'monitor', 'redact', 'require_approval', 'block']
const REFRESH_MS = 5000

function describeError(err: unknown): string {
  if (err instanceof ForbiddenError) return err.message
  if (err instanceof AuthRequiredError) return 'Brak zalogowania (401) — zaloguj się kontem admin.'
  return String(err)
}

/** Rekord audytu w kształcie odpowiedzi czatu, żeby użyć tego samego X-ray co w Playground. */
function asDecision(e: AuditEvent): GuardedChatResponse {
  return { requestId: e.requestId, action: e.action, message: null, blockedBy: e.blockedBy, trace: e.trace, usage: e.usage }
}

export default function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const filters: AuditFilters = Object.fromEntries(
    FILTER_KEYS.map((k) => [k, params.get(k) ?? undefined]).filter(([, v]) => v),
  )
  const filterKey = FILTER_KEYS.map((k) => params.get(k) ?? '').join('|')
  const selectedId = params.get('requestId')

  const [events, setEvents] = useState<AuditEvent[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [verify, setVerify] = useState<AuditVerifyResult | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [selected, setSelected] = useState<AuditEvent | null>(null)

  // filterKey (string) zamiast obiektu filters: nowy obiekt przy każdym renderze restartowałby efekty.
  const loadFirstPage = useCallback(() => api.auditEvents(filters), [filterKey])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    loadFirstPage()
      .then((page) => {
        if (cancelled) return
        setEvents(page.items)
        setNextCursor(page.nextCursor)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [loadFirstPage])

  // Nowe rekordy: odświeżamy pierwszą stronę i dokładamy tylko te, których jeszcze nie ma.
  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(() => {
      loadFirstPage()
        .then((page) =>
          setEvents((current) => {
            const newest = current[0]?.seq ?? 0
            const fresh = page.items.filter((e) => e.seq > newest)
            return fresh.length ? [...fresh, ...current] : current
          }),
        )
        .catch(() => undefined)
    }, REFRESH_MS)
    return () => clearInterval(timer)
  }, [autoRefresh, loadFirstPage])

  useEffect(() => {
    if (!selectedId) {
      setSelected(null)
      return
    }
    const local = events.find((e) => e.requestId === selectedId)
    if (local) setSelected(local)
    else api.auditEvent(selectedId).then(setSelected).catch(() => setSelected(null))
  }, [selectedId, events])

  async function loadMore() {
    if (nextCursor === null) return
    try {
      const page = await api.auditEvents(filters, nextCursor)
      setEvents((current) => [...current, ...page.items])
      setNextCursor(page.nextCursor)
    } catch (err) {
      setError(describeError(err))
    }
  }

  async function runVerify() {
    setVerifying(true)
    try {
      setVerify(await api.auditVerify())
    } catch (err) {
      setError(describeError(err))
    } finally {
      setVerifying(false)
    }
  }

  function applyFilters(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    const next = new URLSearchParams()
    for (const key of FILTER_KEYS) {
      const value = String(form.get(key) ?? '').trim()
      if (value) next.set(key, value)
    }
    setParams(next)
  }

  function filterBy(key: (typeof FILTER_KEYS)[number], value: string | null) {
    if (!value) return
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.delete('requestId')
    setParams(next)
  }

  function select(requestId: string | null) {
    const next = new URLSearchParams(params)
    if (requestId) next.set('requestId', requestId)
    else next.delete('requestId')
    setParams(next)
  }

  return (
    <div className="flex h-[calc(100vh-3rem)] flex-col">
      <PageHeader
        title="Audit log"
        subtitle="Każda decyzja gatewaya (także allow). Bez treści promptów i odpowiedzi — tylko metadane i ścieżka kontroli."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <button onClick={runVerify} disabled={verifying} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700 disabled:opacity-50">
          {verifying ? 'Sprawdzam…' : 'Sprawdź integralność'}
        </button>
        {verify &&
          (verify.valid ? (
            <span className="rounded bg-emerald-900/40 px-2 py-1 text-xs text-emerald-300">
              Łańcuch nienaruszony · {verify.checked} rekordów
            </span>
          ) : (
            <span className="rounded bg-red-900/50 px-2 py-1 text-xs text-red-300">
              Naruszony od seq {verify.brokenAtSeq} ({verify.reason})
            </span>
          ))}
        <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-400">
          <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
          odświeżaj co {REFRESH_MS / 1000} s
        </label>
        <a href={api.auditExportUrl('csv', filters)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
          Eksport CSV
        </a>
        <a href={api.auditExportUrl('json', filters)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
          Eksport JSON
        </a>
      </div>

      <form key={filterKey} onSubmit={applyFilters} className="mb-3 flex flex-wrap items-end gap-2 text-sm">
        <select name="action" defaultValue={filters.action ?? ''} className="rounded bg-slate-800 px-2 py-1.5">
          <option value="">każda akcja</option>
          {ACTIONS.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
        {(['principal', 'model', 'blockedBy', 'sessionId'] as const).map((key) => (
          <input
            key={key}
            name={key}
            defaultValue={filters[key] ?? ''}
            placeholder={{ principal: 'użytkownik', model: 'model', blockedBy: 'zablokowane przez', sessionId: 'sesja' }[key]}
            className="w-40 rounded bg-slate-800 px-2 py-1.5"
          />
        ))}
        <button className="rounded bg-indigo-600 px-3 py-1.5 font-medium">Filtruj</button>
        {Object.keys(filters).length > 0 && (
          <button type="button" onClick={() => setParams(new URLSearchParams())} className="px-2 py-1.5 text-slate-400 hover:text-slate-200">
            wyczyść
          </button>
        )}
      </form>

      {error && <p className="mb-3 rounded bg-red-950/50 px-3 py-2 text-sm text-red-300">{error}</p>}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="min-h-0 overflow-auto rounded-lg border border-slate-800">
          <table className="w-full text-left text-sm">
            <thead className="sticky top-0 bg-slate-900 text-xs uppercase text-slate-400">
              <tr>
                <th className="px-3 py-2">#</th>
                <th className="px-3 py-2">Czas</th>
                <th className="px-3 py-2">Użytkownik</th>
                <th className="px-3 py-2">Model</th>
                <th className="px-3 py-2">Akcja</th>
                <th className="px-3 py-2">Powód</th>
                <th className="px-3 py-2 text-right">Latencja</th>
                <th className="px-3 py-2 text-right">Tokeny</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr
                  key={e.seq}
                  onClick={() => select(e.requestId)}
                  className={`cursor-pointer border-t border-slate-800 ${e.requestId === selectedId ? 'bg-indigo-950/50' : 'hover:bg-slate-900'}`}
                >
                  <td className="px-3 py-2 font-mono text-xs text-slate-500">{e.seq}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-slate-400" title={e.timestamp}>
                    {new Date(e.timestamp).toLocaleString()}
                  </td>
                  <td className="px-3 py-2">
                    <FilterLink onClick={() => filterBy('principal', e.principal)}>{e.principal ?? '—'}</FilterLink>
                    {e.role && <span className="ml-1 text-xs text-slate-500">{e.role}</span>}
                  </td>
                  <td className="max-w-[12rem] truncate px-3 py-2" title={e.model ?? ''}>
                    <FilterLink onClick={() => filterBy('model', e.model)}>{e.model ?? '—'}</FilterLink>
                  </td>
                  <td className="px-3 py-2">
                    <ActionBadge action={e.action} />
                  </td>
                  <td className="px-3 py-2">
                    {e.blockedBy ? (
                      <FilterLink onClick={() => filterBy('blockedBy', e.blockedBy)}>
                        <code className="text-xs">{e.blockedBy}</code>
                      </FilterLink>
                    ) : (
                      <span className="text-slate-600">—</span>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-right text-slate-400">{e.latencyMs} ms</td>
                  <td className="whitespace-nowrap px-3 py-2 text-right text-slate-400">
                    {e.usage ? e.usage.promptTokens + e.usage.completionTokens : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <p className="p-4 text-sm text-slate-500">Ładowanie…</p>}
          {!loading && events.length === 0 && !error && <p className="p-4 text-sm text-slate-500">Brak rekordów.</p>}
          {nextCursor !== null && (
            <button onClick={loadMore} className="m-3 rounded bg-slate-800 px-3 py-1.5 text-sm hover:bg-slate-700">
              Starsze rekordy
            </button>
          )}
        </div>

        <aside className="min-h-0 overflow-auto rounded-lg border border-slate-800 bg-slate-900 p-4">
          {!selected && <p className="text-sm text-slate-500">Wybierz rekord, żeby zobaczyć ścieżkę kontroli.</p>}
          {selected && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-medium">Rekord #{selected.seq}</h3>
                <button onClick={() => select(null)} className="text-sm text-slate-400 hover:text-slate-200">
                  zamknij
                </button>
              </div>
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
                <Field label="czas">{new Date(selected.timestamp).toLocaleString()}</Field>
                <Field label="użytkownik">
                  {selected.principal ?? '—'} {selected.role && `(${selected.role})`}
                </Field>
                <Field label="sesja">
                  <FilterLink onClick={() => filterBy('sessionId', selected.sessionId)}>{selected.sessionId ?? '—'}</FilterLink>
                </Field>
                <Field label="model">{selected.model ?? '—'}</Field>
                <Field label="HTTP">{selected.httpStatus}</Field>
                <Field label="wiadomości">{selected.messageCount}</Field>
                <Field label="hash">
                  <span className="break-all font-mono text-slate-500">{selected.recordHash}</span>
                </Field>
              </dl>
              <DecisionXray response={asDecision(selected)} clientLatencyMs={selected.latencyMs} />
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

function FilterLink({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onClick()
      }}
      className="text-left hover:text-indigo-300 hover:underline"
      title="filtruj po tej wartości"
    >
      {children}
    </button>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-slate-300">{children}</dd>
    </>
  )
}
