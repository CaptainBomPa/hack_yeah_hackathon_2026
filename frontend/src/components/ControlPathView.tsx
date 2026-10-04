import type { ControlTrace, GuardAction } from '../api/types'
import {
  buildControlPath,
  formatSignal,
  isHit,
  type ControlEntry,
  type ControlGroup,
  type ControlSection,
} from '../lib/controlPath'
import { formatMs } from '../lib/latency'
import ActionBadge, { StatusDot } from './ActionBadge'

interface Props {
  trace: ControlTrace[]
  /** Decyzja całego żądania — potrzebna, żeby wskazać wpis, który ją przesądził. */
  action: GuardAction
  blockedBy: string | null
  /** Czas chronionego modelu, gdy gateway go podał; rysowany na separatorze etapów. */
  upstreamMs?: number
}

/**
 * Ścieżka kontroli (VISION.md §5 E) złożona w sekcje pipeline'u i zwinięta do trafień.
 *
 * Trafienia są widoczne od razu, cisza (`allow`, `off`) siedzi pod rozwinięciem, a granica
 * wywołania modelu jest jawnym separatorem, nie kolejnym szarym słowem w wierszu. Bez tego
 * jedno trafienie w rozmowie o 5 wiadomościach to 1 z 19 identycznie wyglądających kart.
 */
export default function ControlPathView({ trace, action, blockedBy, upstreamMs }: Props) {
  const path = buildControlPath(trace, action, blockedBy)
  if (trace.length === 0) return null

  return (
    <section>
      <h4 className="mb-1 text-xs uppercase text-slate-500">Control path</h4>

      <p className="mb-3 text-slate-200">
        {path.hits === 0 ? 'No hits' : `${path.hits} ${path.hits === 1 ? 'hit' : 'hits'}`}{' '}
        <span className="text-slate-400">
          in {path.checks} {path.checks === 1 ? 'check' : 'checks'}
          {path.off > 0 && ` · ${path.off} off`}
        </span>
        {path.decisive && <DecisiveLine entry={path.decisive} />}
      </p>

      <div className="space-y-3">
        {path.sections.map((section) => (
          <div key={section.id}>
            {section.id === 'output' && <ModelDivider upstreamMs={upstreamMs} />}
            <SectionView section={section} decisiveIndex={path.decisive?.index} />
          </div>
        ))}
        {!path.reachedModel && action === 'block' && (
          <p className="rounded border border-slate-800 bg-slate-900/60 px-2 py-1 text-xs text-slate-400">
            Request never reached the protected model.
          </p>
        )}
      </div>
    </section>
  )
}

/** Jedno zdanie: która kontrola zadecydowała, co zrobiła i gdzie. */
function DecisiveLine({ entry }: { entry: ControlEntry }) {
  const t = entry.trace
  return (
    <span className="mt-0.5 block text-xs text-slate-300">
      <code className="text-slate-200">{t.policy}</code> {t.action} at {t.stage ?? 'request gate'}
      {entry.messageNo !== undefined && ` · msg ${entry.messageNo}`}
      {t.detail && <span className="text-slate-400"> — {t.detail}</span>}
    </span>
  )
}

function ModelDivider({ upstreamMs }: { upstreamMs?: number }) {
  return (
    <div className="mb-3 flex items-center gap-2" aria-hidden="true">
      <span className="h-px flex-1 bg-slate-700" />
      <span className="text-xs uppercase tracking-wide text-slate-500">
        protected model{upstreamMs !== undefined && ` · ${formatMs(upstreamMs)}`}
      </span>
      <span className="h-px flex-1 bg-slate-700" />
    </div>
  )
}

function SectionView({ section, decisiveIndex }: { section: ControlSection; decisiveIndex?: number }) {
  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between gap-2 text-xs">
        <span className="font-medium text-slate-300">
          {section.label}
          {section.messages !== undefined && (
            <span className="font-normal text-slate-500">
              {' '}
              · {section.messages} {section.messages === 1 ? 'message' : 'messages'}
            </span>
          )}
        </span>
        <span className="text-slate-500">
          {section.checks} {section.checks === 1 ? 'check' : 'checks'}
          {section.hits > 0 && <span className="text-amber-300"> · {section.hits} hit</span>}
        </span>
      </div>
      <ul className="space-y-1">
        {section.groups.map((group) => (
          <li key={group.key}>
            <GroupView group={group} decisiveIndex={decisiveIndex} />
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Grupa bez trafień jest całkowicie zwinięta (`<details>` z nagłówkiem w `<summary>`), grupa
 * z trafieniem pokazuje je od razu, a resztę wywołań chowa pod osobnym rozwinięciem.
 * `<details>` zamiast własnego stanu: klawiatura i czytniki ekranu działają bez dodatkowego kodu.
 */
function GroupView({ group, decisiveIndex }: { group: ControlGroup; decisiveIndex?: number }) {
  const header = <GroupHeader group={group} />

  if (group.hits.length === 0) {
    return (
      <details className="rounded border border-slate-800 bg-slate-800/30">
        <summary className="cursor-pointer px-2 py-1">{header}</summary>
        <EntryList entries={group.quiet} decisiveIndex={decisiveIndex} />
      </details>
    )
  }

  return (
    <div className="rounded border border-amber-800/50 bg-amber-950/10">
      <div className="px-2 py-1">{header}</div>
      <EntryList entries={group.hits} decisiveIndex={decisiveIndex} />
      {group.quiet.length > 0 && (
        <details>
          <summary className="cursor-pointer px-2 pb-1 text-xs text-slate-500">
            {group.quiet.length} more without a hit
          </summary>
          <EntryList entries={group.quiet} decisiveIndex={decisiveIndex} />
        </details>
      )}
    </div>
  )
}

function GroupHeader({ group }: { group: ControlGroup }) {
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
      <code className={`text-sm ${group.off ? 'text-slate-500' : 'text-slate-200'}`}>{group.policy}</code>
      {group.calls > 1 && <span className="text-xs text-slate-500">×{group.calls}</span>}
      <span className="ml-auto flex items-center gap-1.5">
        {group.counts.map(({ action, count }) => (
          <span key={action} className="flex items-center gap-1">
            {count > 1 && <span className="text-xs text-slate-500">{count}</span>}
            <ActionBadge action={action} />
          </span>
        ))}
      </span>
      <span className="w-full text-xs text-slate-500">
        {group.kind}
        {group.stage && ` · ${group.stage}`}
        {!group.off && ` · ${formatMs(group.ms)}`}
        {group.topSignal && ` · top ${formatSignal(group.topSignal.confidence, group.topSignal.threshold)}`}
      </span>
    </div>
  )
}

function EntryList({ entries, decisiveIndex }: { entries: ControlEntry[]; decisiveIndex?: number }) {
  return (
    <ol className="space-y-1 px-2 pb-2">
      {entries.map((entry) => (
        <EntryRow key={entry.index} entry={entry} decisive={entry.index === decisiveIndex} />
      ))}
    </ol>
  )
}

function EntryRow({ entry, decisive }: { entry: ControlEntry; decisive: boolean }) {
  const t = entry.trace
  const degraded = t.status && t.status !== 'ok'
  return (
    <li
      className={`rounded px-2 py-1 text-xs ${
        decisive ? 'bg-amber-900/20 ring-1 ring-amber-700/50' : degraded ? 'bg-amber-950/20' : 'bg-slate-900/50'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="text-slate-400">
          {entry.messageNo !== undefined ? `msg ${entry.messageNo}` : (t.stage ?? 'gate')}
        </span>
        {isHit(t.action) && <ActionBadge action={t.action} />}
        <span className="ml-auto text-slate-500">{formatMs(t.latencyMs)}</span>
        {t.status && <StatusDot status={t.status} />}
      </div>
      {t.confidence != null && <ConfidenceBar confidence={t.confidence} threshold={t.threshold} />}
      {t.detail && <p className="mt-0.5 text-slate-300">{t.detail}</p>}
    </li>
  )
}

/**
 * Wynik detektora na tle progu z polityki. To jedyne miejsce, w którym widać, jak blisko decyzji
 * był dany prompt — i co zmienia ruszenie progu w polityce (CRITERIA §6: jurorzy ruszają progi).
 */
function ConfidenceBar({ confidence, threshold }: { confidence: number; threshold?: number | null }) {
  const pct = Math.round(confidence * 100)
  const over = threshold != null && confidence >= threshold
  return (
    <div className="mt-1">
      <div className="relative h-1.5 rounded bg-slate-700">
        <div className={`h-1.5 rounded ${over ? 'bg-red-400' : 'bg-emerald-400'}`} style={{ width: `${pct}%` }} />
        {threshold != null && (
          <div
            className="absolute -top-0.5 h-2.5 w-0.5 bg-slate-200"
            style={{ left: `${Math.min(threshold * 100, 100)}%` }}
            title={`threshold ${threshold.toFixed(3)}`}
          />
        )}
      </div>
      <p className="mt-0.5 text-slate-400">
        score {confidence.toFixed(3)}
        {threshold != null && ` · threshold ${threshold.toFixed(3)}`}
      </p>
    </div>
  )
}
