import PageHeader from '../components/PageHeader'

// Najważniejszy ekran wg docs/propozycja-implementacji.md — przepływ danych (taint) przez sesję agenta.
export default function SessionGraphPage() {
  return (
    <div>
      <PageHeader
        title="Session graph"
        subtitle="Przepływ danych przez sesję agenta: źródła taintu → LLM/MCP → zablokowane wyjścia."
      />
      <div className="flex h-96 items-center justify-center rounded-lg border border-dashed border-slate-700 text-slate-500">
        TODO: graf sesji (np. React Flow) — czeka na kontrakt API sesji z gatewaya
      </div>
    </div>
  )
}
