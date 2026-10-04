// Grupowanie ścieżki kontroli na to, co da się przeczytać w kilka sekund.
//
// Backend zwraca `trace` jako jedną płaską listę w kolejności wykonania, a guardy etapu INPUT lecą
// raz na każdą wiadomość żądania (ChatCompletionController.guardInput). Rozmowa o 5 wiadomościach
// i 3 guardach daje 15 wpisów o powtarzających się nazwach, w których jedno trafienie tonie.
// Tu składamy z tego sekcje pipeline'u i grupy per kontrola, zachowując każdy wpis.
import type { ControlAction, ControlTrace, GuardAction } from '../api/types'

/** Sekcja ścieżki: bramki całego żądania, a potem etapy łańcucha guardów. */
export type ControlSectionId = 'gate' | 'input' | 'tool_call' | 'output'

export interface ControlEntry {
  /** Pozycja w surowym `trace` — stabilny klucz i kolejność wykonania. */
  index: number
  trace: ControlTrace
  /**
   * Numer wiadomości (1-based), do której odnosi się wpis etapu `input`.
   *
   * Wyliczany, nie przysłany przez backend: `guardInput` uruchamia łańcuch od początku dla każdej
   * wiadomości, więc n-te wystąpienie danego `policy` w tym etapie to n-ta wiadomość. Działa też
   * przy łańcuchu przerwanym blokadą (różne guardy mają wtedy różną liczbę wystąpień, ale n-te
   * wystąpienie każdego z nich nadal należy do n-tej wiadomości). Gdyby gateway zaczął puszczać
   * wiadomości równolegle albo zmieniać kolejność, to założenie przestaje obowiązywać i numer musi
   * przyjść z backendu jako pole `messageIndex`.
   */
  messageNo?: number
}

export interface ControlGroup {
  key: string
  policy: string
  kind: 'deterministic' | 'semantic'
  stage?: string
  /** Ile razy ta kontrola zadziałała w tym żądaniu. */
  calls: number
  /** Wpisy wymagające uwagi (`redact`, `block`, `monitor`, `require_approval`) — pokazywane zawsze. */
  hits: ControlEntry[]
  /** Wpisy bez trafienia (`allow`, `off`) — domyślnie zwinięte. */
  quiet: ControlEntry[]
  /** Rozkład akcji w grupie, od najpoważniejszej. */
  counts: { action: ControlAction; count: number }[]
  /** Kontrola wyłączona w aktywnej polityce (wszystkie wpisy to `off`). */
  off: boolean
  /** Suma czasu własnego wszystkich wywołań tej kontroli. */
  ms: number
  /** Najwyższy sygnał w grupie — pokazuje, jak blisko progu był najgorszy wynik. */
  topSignal?: { confidence: number; threshold?: number | null }
}

export interface ControlSection {
  id: ControlSectionId
  label: string
  groups: ControlGroup[]
  /** Liczba wykonanych kontroli (bez wpisów `off`). */
  checks: number
  hits: number
  /** Liczba wiadomości przepuszczonych przez ten etap; tylko `input`. */
  messages?: number
}

export interface ControlPath {
  sections: ControlSection[]
  /** Liczba wykonanych kontroli w całym żądaniu (bez `off`). */
  checks: number
  hits: number
  /** Ile kontroli jest wyłączonych w polityce — informacja, nie trafienie. */
  off: number
  /** Czy żądanie dotarło do etapu odpowiedzi modelu. */
  reachedModel: boolean
  /** Wpis, który przesądził decyzję; brak, gdy nic nie trafiło. */
  decisive?: ControlEntry
}

const SECTION_ORDER: ControlSectionId[] = ['gate', 'input', 'tool_call', 'output']

const SECTION_LABELS: Record<ControlSectionId, string> = {
  gate: 'Request gates',
  input: 'Input',
  tool_call: 'Tool calls',
  output: 'Output',
}

/** Od najpoważniejszej: tak sortujemy rozkład akcji i tak wybieramy wpis decydujący. */
const SEVERITY: ControlAction[] = ['block', 'require_approval', 'redact', 'monitor', 'allow', 'off']

/** `off` to informacja o wyłączonej kontroli, nie jej trafienie (ControlTrace.java). */
export function isHit(action: ControlAction): boolean {
  return action !== 'allow' && action !== 'off'
}

function sectionOf(trace: ControlTrace): ControlSectionId {
  return trace.stage ?? 'gate'
}

/** Numeruje wpisy etapu `input` kolejnością wystąpień danej kontroli — patrz `ControlEntry.messageNo`. */
function toEntries(trace: ControlTrace[]): ControlEntry[] {
  const seen = new Map<string, number>()
  return trace.map((t, index) => {
    if (t.stage !== 'input') return { index, trace: t }
    const messageNo = (seen.get(t.policy) ?? 0) + 1
    seen.set(t.policy, messageNo)
    return { index, trace: t, messageNo }
  })
}

function group(entries: ControlEntry[]): ControlGroup[] {
  const byKey = new Map<string, ControlGroup>()
  for (const entry of entries) {
    const t = entry.trace
    const key = `${t.policy}|${t.stage ?? ''}`
    let g = byKey.get(key)
    if (!g) {
      g = {
        key,
        policy: t.policy,
        kind: t.kind === 'semantic' ? 'semantic' : 'deterministic',
        stage: t.stage,
        calls: 0,
        hits: [],
        quiet: [],
        counts: [],
        off: true,
        ms: 0,
        topSignal: undefined,
      }
      byKey.set(key, g)
    }
    g.calls += 1
    g.ms += t.latencyMs
    g.off = g.off && t.action === 'off'
    ;(isHit(t.action) ? g.hits : g.quiet).push(entry)
    if (t.confidence != null && (g.topSignal === undefined || t.confidence > g.topSignal.confidence)) {
      g.topSignal = { confidence: t.confidence, threshold: t.threshold }
    }
  }

  for (const g of byKey.values()) {
    const counts = new Map<ControlAction, number>()
    for (const entry of [...g.hits, ...g.quiet]) {
      counts.set(entry.trace.action, (counts.get(entry.trace.action) ?? 0) + 1)
    }
    g.counts = [...counts.entries()]
      .map(([action, count]) => ({ action, count }))
      .sort((a, b) => SEVERITY.indexOf(a.action) - SEVERITY.indexOf(b.action))
  }

  // Kolejność w sekcji: najpierw to, co trafiło, potem reszta w kolejności wykonania. Wyłączone
  // na końcu, bo nic nie wniosły do decyzji.
  return [...byKey.values()].sort((a, b) => {
    if (a.off !== b.off) return a.off ? 1 : -1
    if ((a.hits.length > 0) !== (b.hits.length > 0)) return a.hits.length > 0 ? -1 : 1
    return firstIndex(a) - firstIndex(b)
  })
}

function firstIndex(g: ControlGroup): number {
  return Math.min(...[...g.hits, ...g.quiet].map((e) => e.index))
}

/**
 * Wpis, który przesądził decyzję. Dla blokady szukamy kontroli wskazanej przez `blockedBy`
 * (nie zawsze równa się nazwie w `trace` — np. `upstream-error` vs `upstream.availability`),
 * potem ostatniego trafienia. Dla pozostałych akcji bierzemy najpoważniejsze trafienie.
 */
function findDecisive(entries: ControlEntry[], action: GuardAction, blockedBy: string | null): ControlEntry | undefined {
  const hits = entries.filter((e) => isHit(e.trace.action))
  if (hits.length === 0) return undefined
  if (action === 'block' || action === 'require_approval') {
    return (
      hits.find((e) => e.trace.policy === blockedBy) ??
      [...hits].reverse().find((e) => e.trace.action === 'block' || e.trace.action === 'require_approval') ??
      hits[hits.length - 1]
    )
  }
  return [...hits].sort((a, b) => SEVERITY.indexOf(a.trace.action) - SEVERITY.indexOf(b.trace.action))[0]
}

export function buildControlPath(
  trace: ControlTrace[],
  action: GuardAction,
  blockedBy: string | null = null,
): ControlPath {
  const entries = toEntries(trace)
  const sections: ControlSection[] = []

  for (const id of SECTION_ORDER) {
    const own = entries.filter((e) => sectionOf(e.trace) === id)
    if (own.length === 0) continue
    const messageNos = own.map((e) => e.messageNo).filter((n): n is number => n !== undefined)
    sections.push({
      id,
      label: SECTION_LABELS[id],
      groups: group(own),
      checks: own.filter((e) => e.trace.action !== 'off').length,
      hits: own.filter((e) => isHit(e.trace.action)).length,
      messages: messageNos.length > 0 ? Math.max(...messageNos) : undefined,
    })
  }

  return {
    sections,
    checks: entries.filter((e) => e.trace.action !== 'off').length,
    hits: entries.filter((e) => isHit(e.trace.action)).length,
    off: entries.filter((e) => e.trace.action === 'off').length,
    reachedModel: sections.some((s) => s.id === 'output'),
    decisive: findDecisive(entries, action, blockedBy),
  }
}

/** `0.884 / 0.900` — wynik i próg w jednym zapisie, zawsze z trzema miejscami po kropce. */
export function formatSignal(confidence: number, threshold?: number | null): string {
  const score = confidence.toFixed(3)
  return threshold == null ? score : `${score} / ${threshold.toFixed(3)}`
}
