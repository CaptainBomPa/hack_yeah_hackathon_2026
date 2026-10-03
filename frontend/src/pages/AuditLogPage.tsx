import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, AuthRequiredError, ForbiddenError } from '../api/client'
import type { AuditEvent, AuditFacets, AuditFilters, AuditVerifyResult, GuardAction, GuardedChatResponse } from '../api/types'
import ActionBadge from '../components/ActionBadge'
import DecisionXray from '../components/DecisionXray'
import MultiSelect from '../components/MultiSelect'
import PageHeader from '../components/PageHeader'

/** Filtry wielowartościowe (lista z checkboxami); sesja osobno — wyszukiwanie „zawiera”. */
const LIST_FILTERS = ['action', 'principal', 'model', 'blockedBy'] as const
type ListFilter = (typeof LIST_FILTERS)[number]
const FILTER_LABELS: Record<ListFilter, string> = {
  action: 'Action',
  principal: 'User',
  model: 'Model',
  blockedBy: 'Blocked by',
}
const FACET_OF: Record<ListFilter, keyof AuditFacets> = {
  action: 'actions',
  principal: 'principals',
  model: 'models',
  blockedBy: 'blockedBy',
}
const REFRESH_MS = 5000
const SESSION_DEBOUNCE_MS = 400

function describeError(err: unknown): string {
  if (err instanceof ForbiddenError) return err.message
  if (err instanceof AuthRequiredError) return 'Not signed in (401) — sign in with an admin account.'
  return String(err)
}

/** Rekord audytu w kształcie odpowiedzi czatu, żeby użyć tego samego X-ray co w Playground. */
function asDecision(e: AuditEvent): GuardedChatResponse {
  return { requestId: e.requestId, action: e.action, message: null, blockedBy: e.blockedBy, trace: e.trace, usage: e.usage, policyVersion: e.policyVersion }
}

export default function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const filters: AuditFilters = {
    ...Object.fromEntries(LIST_FILTERS.map((k) => [k, params.getAll(k)]).filter(([, v]) => v.length)),
    ...(params.get('sessionId') ? { sessionId: params.get('sessionId')! } : {}),
  }
  const filterKey = [...LIST_FILTERS, 'sessionId'].map((k) => params.getAll(k).join(',')).join('|')
  const hasFilters = LIST_FILTERS.some((k) => params.has(k)) || params.has('sessionId')
  const selectedId = params.get('requestId')

  const [facets, setFacets] = useState<AuditFacets>({ actions: [], principals: [], models: [], blockedBy: [] })
  const [sessionInput, setSessionInput] = useState(params.get('sessionId') ?? '')

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

  const loadFacets = useCallback(() => {
    api.auditFacets().then(setFacets).catch(() => undefined)
  }, [])
  useEffect(loadFacets, [loadFacets])

  // Pole sesji: filtr idzie do URL po chwili bez pisania, nie przy każdym znaku.
  useEffect(() => {
    const current = params.get('sessionId') ?? ''
    if (sessionInput.trim() === current) return
    const timer = setTimeout(() => {
      const next = new URLSearchParams(params)
      if (sessionInput.trim()) next.set('sessionId', sessionInput.trim())
      else next.delete('sessionId')
      next.delete('requestId')
      setParams(next, { replace: true })
    }, SESSION_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [sessionInput, params, setParams])

  function setListFilter(key: ListFilter, values: string[]) {
    const next = new URLSearchParams(params)
    next.delete(key)
    values.forEach((v) => next.append(key, v))
    next.delete('requestId')
    setParams(next)
  }

  /** Kliknięcie wartości w tabeli dokłada ją do filtra. */
  function filterBy(key: ListFilter | 'sessionId', value: string | null) {
    if (!value) return
    if (key === 'sessionId') {
      setSessionInput(value)
      return
    }
    const current = params.getAll(key)
    if (!current.includes(value)) setListFilter(key, [...current, value])
  }

  function clearFilters() {
    setSessionInput('')
    setParams(new URLSearchParams())
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
        subtitle="Every gateway decision (including allow). No prompt or response content — metadata and control path only."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <button onClick={runVerify} disabled={verifying} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700 disabled:opacity-50">
          {verifying ? 'Verifying…' : 'Verify integrity'}
        </button>
        {verify &&
          (verify.valid ? (
            <span className="rounded bg-emerald-900/40 px-2 py-1 text-xs text-emerald-300">
              Chain intact · {verify.checked} records
            </span>
          ) : (
            <span className="rounded bg-red-900/50 px-2 py-1 text-xs text-red-300">
              Broken at seq {verify.brokenAtSeq} ({verify.reason})
            </span>
          ))}
        <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-400">
          <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
          refresh every {REFRESH_MS / 1000} s
        </label>
        <a href={api.auditExportUrl('csv', filters)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
          Export CSV
        </a>
        <a href={api.auditExportUrl('json', filters)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
          Export JSON
        </a>
      </div>

      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        {LIST_FILTERS.map((key) => (
          <MultiSelect
            key={key}
            label={FILTER_LABELS[key]}
            options={facets[FACET_OF[key]]}
            selected={params.getAll(key)}
            onChange={(values) => setListFilter(key, values)}
            onOpen={loadFacets}
            renderOption={key === 'action' ? (value) => <ActionBadge action={value as GuardAction} /> : undefined}
          />
        ))}
        <input
          value={sessionInput}
          onChange={(e) => setSessionInput(e.target.value)}
          placeholder="session contains…"
          className="w-44 rounded bg-slate-800 px-2 py-1.5"
        />
        {hasFilters && (
          <button type="button" onClick={clearFilters} className="px-2 py-1.5 text-slate-400 hover:text-slate-200">
            clear filters
          </button>
        )}
      </div>

      {error && <p className="mb-3 rounded bg-red-950/50 px-3 py-2 text-sm text-red-300">{error}</p>}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="min-h-0 overflow-auto rounded-lg border border-slate-800">
          <table className="w-full text-left text-sm">
            <thead className="sticky top-0 bg-slate-900 text-xs uppercase text-slate-400">
              <tr>
                <th className="px-3 py-2">#</th>
                <th className="px-3 py-2">Time</th>
                <th className="px-3 py-2">User</th>
                <th className="px-3 py-2">Model</th>
                <th className="px-3 py-2">Action</th>
                <th className="px-3 py-2">Reason</th>
                <th className="px-3 py-2 text-right">Latency</th>
                <th className="px-3 py-2 text-right">Tokens</th>
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
                    {new Date(e.timestamp).toLocaleString('en-GB')}
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
          {loading && <p className="p-4 text-sm text-slate-500">Loading…</p>}
          {!loading && events.length === 0 && !error && <p className="p-4 text-sm text-slate-500">No records.</p>}
          {nextCursor !== null && (
            <button onClick={loadMore} className="m-3 rounded bg-slate-800 px-3 py-1.5 text-sm hover:bg-slate-700">
              Older records
            </button>
          )}
        </div>

        <aside className="min-h-0 overflow-auto rounded-lg border border-slate-800 bg-slate-900 p-4">
          {!selected && <p className="text-sm text-slate-500">Select a record to see its control path.</p>}
          {selected && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-medium">Record #{selected.seq}</h3>
                <button onClick={() => select(null)} className="text-sm text-slate-400 hover:text-slate-200">
                  close
                </button>
              </div>
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
                <Field label="time">{new Date(selected.timestamp).toLocaleString('en-GB')}</Field>
                <Field label="user">
                  {selected.principal ?? '—'} {selected.role && `(${selected.role})`}
                </Field>
                <Field label="session">
                  <FilterLink onClick={() => filterBy('sessionId', selected.sessionId)}>{selected.sessionId ?? '—'}</FilterLink>
                </Field>
                <Field label="model">{selected.model ?? '—'}</Field>
                <Field label="HTTP">{selected.httpStatus}</Field>
                <Field label="messages">{selected.messageCount}</Field>
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
      title="filter by this value"
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
