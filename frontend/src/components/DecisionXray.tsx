import type { ChatBudget, ControlTrace, GuardedChatResponse } from '../api/types'
import ActionBadge, { StatusDot } from './ActionBadge'
import { SpanHighlight } from './HighlightedText'

function budgetPct(budget: ChatBudget): number {
  if (!budget.cap) return 0
  return Math.round((budget.used / budget.cap) * 100)
}

interface Props {
  response: GuardedChatResponse
  /** Czas mierzony w przeglądarce (request → odpowiedź), gdy gateway nie podaje latency.totalMs. */
  clientLatencyMs?: number
  /** Tekst użytkownika do podświetlenia spanów (tylko Playground; audyt nie przechowuje promptów). */
  userText?: string
}

/** Explainable Verdict / Security X-ray (VISION.md §5 E): ścieżka kontroli, sygnały, akcja, latencja. */
export default function DecisionXray({ response, clientLatencyMs, userText }: Props) {
  const controlsMs = response.trace.reduce((sum, t) => sum + t.latencyMs, 0)
  const totalMs = response.latency?.totalMs ?? clientLatencyMs
  const upstreamMs = response.latency?.upstreamMs
  const spans = response.trace.flatMap((t) => t.spans ?? [])

  return (
    <div className="space-y-4 text-sm">
      <section className="space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <ActionBadge action={response.action} />
          {response.blockedBy && (
            <span className="text-slate-300">
              przez <code className="text-red-300">{response.blockedBy}</code>
            </span>
          )}
          {response.status && <StatusDot status={response.status} />}
        </div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs text-slate-400">
          <dt>request</dt>
          <dd className="truncate font-mono" title={response.requestId}>
            {response.requestId}
          </dd>
          {response.policyVersion && (
            <>
              <dt>polityka</dt>
              <dd className="font-mono">
                {response.policyVersion}
                {response.policyHash && ` · ${response.policyHash}`}
              </dd>
            </>
          )}
          {response.usage && (
            <>
              <dt>tokeny</dt>
              <dd>
                {response.usage.promptTokens} in / {response.usage.completionTokens} out
              </dd>
            </>
          )}
          {response.budget && response.budget.cap !== null && (
            <>
              <dt>budżet dziś</dt>
              <dd className={budgetPct(response.budget) >= 80 ? 'text-amber-300' : undefined}>
                {response.budget.used} / {response.budget.cap} ({budgetPct(response.budget)}%)
              </dd>
            </>
          )}
        </dl>
        {response.shadow && (
          <p className="rounded bg-violet-950/50 px-2 py-1 text-xs text-violet-300">
            Shadow {response.shadow.policyVersion}: <ActionBadge action={response.shadow.action} />
            {response.shadow.blockedBy && ` przez ${response.shadow.blockedBy}`}
          </p>
        )}
      </section>

      {userText && spans.length > 0 && (
        <section>
          <h4 className="mb-1 text-xs uppercase text-slate-500">Co wywołało decyzję</h4>
          <p className="whitespace-pre-wrap rounded bg-slate-800/60 p-2">
            <SpanHighlight text={userText} spans={spans} />
          </p>
        </section>
      )}

      <LatencyBreakdown trace={response.trace} totalMs={totalMs} upstreamMs={upstreamMs} controlsMs={controlsMs} />

      <section>
        <h4 className="mb-1 text-xs uppercase text-slate-500">Ścieżka kontroli ({response.trace.length})</h4>
        <ol className="space-y-2">
          {response.trace.map((t, i) => (
            <TraceItem key={`${t.policy}-${i}`} trace={t} />
          ))}
        </ol>
      </section>
    </div>
  )
}

function TraceItem({ trace: t }: { trace: ControlTrace }) {
  const degraded = t.status && t.status !== 'ok'
  return (
    <li className={`rounded border p-2 ${degraded ? 'border-amber-700/60 bg-amber-950/20' : 'border-slate-800 bg-slate-800/40'}`}>
      <div className="flex items-center justify-between gap-2">
        <code className="truncate">{t.policy}</code>
        <ActionBadge action={t.action} />
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 text-xs text-slate-400">
        <span>{t.kind === 'semantic' ? 'semantyczna' : 'deterministyczna'}</span>
        {t.stage && <span>{t.stage === 'input' ? 'wejście' : 'wyjście'}</span>}
        {t.mode && <span>tryb: {t.mode}</span>}
        <span>{t.latencyMs} ms</span>
        {t.provider && <span>provider: {t.provider}</span>}
        {t.status && <StatusDot status={t.status} />}
      </div>
      {t.confidence !== undefined && (
        <ConfidenceBar confidence={t.confidence} threshold={t.threshold} />
      )}
      {t.detail && <p className="mt-1 text-xs text-slate-300">{t.detail}</p>}
    </li>
  )
}

function ConfidenceBar({ confidence, threshold }: { confidence: number; threshold?: number }) {
  const pct = Math.round(confidence * 100)
  const over = threshold !== undefined && confidence >= threshold
  return (
    <div className="mt-1.5">
      <div className="relative h-1.5 rounded bg-slate-700">
        <div className={`h-1.5 rounded ${over ? 'bg-red-400' : 'bg-emerald-400'}`} style={{ width: `${pct}%` }} />
        {threshold !== undefined && (
          <div className="absolute -top-0.5 h-2.5 w-0.5 bg-slate-200" style={{ left: `${threshold * 100}%` }} />
        )}
      </div>
      <p className="mt-0.5 text-xs text-slate-400">
        pewność {pct}%{threshold !== undefined && ` · próg ${Math.round(threshold * 100)}%`}
      </p>
    </div>
  )
}

function LatencyBreakdown({
  trace,
  totalMs,
  upstreamMs,
  controlsMs,
}: {
  trace: ControlTrace[]
  totalMs?: number
  upstreamMs?: number
  controlsMs: number
}) {
  if (totalMs === undefined || totalMs <= 0) return null
  // Bez upstreamMs z gatewaya nie wiemy, ile zajął sam model — pokazujemy resztę uczciwie jako "model + sieć".
  const restMs = Math.max(totalMs - controlsMs - (upstreamMs ?? 0), 0)
  const rows = [
    ...trace.map((t) => ({ label: t.policy, ms: t.latencyMs, color: t.kind === 'semantic' ? 'bg-violet-400' : 'bg-sky-400' })),
    ...(upstreamMs !== undefined ? [{ label: 'model (Ollama)', ms: upstreamMs, color: 'bg-slate-400' }] : []),
    { label: upstreamMs !== undefined ? 'pozostałe' : 'model + sieć', ms: restMs, color: 'bg-slate-600' },
  ].filter((r) => r.ms > 0)

  return (
    <section>
      <h4 className="mb-1 text-xs uppercase text-slate-500">
        Latencja: {totalMs} ms · kontrole {controlsMs} ms
      </h4>
      <div className="space-y-1">
        {rows.map((r, i) => (
          <div key={i} className="grid grid-cols-[8rem_1fr_3.5rem] items-center gap-2 text-xs">
            <span className="truncate text-slate-400" title={r.label}>
              {r.label}
            </span>
            <div className="h-2 rounded bg-slate-800">
              <div className={`h-2 rounded ${r.color}`} style={{ width: `${Math.max((r.ms / totalMs) * 100, 1)}%` }} />
            </div>
            <span className="text-right text-slate-400">{r.ms} ms</span>
          </div>
        ))}
      </div>
    </section>
  )
}
