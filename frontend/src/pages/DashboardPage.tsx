import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/client'
import type { DashboardStats } from '../api/types'
import PageHeader from '../components/PageHeader'

function StatTile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg border border-slate-800 bg-slate-900 p-4">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
    </div>
  )
}

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // TODO: odświeżanie real-time (polling / SSE z gatewaya)
    api.stats().then(setStats).catch((e) => setError(String(e)))
  }, [])

  if (error) return <p className="text-red-400">{error}</p>
  if (!stats) return <p className="text-slate-500">Ładowanie…</p>

  return (
    <div>
      <PageHeader title="Dashboard bezpieczeństwa" subtitle="Blokady, redakcje, budżet i latencje (VISION.md §5)." />
      <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        <StatTile label="Żądania" value={stats.totalRequests} />
        <StatTile label="Zablokowane" value={stats.blocked} />
        <StatTile label="Zredagowane" value={stats.redacted} />
        <StatTile label="Budżet" value={`${stats.budgetUsedPct}%`} />
        <StatTile label="Latencja p50" value={`${stats.latencyP50Ms} ms`} />
        <StatTile label="Latencja p95" value={`${stats.latencyP95Ms} ms`} />
      </div>
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <section className="rounded-lg border border-slate-800 bg-slate-900 p-4">
          <h3 className="mb-3 font-medium">Decyzje w czasie</h3>
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={stats.timeline}>
              <CartesianGrid stroke="#1e293b" />
              <XAxis dataKey="time" stroke="#94a3b8" fontSize={12} />
              <YAxis stroke="#94a3b8" fontSize={12} />
              <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155' }} />
              <Legend />
              <Line type="monotone" dataKey="allow" stroke="#34d399" dot={false} />
              <Line type="monotone" dataKey="redact" stroke="#fbbf24" dot={false} />
              <Line type="monotone" dataKey="block" stroke="#f87171" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </section>
        <section className="rounded-lg border border-slate-800 bg-slate-900 p-4">
          <h3 className="mb-3 font-medium">Trafienia per polityka</h3>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={stats.hitsPerPolicy} layout="vertical">
              <CartesianGrid stroke="#1e293b" />
              <XAxis type="number" stroke="#94a3b8" fontSize={12} />
              <YAxis type="category" dataKey="policy" stroke="#94a3b8" fontSize={12} width={180} />
              <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #334155' }} />
              <Bar dataKey="count" fill="#818cf8" />
            </BarChart>
          </ResponsiveContainer>
        </section>
      </div>
    </div>
  )
}
