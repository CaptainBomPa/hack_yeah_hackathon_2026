import type { GuardAction } from '../api/types'

const STYLES: Record<GuardAction, string> = {
  allow: 'bg-emerald-900/50 text-emerald-300',
  redact: 'bg-amber-900/50 text-amber-300',
  block: 'bg-red-900/50 text-red-300',
}

export default function ActionBadge({ action }: { action: GuardAction }) {
  return <span className={`rounded px-2 py-0.5 text-xs font-medium uppercase ${STYLES[action]}`}>{action}</span>
}
