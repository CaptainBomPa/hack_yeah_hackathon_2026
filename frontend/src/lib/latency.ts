// Rozkład czasu jednego żądania na kontrole, model i resztę — wspólny dla Playground i audytu.
// `ControlTrace.latencyMs` z backendu to czas własny jednej kontroli, więc suma po wpisach jest
// sensownym "ile zajęły kontrole". Rekordy audytu sprzed tej zmiany mają tam czas narastający;
// dlatego skala słupków bierze max(total, controls + model), a nie samo total (patrz `scaleMs`).
import type { ControlTrace } from '../api/types'

export type LatencyKind = 'deterministic' | 'semantic' | 'model' | 'other'

export interface LatencyRow {
  key: string
  /** Nazwa kontroli (id polityki) albo etykieta pozycji technicznej. */
  label: string
  /** Etap guardu, gdy kontrola należy do łańcucha: `input` / `output` / `tool_call`. */
  stage?: string
  kind: LatencyKind
  ms: number
  /** Ile razy ta kontrola zadziałała w tym żądaniu (np. raz na każdą wiadomość historii). */
  calls: number
}

export interface LatencySummary {
  totalMs?: number
  controlsMs: number
  deterministicMs: number
  semanticMs: number
  upstreamMs?: number
  /** Model + sieć, gdy gateway nie podał `upstreamMs`; sama reszta pipeline'u, gdy podał. */
  restMs: number
  restLabel: string
  /** Mianownik do szerokości słupków; chroni przed >100% na starych rekordach audytu. */
  scaleMs: number
  /** Kontrole zagregowane per polityka i etap, najdroższe pierwsze. */
  rows: LatencyRow[]
  /** Najdroższa kontrola — do jednozdaniowego podsumowania w UI. */
  slowest?: LatencyRow
}

const STAGE_ORDER: Record<string, number> = { input: 0, tool_call: 1, output: 2 }

/** Jedna kontrola może zadziałać kilka razy (guardy INPUT lecą na każdej wiadomości) — sumujemy. */
function aggregate(trace: ControlTrace[]): LatencyRow[] {
  const byKey = new Map<string, LatencyRow>()
  for (const t of trace) {
    const key = `${t.policy}|${t.stage ?? ''}`
    const row = byKey.get(key)
    if (row) {
      row.ms += t.latencyMs
      row.calls += 1
    } else {
      byKey.set(key, {
        key,
        label: t.policy,
        stage: t.stage,
        kind: t.kind === 'semantic' ? 'semantic' : 'deterministic',
        ms: t.latencyMs,
        calls: 1,
      })
    }
  }
  return [...byKey.values()].sort(
    (a, b) =>
      b.ms - a.ms ||
      (STAGE_ORDER[a.stage ?? ''] ?? -1) - (STAGE_ORDER[b.stage ?? ''] ?? -1) ||
      a.label.localeCompare(b.label),
  )
}

export function summarizeLatency(
  trace: ControlTrace[],
  totalMs?: number,
  upstreamMs?: number,
): LatencySummary {
  const rows = aggregate(trace)
  const sumOf = (kind: LatencyKind) => rows.filter((r) => r.kind === kind).reduce((s, r) => s + r.ms, 0)
  const deterministicMs = sumOf('deterministic')
  const semanticMs = sumOf('semantic')
  const controlsMs = deterministicMs + semanticMs
  const accounted = controlsMs + (upstreamMs ?? 0)
  const restMs = totalMs === undefined ? 0 : Math.max(totalMs - accounted, 0)

  return {
    totalMs,
    controlsMs,
    deterministicMs,
    semanticMs,
    upstreamMs,
    restMs,
    restLabel: upstreamMs === undefined ? 'model + network' : 'gateway + network',
    scaleMs: Math.max(totalMs ?? 0, accounted, 1),
    rows,
    slowest: rows[0],
  }
}

/**
 * Jednostka dobrana do wielkości: µs dla kontroli deterministycznych, ms dla semantycznych,
 * sekundy dla modelu. `ControlTrace.latencyMs` ma rozdzielczość mikrosekundy, więc guard trwający
 * 0,18 ms pokazuje się jako „180 µs”, a nie „0 ms” — inaczej cała warstwa deterministyczna
 * wyglądałaby na niezmierzoną.
 *
 * Dokładne zero zostaje jako „0 ms”: tyle ma wpis `off`, czyli kontrola, która się nie wykonała.
 */
export function formatMs(ms: number): string {
  if (ms <= 0) return '0 ms'
  const us = Math.round(ms * 1000)
  if (us < 1000) return `${us} µs`
  if (ms < 1000) return `${ms < 10 ? ms.toFixed(1) : Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}

/** Udział w całości, zaokrąglony tak, żeby nie pokazywać "0%" dla czegoś, co zajęło czas. */
export function sharePct(ms: number, scaleMs: number): number {
  if (scaleMs <= 0) return 0
  const pct = (ms / scaleMs) * 100
  return pct > 0 && pct < 1 ? 1 : Math.round(pct)
}
