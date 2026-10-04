import type { ChatBudget, GuardedChatResponse } from '../api/types'
import { formatMs, sharePct, summarizeLatency, type LatencyRow, type LatencySummary } from '../lib/latency'
import ActionBadge, { StatusDot } from './ActionBadge'
import ControlPathView from './ControlPathView'
import { SpanHighlight } from './HighlightedText'
import { redactedBy } from '../lib/decision'

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
  /** Klik w kontrolę-powód (audyt: filtr `reason`). Bez tego powód jest zwykłym tekstem. */
  onReason?: (policy: string) => void
}

/** Explainable Verdict / Security X-ray (VISION.md §5 E): ścieżka kontroli, sygnały, akcja, latencja. */
export default function DecisionXray({ response, clientLatencyMs, userText, onReason }: Props) {
  const redacting = redactedBy(response.action, response.trace)
  // totalMs z gatewaya jest dokładniejszy niż pomiar w przeglądarce (bez narzutu sieci i renderu).
  const timing = summarizeLatency(
    response.trace,
    response.latency?.totalMs ?? clientLatencyMs,
    response.latency?.upstreamMs ?? undefined,
  )
  const spans = response.trace.flatMap((t) => t.spans ?? [])

  return (
    <div className="space-y-4 text-sm">
      <section className="space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <ActionBadge action={response.action} />
          {response.blockedBy && (
            <span className="text-slate-300">
              by <Reason policy={response.blockedBy} className="text-red-300" onReason={onReason} />
            </span>
          )}
          {redacting.length > 0 && (
            <span className="text-slate-300">
              because of{' '}
              {redacting.map((policy, i) => (
                <span key={policy}>
                  {i > 0 && ', '}
                  <Reason policy={policy} className="text-amber-300" onReason={onReason} />
                </span>
              ))}
            </span>
          )}
          {response.status && <StatusDot status={response.status} />}
        </div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs text-slate-400">
          <dt>request</dt>
          <dd className="truncate font-mono" title={response.requestId}>
            {response.requestId}
          </dd>
          {response.policyVersion != null && (
            <>
              <dt>policy</dt>
              <dd className="font-mono" title={response.policyHash ?? undefined}>
                v{response.policyVersion}
                {response.policyHash && ` · ${response.policyHash.slice(0, 8)}`}
              </dd>
            </>
          )}
          {response.usage && (
            <>
              <dt>tokens</dt>
              <dd>
                {response.usage.promptTokens} in / {response.usage.completionTokens} out
              </dd>
            </>
          )}
          {response.budget && response.budget.cap !== null && (
            <>
              <dt>budget today</dt>
              <dd className={budgetPct(response.budget) >= 80 ? 'text-amber-300' : undefined}>
                {response.budget.used} / {response.budget.cap} ({budgetPct(response.budget)}%)
              </dd>
            </>
          )}
        </dl>
        {response.shadow && (
          <p className="rounded bg-violet-950/50 px-2 py-1 text-xs text-violet-300">
            Shadow {response.shadow.policyVersion}: <ActionBadge action={response.shadow.action} />
            {response.shadow.blockedBy && ` by ${response.shadow.blockedBy}`}
          </p>
        )}
      </section>

      {userText && spans.length > 0 && (
        <section>
          <h4 className="mb-1 text-xs uppercase text-slate-500">What triggered the decision</h4>
          <p className="whitespace-pre-wrap rounded bg-slate-800/60 p-2">
            <SpanHighlight text={userText} spans={spans} />
          </p>
        </section>
      )}

      <TimingPanel timing={timing} />

      {/* Ścieżkę kontroli rysuje ControlPathView: grupuje wpisy per etap i kontrolę, bo guardy
          INPUT lecą raz na każdą wiadomość i płaska lista rosła z historią rozmowy. */}
      <ControlPathView
        trace={response.trace}
        action={response.action}
        blockedBy={response.blockedBy}
        upstreamMs={timing.upstreamMs}
      />
    </div>
  )
}

/** Nazwa kontroli jako powód decyzji; klikalna, gdy rodzic umie filtrować audyt po `reason`. */
function Reason({ policy, className, onReason }: { policy: string; className: string; onReason?: (policy: string) => void }) {
  if (!onReason) return <code className={className}>{policy}</code>
  return (
    <button onClick={() => onReason(policy)} title={`Show requests with reason ${policy}`} className="hover:underline">
      <code className={className}>{policy}</code>
    </button>
  )
}

const KIND_COLOR: Record<string, string> = {
  deterministic: 'bg-sky-400',
  semantic: 'bg-violet-400',
  model: 'bg-slate-400',
  other: 'bg-slate-600',
}

/**
 * Gdzie poszedł czas żądania: jeden pasek z podziałem na kontrole / model / resztę, a pod nim
 * kontrole zagregowane per polityka (guardy INPUT lecą raz na każdą wiadomość, więc bez
 * agregacji lista rośnie z historią rozmowy i nic z niej nie wynika).
 */
function TimingPanel({ timing }: { timing: LatencySummary }) {
  const { totalMs, controlsMs, deterministicMs, semanticMs, upstreamMs, restMs, restLabel, scaleMs, rows, slowest } =
    timing
  if (totalMs === undefined || totalMs <= 0) return null

  const segments = [
    { key: 'deterministic', label: 'deterministic checks', ms: deterministicMs, kind: 'deterministic' },
    { key: 'semantic', label: 'semantic checks', ms: semanticMs, kind: 'semantic' },
    ...(upstreamMs !== undefined ? [{ key: 'model', label: 'protected model', ms: upstreamMs, kind: 'model' }] : []),
    { key: 'rest', label: restLabel, ms: restMs, kind: 'other' },
  ].filter((s) => s.ms > 0)

  return (
    <section>
      <h4 className="mb-1 text-xs uppercase text-slate-500">Timing</h4>
      <p className="mb-2 text-slate-200">
        {formatMs(totalMs)} total{' '}
        <span className="text-slate-400">
          · checks {formatMs(controlsMs)} ({sharePct(controlsMs, scaleMs)}%)
        </span>
      </p>

      {/* Etykiety i liczby są w legendzie pod paskiem — kolor nigdy nie jest jedynym nośnikiem informacji.
          Proporcje przez flex-grow, nie przez width w %: segment krótszy niż piksel i tak jest widoczny
          (minWidth), a pasek nigdy nie przekracza 100% i nie ucina ostatniej pozycji. */}
      <div className="flex h-2.5 overflow-hidden rounded bg-slate-800" role="presentation">
        {segments.map((s) => (
          <div
            key={s.key}
            className={KIND_COLOR[s.kind]}
            style={{ flexGrow: s.ms, flexBasis: 0, minWidth: '2px' }}
            title={`${s.label}: ${formatMs(s.ms)}`}
          />
        ))}
      </div>
      <ul className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-400">
        {segments.map((s) => (
          <li key={s.key} className="flex items-center gap-1.5">
            <span className={`h-2 w-2 shrink-0 rounded-sm ${KIND_COLOR[s.kind]}`} aria-hidden="true" />
            <span>
              {s.label} {formatMs(s.ms)}
            </span>
          </li>
        ))}
      </ul>

      {rows.length > 0 && (
        <div className="mt-3 space-y-1">
          {rows.map((r) => (
            <ControlTimingRow key={r.key} row={r} scaleMs={scaleMs} />
          ))}
        </div>
      )}

      {slowest && rows.length > 1 && (
        <p className="mt-2 text-xs text-slate-500">
          Slowest check: <code className="text-slate-400">{slowest.label}</code> — {formatMs(slowest.ms)}
          {slowest.calls > 1 && ` across ${slowest.calls} calls`}.
        </p>
      )}
    </section>
  )
}

function ControlTimingRow({ row, scaleMs }: { row: LatencyRow; scaleMs: number }) {
  const pct = sharePct(row.ms, scaleMs)
  return (
    <div className="grid grid-cols-[9rem_1fr_4.5rem] items-center gap-2 text-xs">
      <span className="truncate text-slate-300" title={row.label}>
        {row.label}
      </span>
      <div className="flex items-center gap-2">
        <div className="h-1.5 min-w-0 flex-1 rounded bg-slate-800">
          <div className={`h-1.5 rounded ${KIND_COLOR[row.kind]}`} style={{ width: `${pct}%` }} />
        </div>
        <span className="shrink-0 text-slate-500">
          {row.stage ?? 'gate'}
          {row.calls > 1 && ` ×${row.calls}`}
        </span>
      </div>
      <span className="text-right text-slate-400">{formatMs(row.ms)}</span>
    </div>
  )
}
