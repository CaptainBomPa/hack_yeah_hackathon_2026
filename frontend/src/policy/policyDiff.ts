import type { PolicyDocument } from '../api/types'

export interface PolicyChange {
  path: string
  before: string
  after: string
}

const NONE = '—'

function show(value: unknown): string {
  if (value === undefined) return NONE
  if (value === null) return 'unlimited'
  if (Array.isArray(value)) return value.length ? value.join(', ') : 'none'
  return String(value)
}

/** Polityka spłaszczona do „ścieżka → wartość” (ścieżki jak w błędach PolicyValidator.java). */
export function flatten(doc: PolicyDocument): Map<string, string> {
  const out = new Map<string, string>()
  for (const [role, policy] of Object.entries(doc.roles)) {
    out.set(`roles.${role}.models`, show([...policy.models].sort()))
    out.set(`roles.${role}.dailyTokens`, show(policy.dailyTokens))
  }
  for (const model of doc.models) out.set(`models.${model.tag}.enabled`, show(model.enabled))
  for (const [id, guard] of Object.entries(doc.guards)) {
    out.set(`guards.${id}.enabled`, show(guard.enabled))
    out.set(`guards.${id}.order`, show(guard.order))
    for (const [key, value] of Object.entries(guard.params)) {
      out.set(`guards.${id}.params.${key}`, show(Array.isArray(value) ? [...value].sort() : value))
    }
  }
  out.set('limits.maxInputTokens', show(doc.limits.maxInputTokens))
  out.set('limits.maxOutputTokens', show(doc.limits.maxOutputTokens))
  return out
}

/** Co się zmieniło między dwiema wersjami — do podglądu przed zapisem i w historii. */
export function diffPolicies(before: PolicyDocument, after: PolicyDocument): PolicyChange[] {
  const a = flatten(before)
  const b = flatten(after)
  const paths = [...new Set([...a.keys(), ...b.keys()])].sort()
  return paths
    .filter((path) => (a.get(path) ?? NONE) !== (b.get(path) ?? NONE))
    .map((path) => ({ path, before: a.get(path) ?? NONE, after: b.get(path) ?? NONE }))
}
