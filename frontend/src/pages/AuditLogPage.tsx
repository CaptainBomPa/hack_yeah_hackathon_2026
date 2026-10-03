import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { AuditEvent } from '../api/types'
import ActionBadge from '../components/ActionBadge'
import PageHeader from '../components/PageHeader'

export default function AuditLogPage() {
  const [events, setEvents] = useState<AuditEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.auditEvents().then(setEvents).catch((e) => setError(String(e)))
  }, [])

  return (
    <div>
      <PageHeader title="Audit log" subtitle="Bez surowego PII — tylko hash zredagowanego fragmentu." />
      <div className="mb-4 flex gap-2">
        <a href={api.auditExportUrl('csv')} className="rounded bg-slate-800 px-3 py-1.5 text-sm hover:bg-slate-700">
          Eksport CSV
        </a>
        <a href={api.auditExportUrl('json')} className="rounded bg-slate-800 px-3 py-1.5 text-sm hover:bg-slate-700">
          Eksport JSON
        </a>
      </div>
      {error && <p className="text-red-400">{error}</p>}
      <div className="overflow-x-auto rounded-lg border border-slate-800">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-900 text-xs uppercase text-slate-400">
            <tr>
              <th className="px-3 py-2">Czas</th>
              <th className="px-3 py-2">Caller</th>
              <th className="px-3 py-2">Sesja</th>
              <th className="px-3 py-2">Polityka</th>
              <th className="px-3 py-2">Akcja</th>
              <th className="px-3 py-2">Hash</th>
              <th className="px-3 py-2">Wersja</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id} className="border-t border-slate-800">
                <td className="px-3 py-2 text-slate-400">{new Date(e.timestamp).toLocaleTimeString()}</td>
                <td className="px-3 py-2">{e.callerId}</td>
                <td className="px-3 py-2">{e.sessionId ?? '—'}</td>
                <td className="px-3 py-2">
                  <code>{e.policy}</code>
                </td>
                <td className="px-3 py-2">
                  <ActionBadge action={e.action} />
                </td>
                <td className="px-3 py-2 font-mono text-xs text-slate-400">{e.redactedHash ?? '—'}</td>
                <td className="px-3 py-2">{e.policyVersion}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
