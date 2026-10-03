import PageHeader from '../components/PageHeader'

// Najważniejszy ekran wg docs/propozycja-implementacji.md — przepływ danych (taint) przez sesję agenta.
export default function SessionGraphPage() {
  return (
    <div>
      <PageHeader
        title="Session graph"
        subtitle="Data flow through an agent session: taint sources → LLM/MCP → blocked sinks."
      />
      <div className="flex h-96 items-center justify-center rounded-lg border border-dashed border-slate-700 text-slate-500">
        Coming soon: session graph (e.g. React Flow) — waiting for the gateway session API
      </div>
    </div>
  )
}
