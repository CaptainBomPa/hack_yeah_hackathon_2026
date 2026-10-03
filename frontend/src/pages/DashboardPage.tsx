import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, AuthRequiredError, ForbiddenError } from '../api/client'
import type { DashboardData, DashboardWindow } from '../api/types'
import ActionBadge from '../components/ActionBadge'
import PageHeader from '../components/PageHeader'

const WINDOWS: { id: DashboardWindow; label: string }[] = [
  { id: '1h', label: 'Last hour' },
  { id: '24h', label: '24 hours' },
  { id: '7d', label: '7 days' },
]
const REFRESH_MS = 10_000

/**
 * Akcje to statusy, nie dowolne kategorie: kolory z palety statusów (good / warning / critical)
 * + niebieski dla monitor. Zestaw sprawdzony walidatorem palety na powierzchni #0f172a
 * (CVD i normal-vision OK); zawsze z etykietą w legendzie, nigdy sam kolor.
 * require_approval pominięte na wykresie — backend go nie zwraca (bez approval flow = block).
 */
const SERIES = [
  { key: 'allow', label: 'allow', color: '#0ca30c' },
  { key: 'monitor', label: 'monitor', color: '#3987e5' },
  { key: 'redact', label: 'redact', color: '#fab219' },
  { key: 'block', label: 'block', color: '#d03b3b' },
] as const

const SURFACE = '#0f172a' // slate-900 — tło kart z wykresami
const GRID = '#1e293b' // slate-800
const MUTED = '#94a3b8' // slate-400
const BAR_BLUE = '#3987e5'

const fmt = new Intl.NumberFormat('en-US')

function describeError(err: unknown): string {
  if (err instanceof ForbiddenError) return err.message
  if (err instanceof AuthRequiredError) return 'Not signed in (401).'
  return String(err)
}

function tickLabel(iso: string, window: DashboardWindow): string {
  const d = new Date(iso)
  if (window === '7d') return d.toLocaleDateString('en-GB', { weekday: 'short', hour: '2-digit' })
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

export default function DashboardPage() {
  const [params, setParams] = useSearchParams()
  const window = (WINDOWS.find((w) => w.id === params.get('window'))?.id ?? '1h') as DashboardWindow
  const [data, setData] = useState<DashboardData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(true)

  const load = useCallback(() => {
    api
      .dashboard(window)
      .then((d) => {
        setData(d)
        setError(null)
        setUpdatedAt(new Date())
      })
      .catch((err) => setError(describeError(err)))
  }, [window])

  useEffect(() => {
    setData(null)
    load()
  }, [load])

  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(load, REFRESH_MS)
    return () => clearInterval(timer)
  }, [autoRefresh, load])

  return (
    <div className="space-y-4">
      <PageHeader title="Dashboard" subtitle="Metrics computed live from the gateway audit log and budget counters." />

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <div className="flex rounded bg-slate-800 p-0.5">
          {WINDOWS.map((w) => (
            <button
              key={w.id}
              onClick={() => setParams({ window: w.id })}
              className={`rounded px-3 py-1 ${w.id === window ? 'bg-slate-600 text-white' : 'text-slate-400 hover:text-slate-200'}`}
            >
              {w.label}
            </button>
          ))}
        </div>
        <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-400">
          <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
          refresh every {REFRESH_MS / 1000} s
        </label>
        {updatedAt && <span className="text-xs text-slate-500">updated {updatedAt.toLocaleTimeString('en-GB')}</span>}
      </div>

      {error && <p className="rounded bg-red-950/50 px-3 py-2 text-sm text-red-300">{error}</p>}
      {!data && !error && <p className="text-sm text-slate-500">Loading…</p>}
      {data?.truncated && (
        <p className="rounded bg-amber-900/40 px-3 py-2 text-sm text-amber-300">
          This window has more records than the aggregation limit — numbers are a lower bound.
        </p>
      )}
      {data && <DashboardBody data={data} window={window} />}
    </div>
  )
}

function DashboardBody({ data, window }: { data: DashboardData; window: DashboardWindow }) {
  const navigate = useNavigate()
  const { requests, byAction, errors } = data.totals
  const blocked = byAction.block ?? 0
  const pctOf = (n: number) => (requests ? `${((n / requests) * 100).toFixed(1)}%` : '—')
  const timeline = data.timeline.map((b) => ({ start: b.start, ...b.byAction }))
  const maxHits = Math.max(1, ...data.controls.map((c) => c.count))

  return (
    <>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <StatTile label="Requests" value={fmt.format(requests)} />
        <StatTile label="Blocked" value={fmt.format(blocked)} note={`${pctOf(blocked)} of requests`} to={`/audit?action=block`} />
        <StatTile label="Redacted" value={fmt.format(byAction.redact ?? 0)} note={pctOf(byAction.redact ?? 0)} to="/audit?action=redact" />
        <StatTile
          label="Latency p50 / p95"
          value={data.latency.p50 === null ? '—' : `${fmt.format(data.latency.p50)} / ${fmt.format(data.latency.p95 ?? 0)} ms`}
          note={data.latency.samples ? `${data.latency.samples} model responses` : 'no model responses'}
        />
        <StatTile
          label="Tokens"
          value={fmt.format(data.tokens.prompt + data.tokens.completion)}
          note={`${fmt.format(data.tokens.prompt)} in · ${fmt.format(data.tokens.completion)} out`}
        />
        <StatTile label="Errors (5xx)" value={fmt.format(errors)} note={pctOf(errors)} />
      </div>

      {requests === 0 ? (
        <Card title="No traffic">
          <p className="text-sm text-slate-400">
            Nobody talked to the model in this window. Send a prompt from the <Link to="/playground" className="text-indigo-300 hover:underline">Playground</Link>.
          </p>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <Card title="Decisions over time">
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={timeline} margin={{ top: 4, right: 8, bottom: 0, left: -12 }} barCategoryGap="20%">
                <CartesianGrid stroke={GRID} vertical={false} />
                <XAxis
                  dataKey="start"
                  tickFormatter={(v: string) => tickLabel(v, window)}
                  stroke={MUTED}
                  fontSize={11}
                  tickLine={false}
                  axisLine={{ stroke: GRID }}
                  minTickGap={24}
                />
                <YAxis allowDecimals={false} stroke={MUTED} fontSize={11} tickLine={false} axisLine={false} />
                <Tooltip
                  cursor={{ fill: 'rgba(148,163,184,0.08)' }}
                  contentStyle={{ background: '#020617', border: '1px solid #334155', borderRadius: 6, fontSize: 12 }}
                  labelStyle={{ color: '#e2e8f0' }}
                  itemStyle={{ color: '#e2e8f0' }}
                  labelFormatter={(v: string) => new Date(v).toLocaleString('en-GB')}
                />
                {/* Tekst legendy w kolorze tekstu; kolor niesie tylko kwadracik obok. */}
                <Legend
                  wrapperStyle={{ fontSize: 12 }}
                  iconType="square"
                  formatter={(value: string) => <span style={{ color: '#cbd5e1' }}>{value}</span>}
                />
                {SERIES.map((s, i) => (
                  <Bar
                    key={s.key}
                    dataKey={s.key}
                    name={s.label}
                    stackId="decisions"
                    fill={s.color}
                    stroke={SURFACE}
                    strokeWidth={1}
                    radius={i === SERIES.length - 1 ? [4, 4, 0, 0] : undefined}
                    isAnimationActive={false}
                  />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </Card>

          <Card title="Most active controls" subtitle="Controls that blocked or changed a request (from the trace).">
            {data.controls.length === 0 && <p className="text-sm text-slate-500">No control fired in this window.</p>}
            <ul className="space-y-2">
              {data.controls.map((c) => (
                <li key={`${c.policy}-${c.action}`}>
                  <button
                    onClick={() => navigate(c.action === 'block' ? `/audit?blockedBy=${encodeURIComponent(c.policy)}` : `/audit?action=${c.action}`)}
                    className="group w-full text-left"
                    title="show in audit log"
                  >
                    <div className="mb-0.5 flex items-center justify-between gap-2 text-sm">
                      <span className="flex min-w-0 items-center gap-2">
                        <code className="truncate group-hover:text-indigo-300">{c.policy}</code>
                        <ActionBadge action={c.action} />
                      </span>
                      <span className="tabular-nums text-slate-300">{fmt.format(c.count)}</span>
                    </div>
                    <div className="h-2 rounded-r bg-slate-800">
                      <div className="h-2 rounded-r" style={{ width: `${(c.count / maxHits) * 100}%`, background: BAR_BLUE }} />
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          </Card>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card title="Token budget — today" subtitle="Daily limit per role from policy.yaml.">
          <ul className="space-y-3">
            {data.budgets.map((b) => (
              <BudgetMeter key={b.role} {...b} />
            ))}
          </ul>
        </Card>
        <Card title="Models">
          <StatsTable
            columns={['Model', 'Requests', 'Blocked', 'Tokens']}
            rows={data.models.map((m) => ({
              key: m.model,
              to: `/audit?model=${encodeURIComponent(m.model)}`,
              cells: [<span className="truncate">{m.model}</span>, fmt.format(m.requests), blockShare(m.blocked, m.requests), fmt.format(m.tokens)],
            }))}
          />
        </Card>
        <Card title="Users">
          <StatsTable
            columns={['User', 'Requests', 'Blocked', 'Tokens']}
            rows={data.principals.map((p) => ({
              key: p.principal,
              to: `/audit?principal=${encodeURIComponent(p.principal)}`,
              cells: [
                <span>
                  {p.principal} <span className="text-xs text-slate-500">{p.role}</span>
                </span>,
                fmt.format(p.requests),
                blockShare(p.blocked, p.requests),
                fmt.format(p.tokens),
              ],
            }))}
          />
        </Card>
      </div>
    </>
  )
}

function blockShare(blocked: number, requests: number): string {
  return blocked ? `${fmt.format(blocked)} (${Math.round((blocked / requests) * 100)}%)` : '0'
}

function Card({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-800 bg-slate-900 p-4">
      <h3 className="font-medium">{title}</h3>
      {subtitle && <p className="mb-3 text-xs text-slate-500">{subtitle}</p>}
      <div className={subtitle ? '' : 'mt-3'}>{children}</div>
    </section>
  )
}

function StatTile({ label, value, note, to }: { label: string; value: string; note?: string; to?: string }) {
  const body = (
    <>
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className="mt-1 text-2xl font-semibold text-slate-100">{value}</div>
      {note && <div className="mt-0.5 text-xs text-slate-500">{note}</div>}
    </>
  )
  const cls = 'block rounded-lg border border-slate-800 bg-slate-900 p-4'
  return to ? (
    <Link to={to} className={`${cls} hover:border-slate-600`} title="show in audit log">
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  )
}

/** Pasek zużycia: status (ok / blisko limitu / wyczerpany) zawsze z tekstem, nie tylko kolorem. */
function BudgetMeter({ role, usedTokens, reservedTokens, cap }: DashboardData['budgets'][number]) {
  if (cap === null) {
    return (
      <li className="text-sm">
        <div className="flex justify-between">
          <span>{role}</span>
          <span className="text-slate-400">{fmt.format(usedTokens)} tokens · no limit</span>
        </div>
      </li>
    )
  }
  const ratio = cap > 0 ? usedTokens / cap : 0
  const status =
    ratio >= 1
      ? { label: 'exhausted', color: '#d03b3b', text: 'text-red-300' }
      : ratio >= 0.8
        ? { label: 'near limit', color: '#fab219', text: 'text-amber-300' }
        : { label: 'ok', color: '#0ca30c', text: 'text-emerald-300' }
  return (
    <li className="text-sm">
      <div className="mb-1 flex justify-between gap-2">
        <span>{role}</span>
        <span className="tabular-nums text-slate-400">
          {fmt.format(usedTokens)} / {fmt.format(cap)} · <span className={status.text}>{status.label}</span>
        </span>
      </div>
      <div className="h-2 rounded bg-slate-800" title={reservedTokens ? `${fmt.format(reservedTokens)} tokens reserved for in-flight requests` : undefined}>
        <div className="h-2 rounded" style={{ width: `${Math.min(ratio, 1) * 100}%`, background: status.color }} />
      </div>
    </li>
  )
}

function StatsTable({ columns, rows }: { columns: string[]; rows: { key: string; to: string; cells: ReactNode[] }[] }) {
  const navigate = useNavigate()
  if (rows.length === 0) return <p className="text-sm text-slate-500">No data in this window.</p>
  return (
    <table className="w-full text-sm">
      <thead className="text-left text-xs uppercase text-slate-500">
        <tr>
          {columns.map((c, i) => (
            <th key={c} className={`pb-1 font-normal ${i > 0 ? 'text-right' : ''}`}>
              {c}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.key} onClick={() => navigate(r.to)} className="cursor-pointer border-t border-slate-800 hover:bg-slate-800/50" title="show in audit log">
            {r.cells.map((cell, i) => (
              <td key={i} className={`max-w-[10rem] truncate py-1.5 ${i > 0 ? 'text-right tabular-nums text-slate-300' : ''}`}>
                {cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
