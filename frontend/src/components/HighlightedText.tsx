import type { TextSpan } from '../api/types'

const REDACTED = /\[REDACTED(?::[^\]]*)?\]/g

/** Podświetla fragmenty wskazane przez kontrolki (spans) w tekście użytkownika. Renderuje wyłącznie tekst. */
export function SpanHighlight({ text, spans }: { text: string; spans: TextSpan[] }) {
  const sorted = [...spans]
    .filter((s) => s.start >= 0 && s.end <= text.length && s.start < s.end)
    .sort((a, b) => a.start - b.start)
  const parts: JSX.Element[] = []
  let pos = 0
  sorted.forEach((span, i) => {
    if (span.start < pos) return // nachodzące spany: pierwszy wygrywa
    if (span.start > pos) parts.push(<span key={`t${i}`}>{text.slice(pos, span.start)}</span>)
    parts.push(
      <mark key={`m${i}`} title={span.label} className="rounded bg-red-500/30 px-0.5 text-inherit underline decoration-red-400">
        {text.slice(span.start, span.end)}
      </mark>,
    )
    pos = span.end
  })
  if (pos < text.length) parts.push(<span key="rest">{text.slice(pos)}</span>)
  return <>{parts}</>
}

/** Wyróżnia znaczniki [REDACTED:…] wstawione przez gateway w odpowiedzi modelu. */
export function RedactedText({ text }: { text: string }) {
  const parts: JSX.Element[] = []
  let pos = 0
  for (const m of text.matchAll(REDACTED)) {
    const start = m.index ?? 0
    if (start > pos) parts.push(<span key={`t${start}`}>{text.slice(pos, start)}</span>)
    parts.push(
      <span key={`r${start}`} className="rounded bg-amber-500/20 px-1 font-mono text-xs text-amber-300">
        {m[0]}
      </span>,
    )
    pos = start + m[0].length
  }
  if (pos < text.length) parts.push(<span key="rest">{text.slice(pos)}</span>)
  return <>{parts}</>
}
