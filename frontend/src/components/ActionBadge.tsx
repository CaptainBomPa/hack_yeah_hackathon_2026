import type { ControlAction, TechStatus } from '../api/types'

const ACTION_STYLES: Record<ControlAction, string> = {
  allow: 'bg-emerald-900/50 text-emerald-300',
  monitor: 'bg-sky-900/50 text-sky-300',
  redact: 'bg-amber-900/50 text-amber-300',
  require_approval: 'bg-violet-900/50 text-violet-300',
  block: 'bg-red-900/50 text-red-300',
  // Wyłączona kontrola: neutralnie i bez obwódki, żeby nie udawała wyniku (GuardChain.java).
  off: 'bg-slate-800 text-slate-400',
}

const ACTION_LABELS: Record<ControlAction, string> = {
  allow: 'allow',
  monitor: 'monitor',
  redact: 'redact',
  require_approval: 'approval',
  block: 'block',
  off: 'off',
}

export default function ActionBadge({ action }: { action: ControlAction }) {
  const style = ACTION_STYLES[action] ?? 'bg-slate-800 text-slate-300'
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium uppercase ${style}`}>
      {ACTION_LABELS[action] ?? action}
    </span>
  )
}

const STATUS_STYLES: Record<TechStatus, string> = {
  ok: 'text-emerald-400',
  degraded: 'text-amber-400',
  error: 'text-red-400',
}

export function StatusDot({ status }: { status: TechStatus }) {
  return (
    <span className={`text-xs font-medium ${STATUS_STYLES[status]}`} title={`status: ${status}`}>
      ● {status}
    </span>
  )
}
