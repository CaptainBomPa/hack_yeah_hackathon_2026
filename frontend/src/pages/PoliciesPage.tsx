import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PolicyInfo } from '../api/types'
import PageHeader from '../components/PageHeader'

export default function PoliciesPage() {
  const [policy, setPolicy] = useState<PolicyInfo | null>(null)
  const [draft, setDraft] = useState('')
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    api
      .policy()
      .then((p) => {
        setPolicy(p)
        setDraft(p.raw)
      })
      .catch((e) => setStatus(String(e)))
  }, [])

  async function save() {
    setStatus('Zapisywanie…')
    try {
      const p = await api.updatePolicy(draft)
      setPolicy(p)
      setStatus('Zapisano — gateway przeładuje politykę bez restartu.')
    } catch (e) {
      setStatus(String(e))
    }
  }

  return (
    <div>
      <PageHeader title="Polityki" subtitle="Hot-reload konfiguracji guardraili (jury podmienia politykę na żywo)." />
      {policy && (
        <p className="mb-3 text-sm text-slate-400">
          Wersja <b>{policy.version}</b> · hash <code>{policy.hash}</code> · {new Date(policy.updatedAt).toLocaleString()}
        </p>
      )}
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        spellCheck={false}
        className="h-96 w-full rounded-lg border border-slate-800 bg-slate-900 p-3 font-mono text-sm outline-none focus:ring-2 focus:ring-indigo-500"
      />
      <div className="mt-3 flex items-center gap-3">
        <button onClick={save} className="rounded bg-indigo-600 px-4 py-2 text-sm font-medium">
          Zapisz politykę
        </button>
        {status && <span className="text-sm text-slate-400">{status}</span>}
      </div>
    </div>
  )
}
