import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ForbiddenError, PolicyConflictError, PolicyInvalidError } from '../api/client'
import type { PolicyCatalog, PolicyDocument, PolicyError, PolicyVersionSummary, PolicyView } from '../api/types'
import PageHeader from '../components/PageHeader'
import { diffPolicies, type PolicyChange } from '../policy/policyDiff'

/**
 * Zarządzanie polityką (docs/policy-management-plan.md §5): formularz na kopii aktywnej wersji,
 * walidacja na bieżąco, podgląd zmian przed zapisem; zapis = nowa wersja aktywna od następnego żądania.
 */
const ANY_MODEL = '*'
const VALIDATE_DEBOUNCE_MS = 500
const PII_GUARD = 'PII-RECOGNIZERS'
const SEMANTIC_GUARD = 'SEM-001'
const PII_LISTS = { block: 'blockRecognizers', monitor: 'monitorRecognizers', off: 'disabledRecognizers' } as const
type PiiChoice = 'default' | keyof typeof PII_LISTS
type GuardPolicy = { enabled: boolean; order: number; params: Record<string, unknown> }
type SetGuard = (mutate: (g: GuardPolicy) => void) => void

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const fmtTime = (iso: string) => new Date(iso).toLocaleString('en-GB')

export default function PoliciesPage() {
  const [active, setActive] = useState<PolicyView | null>(null)
  const [draft, setDraft] = useState<PolicyDocument | null>(null)
  const [errors, setErrors] = useState<PolicyError[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ kind: 'ok' | 'conflict' | 'error'; text: string } | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [showImport, setShowImport] = useState(false)

  const load = useCallback(async () => {
    try {
      const view = await api.policy()
      setActive(view)
      setDraft(clone(view.document))
      setErrors([])
      setLoadError(null)
      setNotice(null)
    } catch (err) {
      setLoadError(err instanceof ForbiddenError ? err.message : String(err))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const changes = useMemo(() => (active && draft ? diffPolicies(active.document, draft) : []), [active, draft])

  // Walidacja na bieżąco — te same reguły co przy zapisie (PolicyValidator.java).
  useEffect(() => {
    if (!draft || changes.length === 0) {
      setErrors([])
      return
    }
    const timer = setTimeout(() => {
      api.validatePolicy(draft).then((r) => setErrors(r.errors)).catch(() => undefined)
    }, VALIDATE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [draft, changes.length])

  function update(mutate: (doc: PolicyDocument) => void) {
    setDraft((current) => {
      if (!current) return current
      const next = clone(current)
      mutate(next)
      return next
    })
    setNotice(null)
  }

  async function save() {
    if (!active || !draft) return
    setSaving(true)
    try {
      const saved = await api.savePolicy(active.version, draft, comment)
      afterActivation(saved, `Policy v${saved.version} is active — it applies to new requests.`)
    } catch (err) {
      handleWriteError(err)
    } finally {
      setSaving(false)
    }
  }

  function afterActivation(view: PolicyView, text: string) {
    setActive(view)
    setDraft(clone(view.document))
    setComment('')
    setReviewing(false)
    setErrors([])
    setNotice({ kind: 'ok', text })
  }

  function handleWriteError(err: unknown) {
    setReviewing(false)
    if (err instanceof PolicyInvalidError) {
      setErrors(err.errors)
      setNotice({ kind: 'error', text: 'The policy was not saved — fix the highlighted fields.' })
    } else if (err instanceof PolicyConflictError) {
      setNotice({ kind: 'conflict', text: `${err.message}. Your changes were not saved.` })
    } else {
      setNotice({ kind: 'error', text: String(err) })
    }
  }

  if (loadError) return <p className="rounded bg-red-950/50 px-3 py-2 text-sm text-red-300">{loadError}</p>
  if (!active || !draft) return <p className="text-sm text-slate-500">Loading…</p>

  const errorsAt = (prefix: string) =>
    errors.filter((e) => e.path === prefix || e.path.startsWith(prefix + '.') || e.path.startsWith(prefix + '['))

  return (
    <div className="space-y-4 pb-24">
      <PageHeader
        title="Policies"
        subtitle="Single source of truth for controls, thresholds, allowed models and budgets. Saving activates a new version immediately — no restart."
      />

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="rounded bg-emerald-900/40 px-2 py-1 text-emerald-300">
          Active v{active.version} · <code>{active.hash.slice(0, 8)}</code>
        </span>
        <span className="text-xs text-slate-400">
          by {active.author} · {fmtTime(active.createdAt)} · {active.source}
          {active.comment && <> · “{active.comment}”</>}
        </span>
        <div className="ml-auto flex gap-2">
          <button onClick={() => setShowHistory(!showHistory)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
            {showHistory ? 'Hide history' : 'History'}
          </button>
          <a href={api.policyExportUrl()} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
            Export YAML
          </a>
          <button onClick={() => setShowImport(true)} className="rounded bg-slate-800 px-3 py-1.5 hover:bg-slate-700">
            Import YAML
          </button>
        </div>
      </div>

      {notice && (
        <div
          className={`flex items-center gap-3 rounded px-3 py-2 text-sm ${notice.kind === 'ok' ? 'bg-emerald-950/60 text-emerald-300' : notice.kind === 'conflict' ? 'bg-amber-900/40 text-amber-300' : 'bg-red-950/50 text-red-300'}`}
        >
          {notice.text}
          {notice.kind === 'conflict' && (
            <button onClick={load} className="rounded bg-amber-800/60 px-2 py-0.5 text-xs text-amber-100 hover:bg-amber-700/60">
              Load latest version
            </button>
          )}
        </div>
      )}

      {showHistory && (
        <HistoryPanel
          activeVersion={active.version}
          onRestored={(view) => afterActivation(view, `Restored as policy v${view.version} — it applies to new requests.`)}
          onError={handleWriteError}
        />
      )}

      <RolesSection draft={draft} catalog={active.catalog} errorsAt={errorsAt} update={update} />
      <ModelsSection draft={draft} catalog={active.catalog} errorsAt={errorsAt} update={update} />
      <GuardsSection draft={draft} catalog={active.catalog} errorsAt={errorsAt} update={update} />
      <LimitsSection draft={draft} errorsAt={errorsAt} update={update} />

      {changes.length > 0 && (
        <div className="fixed bottom-0 left-56 right-0 z-10 border-t border-slate-700 bg-slate-900/95 px-6 py-3 backdrop-blur">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span>
              {changes.length} unsaved change{changes.length > 1 ? 's' : ''}
            </span>
            {errors.length > 0 && (
              <span className="text-red-300">
                {errors.length} problem{errors.length > 1 ? 's' : ''} to fix
              </span>
            )}
            <button onClick={() => setDraft(clone(active.document))} className="ml-auto px-2 py-1.5 text-slate-400 hover:text-slate-200">
              Discard
            </button>
            <button
              onClick={() => setReviewing(true)}
              disabled={errors.length > 0}
              className="rounded bg-indigo-600 px-4 py-1.5 font-medium disabled:opacity-50"
            >
              Review &amp; save
            </button>
          </div>
        </div>
      )}

      {reviewing && (
        <Dialog title={`Save as policy v${active.version + 1}`} onClose={() => setReviewing(false)}>
          <ChangeList changes={changes} />
          <input
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="Comment (optional) — why are you changing it?"
            maxLength={500}
            className="mt-3 w-full rounded bg-slate-800 px-3 py-2 text-sm"
          />
          <div className="mt-3 flex justify-end gap-2 text-sm">
            <button onClick={() => setReviewing(false)} className="px-3 py-1.5 text-slate-400 hover:text-slate-200">
              Back to editing
            </button>
            <button onClick={save} disabled={saving} className="rounded bg-indigo-600 px-4 py-1.5 font-medium disabled:opacity-50">
              {saving ? 'Saving…' : 'Save and activate'}
            </button>
          </div>
        </Dialog>
      )}

      {showImport && (
        <ImportDialog
          baseVersion={active.version}
          onClose={() => setShowImport(false)}
          onImported={(view) => {
            setShowImport(false)
            afterActivation(view, `Imported as policy v${view.version} — it applies to new requests.`)
          }}
          onError={(err) => {
            setShowImport(false)
            handleWriteError(err)
          }}
        />
      )}
    </div>
  )
}

interface SectionProps {
  draft: PolicyDocument
  catalog: PolicyCatalog
  errorsAt(prefix: string): PolicyError[]
  update(mutate: (doc: PolicyDocument) => void): void
}

/**
 * Modele z polityki, których nie ma w katalogu wdrożenia (np. po zmianie konfiguracji albo brancha).
 * Walidator odrzuca zapis z takim modelem, więc UI musi je pokazać, żeby admin mógł je usunąć.
 */
function staleModels(doc: PolicyDocument, catalog: PolicyCatalog): string[] {
  const known = new Set(catalog.models.map((m) => m.tag))
  const used = [...doc.models.map((m) => m.tag), ...Object.values(doc.roles).flatMap((r) => r.models)]
  return [...new Set(used)].filter((tag) => tag !== ANY_MODEL && !known.has(tag))
}

function removeModel(doc: PolicyDocument, tag: string) {
  doc.models = doc.models.filter((m) => m.tag !== tag)
  Object.values(doc.roles).forEach((role) => {
    role.models = role.models.filter((m) => m !== tag)
  })
}

function RolesSection({ draft, catalog, errorsAt, update }: SectionProps) {
  const [newRole, setNewRole] = useState('')
  const stale = staleModels(draft, catalog)
  const tags = [...catalog.models.map((m) => m.tag), ...stale]
  return (
    <Section title="Roles & model access" subtitle="Which models each role may call and its daily token budget. A role without an entry has no access.">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="py-1 pr-3 font-normal">Role</th>
              <th className="px-2 py-1 font-normal">All models</th>
              {tags.map((tag) => (
                <th key={tag} className="px-2 py-1 font-normal normal-case" title={tag}>
                  <span className="block max-w-[10rem] truncate">{tag}</span>
                  {stale.includes(tag) && <span className="block text-[10px] text-amber-300">not in catalog</span>}
                </th>
              ))}
              <th className="px-2 py-1 font-normal">Daily token budget</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {Object.entries(draft.roles).map(([role, policy]) => {
              const accounts = catalog.roleAccounts[role] ?? 0
              const all = policy.models.includes(ANY_MODEL)
              const toggle = (model: string, on: boolean) =>
                update((doc) => {
                  const models = new Set(doc.roles[role].models)
                  if (on) models.add(model)
                  else models.delete(model)
                  doc.roles[role].models = [...models]
                })
              return (
                <tr key={role} className="border-t border-slate-800 align-top">
                  <td className="py-2 pr-3">
                    <div className="font-medium">{role}</div>
                    <div className="text-xs text-slate-500">
                      {accounts} account{accounts === 1 ? '' : 's'}
                    </div>
                    <FieldErrors
                      errors={errorsAt(`roles.${role}`).filter((e) => !e.path.includes('.models') && !e.path.includes('dailyTokens'))}
                    />
                  </td>
                  <td className="px-2 py-2">
                    <input type="checkbox" checked={all} onChange={(e) => toggle(ANY_MODEL, e.target.checked)} aria-label={`${role}: all models`} />
                  </td>
                  {tags.map((tag) => (
                    <td key={tag} className="px-2 py-2">
                      <input
                        type="checkbox"
                        checked={all || policy.models.includes(tag)}
                        disabled={all}
                        onChange={(e) => toggle(tag, e.target.checked)}
                        aria-label={`${role}: ${tag}`}
                      />
                    </td>
                  ))}
                  <td className="px-2 py-2">
                    <input
                      type="number"
                      min={1}
                      value={policy.dailyTokens ?? ''}
                      placeholder="unlimited"
                      onChange={(e) =>
                        update((doc) => {
                          doc.roles[role].dailyTokens = e.target.value === '' ? null : Number(e.target.value)
                        })
                      }
                      className="w-32 rounded bg-slate-800 px-2 py-1"
                    />
                    <FieldErrors errors={[...errorsAt(`roles.${role}.dailyTokens`), ...errorsAt(`roles.${role}.models`)]} />
                  </td>
                  <td className="px-2 py-2 text-right">
                    <button
                      onClick={() =>
                        update((doc) => {
                          delete doc.roles[role]
                        })
                      }
                      disabled={accounts > 0 || role === 'admin'}
                      title={role === 'admin' ? 'The admin role is required' : accounts > 0 ? 'Role has accounts' : 'Remove role'}
                      className="text-xs text-slate-400 hover:text-red-300 disabled:cursor-not-allowed disabled:opacity-30"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <FieldErrors errors={errorsAt('roles').filter((e) => e.path === 'roles')} />
      <form
        className="mt-3 flex gap-2 text-sm"
        onSubmit={(e) => {
          e.preventDefault()
          const name = newRole.trim().toLowerCase()
          if (!name || draft.roles[name]) return
          update((doc) => {
            doc.roles[name] = { models: [], dailyTokens: null }
          })
          setNewRole('')
        }}
      >
        <input value={newRole} onChange={(e) => setNewRole(e.target.value)} placeholder="new role name" className="w-44 rounded bg-slate-800 px-2 py-1" />
        <button className="rounded bg-slate-800 px-3 py-1 hover:bg-slate-700">Add role</button>
      </form>
    </Section>
  )
}

function ModelsSection({ draft, catalog, errorsAt, update }: SectionProps) {
  const stale = staleModels(draft, catalog)
  return (
    <Section title="Models" subtitle="Allowlist. A disabled model is rejected for every role, exactly like an unknown model.">
      <ul className="space-y-2 text-sm">
        {catalog.models.map((model) => {
          const entry = draft.models.find((m) => m.tag === model.tag)
          return (
            <li key={model.tag} className="flex items-center gap-3">
              <Toggle
                checked={entry?.enabled ?? false}
                onChange={(on) =>
                  update((doc) => {
                    const existing = doc.models.find((m) => m.tag === model.tag)
                    if (existing) existing.enabled = on
                    else doc.models.push({ tag: model.tag, enabled: on })
                  })
                }
                label={model.tag}
              />
              <span className="font-mono">{model.tag}</span>
              <span className="text-xs text-slate-500">{model.baseUrl}</span>
            </li>
          )
        })}
        {stale.map((tag) => (
          <li key={tag} className="flex items-center gap-3">
            <span className="font-mono text-slate-400 line-through">{tag}</span>
            <span className="text-xs text-amber-300">not in the deployment catalog — remove it to save the policy</span>
            <button
              onClick={() => update((doc) => removeModel(doc, tag))}
              className="rounded bg-slate-800 px-2 py-0.5 text-xs hover:bg-slate-700 hover:text-red-300"
            >
              Remove from policy
            </button>
          </li>
        ))}
      </ul>
      <FieldErrors errors={errorsAt('models')} />
    </Section>
  )
}

function GuardsSection({ draft, catalog, errorsAt, update }: SectionProps) {
  return (
    <Section title="Guards" subtitle="Controls run in order (lower first) on every request. A disabled guard does not run at all.">
      <div className="space-y-3">
        {catalog.guards.map((guard) => {
          const policy: GuardPolicy = draft.guards[guard.id] ?? { enabled: false, order: 100, params: {} }
          const setGuard: SetGuard = (mutate) =>
            update((doc) => {
              doc.guards[guard.id] = doc.guards[guard.id] ?? { enabled: false, order: 100, params: {} }
              mutate(doc.guards[guard.id])
            })
          return (
            <div key={guard.id} className="rounded-lg border border-slate-800 p-3">
              <div className="flex flex-wrap items-center gap-3 text-sm">
                <Toggle
                  checked={policy.enabled}
                  onChange={(on) =>
                    setGuard((g) => {
                      g.enabled = on
                    })
                  }
                  label={guard.id}
                />
                <code className="font-medium">{guard.id}</code>
                <span className="text-xs text-slate-500">
                  {guard.kind} · {guard.stages.join(', ').toLowerCase()}
                </span>
                <label className="ml-auto flex items-center gap-1 text-xs text-slate-400">
                  order
                  <input
                    type="number"
                    value={policy.order}
                    onChange={(e) =>
                      setGuard((g) => {
                        g.order = Number(e.target.value)
                      })
                    }
                    className="w-20 rounded bg-slate-800 px-2 py-1 text-sm text-slate-100"
                  />
                </label>
              </div>
              <FieldErrors errors={errorsAt(`guards.${guard.id}`)} />
              {policy.enabled && guard.id === PII_GUARD && <PiiParams params={policy.params} catalog={catalog} setGuard={setGuard} />}
              {policy.enabled && guard.id === SEMANTIC_GUARD && <SemanticParams params={policy.params} setGuard={setGuard} />}
              {policy.enabled && guard.id !== PII_GUARD && guard.id !== SEMANTIC_GUARD && Object.keys(policy.params).length > 0 && (
                <pre className="mt-2 overflow-auto rounded bg-slate-950 p-2 text-xs text-slate-400">{JSON.stringify(policy.params, null, 2)}</pre>
              )}
            </div>
          )
        })}
      </div>
    </Section>
  )
}

function PiiParams({ params, catalog, setGuard }: { params: Record<string, unknown>; catalog: PolicyCatalog; setGuard: SetGuard }) {
  const listOf = (key: string) => (Array.isArray(params[key]) ? (params[key] as string[]) : [])
  const choiceOf = (id: string): PiiChoice =>
    (Object.keys(PII_LISTS) as (keyof typeof PII_LISTS)[]).find((c) => listOf(PII_LISTS[c]).includes(id)) ?? 'default'
  // Jeden recognizer może być na co najwyżej jednej liście (block / monitor / off) — PolicyValidator to sprawdza.
  const setChoice = (id: string, choice: PiiChoice) =>
    setGuard((g) => {
      for (const [c, key] of Object.entries(PII_LISTS)) {
        const ids = new Set(Array.isArray(g.params[key]) ? (g.params[key] as string[]) : [])
        if (c === choice) ids.add(id)
        else ids.delete(id)
        g.params[key] = [...ids].sort()
      }
    })
  const threshold = typeof params.threshold === 'number' ? params.threshold : 0.5
  return (
    <div className="mt-3 space-y-3 text-sm">
      <NumberSlider
        label="Detection threshold"
        hint="Minimum confidence for a PII finding. Lower catches more, with more false positives."
        value={threshold}
        onChange={(v) =>
          setGuard((g) => {
            g.params.threshold = v
          })
        }
      />
      <table className="w-full text-sm">
        <thead className="text-left text-xs uppercase text-slate-500">
          <tr>
            <th className="py-1 font-normal">Recognizer</th>
            <th className="py-1 font-normal">Entity</th>
            <th className="py-1 font-normal">Action</th>
          </tr>
        </thead>
        <tbody>
          {catalog.piiRecognizers.map((r) => (
            <tr key={r.id} className="border-t border-slate-800">
              <td className="py-1.5">
                <code>{r.id}</code> <span className="text-slate-400">{r.name}</span>
              </td>
              <td className="py-1.5 text-xs text-slate-500">{r.entity}</td>
              <td className="py-1.5">
                <select
                  value={choiceOf(r.id)}
                  onChange={(e) => setChoice(r.id, e.target.value as PiiChoice)}
                  className="rounded bg-slate-800 px-2 py-1"
                  aria-label={`${r.id} action`}
                >
                  <option value="default">default ({r.defaultAction})</option>
                  <option value="block">block</option>
                  <option value="monitor">monitor only</option>
                  <option value="off">off</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SemanticParams({ params, setGuard }: { params: Record<string, unknown>; setGuard: SetGuard }) {
  const threshold = typeof params.blockThreshold === 'number' ? params.blockThreshold : 0.998
  const failureMode = params.failureMode === 'open' ? 'open' : 'closed'
  return (
    <div className="mt-3 space-y-3 text-sm">
      <NumberSlider
        label="Block threshold"
        hint="Calibrated injection score (0–1) at which a prompt is blocked. Higher means fewer false positives and more misses."
        value={threshold}
        step={0.001}
        onChange={(v) =>
          setGuard((g) => {
            g.params.blockThreshold = v
          })
        }
      />
      <div className="flex flex-wrap items-center gap-4">
        <label className="flex items-center gap-2">
          Timeout
          <input
            type="number"
            min={100}
            max={60000}
            value={typeof params.timeoutMs === 'number' ? params.timeoutMs : 4000}
            onChange={(e) =>
              setGuard((g) => {
                g.params.timeoutMs = Number(e.target.value)
              })
            }
            className="w-24 rounded bg-slate-800 px-2 py-1"
          />
          ms
        </label>
        <label className="flex items-center gap-2">
          When the classifier fails
          <select
            value={failureMode}
            onChange={(e) =>
              setGuard((g) => {
                g.params.failureMode = e.target.value
              })
            }
            className="rounded bg-slate-800 px-2 py-1"
          >
            <option value="closed">block the request (fail-closed)</option>
            <option value="open">let it through (fail-open)</option>
          </select>
        </label>
      </div>
      {failureMode === 'open' && (
        <p className="rounded bg-amber-900/40 px-3 py-2 text-xs text-amber-300">
          Fail-open lets every prompt through while the semantic sidecar is down — attacks are not checked semantically.
        </p>
      )}
    </div>
  )
}

function LimitsSection({ draft, errorsAt, update }: Omit<SectionProps, 'catalog'>) {
  return (
    <Section title="Request limits" subtitle="Applied to every request, for every role — they protect the model host.">
      <div className="flex flex-wrap gap-6 text-sm">
        {(['maxInputTokens', 'maxOutputTokens'] as const).map((key) => (
          <label key={key} className="flex items-center gap-2">
            {key === 'maxInputTokens' ? 'Max input tokens' : 'Max output tokens'}
            <input
              type="number"
              min={1}
              value={draft.limits[key]}
              onChange={(e) =>
                update((doc) => {
                  doc.limits[key] = Number(e.target.value)
                })
              }
              className="w-28 rounded bg-slate-800 px-2 py-1"
            />
          </label>
        ))}
      </div>
      <FieldErrors errors={errorsAt('limits')} />
    </Section>
  )
}

function HistoryPanel({
  activeVersion,
  onRestored,
  onError,
}: {
  activeVersion: number
  onRestored(view: PolicyView): void
  onError(err: unknown): void
}) {
  const [versions, setVersions] = useState<PolicyVersionSummary[] | null>(null)
  const [diff, setDiff] = useState<{ version: number; changes: PolicyChange[] } | null>(null)

  useEffect(() => {
    api.policyVersions().then(setVersions).catch(onError)
  }, [activeVersion]) // odśwież listę po każdej nowej aktywnej wersji

  async function toggleDiff(version: number) {
    if (diff?.version === version) {
      setDiff(null)
      return
    }
    const [current, previous] = await Promise.all([
      api.policyVersion(version),
      version > 1 ? api.policyVersion(version - 1) : Promise.resolve(null),
    ])
    setDiff({ version, changes: previous ? diffPolicies(previous.document, current.document) : [] })
  }

  return (
    <Section title="History" subtitle="Every saved version. Restoring creates a new version with the old content — history is never rewritten.">
      {!versions && <p className="text-sm text-slate-500">Loading…</p>}
      <ul className="divide-y divide-slate-800 text-sm">
        {versions?.map((v) => (
          <li key={v.version} className="py-2">
            <div className="flex flex-wrap items-center gap-3">
              <span className="font-medium">v{v.version}</span>
              {v.version === activeVersion && <span className="rounded bg-emerald-900/40 px-1.5 text-xs text-emerald-300">active</span>}
              <span className="text-xs text-slate-400">
                {v.author} · {fmtTime(v.createdAt)} · {v.source}
              </span>
              {v.comment && <span className="text-xs text-slate-300">“{v.comment}”</span>}
              <div className="ml-auto flex gap-3 text-xs">
                <button onClick={() => toggleDiff(v.version)} className="text-slate-400 hover:text-slate-200">
                  {diff?.version === v.version ? 'Hide changes' : 'Changes'}
                </button>
                {v.version !== activeVersion && (
                  <button onClick={() => api.restorePolicy(v.version).then(onRestored).catch(onError)} className="text-indigo-300 hover:underline">
                    Restore
                  </button>
                )}
              </div>
            </div>
            {diff?.version === v.version && (
              <div className="mt-2">
                {v.version === 1 ? <p className="text-xs text-slate-500">Initial version.</p> : <ChangeList changes={diff.changes} />}
              </div>
            )}
          </li>
        ))}
      </ul>
    </Section>
  )
}

function ImportDialog({
  baseVersion,
  onClose,
  onImported,
  onError,
}: {
  baseVersion: number
  onClose(): void
  onImported(view: PolicyView): void
  onError(err: unknown): void
}) {
  const [yaml, setYaml] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit() {
    setBusy(true)
    try {
      onImported(await api.importPolicy(baseVersion, yaml, 'imported from YAML'))
    } catch (err) {
      onError(err)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog title="Import policy from YAML" onClose={onClose}>
      <p className="mb-2 text-xs text-slate-400">Paste a file exported from this screen or load it from disk. It is validated like any other change.</p>
      <input
        type="file"
        accept=".yaml,.yml,text/yaml"
        onChange={(e) => e.target.files?.[0]?.text().then(setYaml)}
        className="mb-2 block text-xs text-slate-400"
      />
      <textarea value={yaml} onChange={(e) => setYaml(e.target.value)} spellCheck={false} className="h-64 w-full rounded bg-slate-950 p-2 font-mono text-xs" />
      <div className="mt-3 flex justify-end gap-2 text-sm">
        <button onClick={onClose} className="px-3 py-1.5 text-slate-400 hover:text-slate-200">
          Cancel
        </button>
        <button onClick={submit} disabled={busy || !yaml.trim()} className="rounded bg-indigo-600 px-4 py-1.5 font-medium disabled:opacity-50">
          {busy ? 'Importing…' : 'Validate and activate'}
        </button>
      </div>
    </Dialog>
  )
}

function ChangeList({ changes }: { changes: PolicyChange[] }) {
  if (changes.length === 0) return <p className="text-xs text-slate-500">No changes.</p>
  return (
    <table className="w-full text-xs">
      <tbody>
        {changes.map((c) => (
          <tr key={c.path} className="border-t border-slate-800">
            <td className="py-1 pr-3 font-mono text-slate-400">{c.path}</td>
            <td className="py-1 pr-2 text-red-300 line-through decoration-red-400/50">{c.before}</td>
            <td className="py-1 text-emerald-300">{c.after}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-800 bg-slate-900 p-4">
      <h3 className="font-medium">{title}</h3>
      {subtitle && <p className="mb-3 text-xs text-slate-500">{subtitle}</p>}
      {children}
    </section>
  )
}

function Dialog({ title, onClose, children }: { title: string; onClose(): void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-30 flex items-center justify-center bg-black/60 p-4" onMouseDown={onClose}>
      <div
        className="max-h-[85vh] w-full max-w-2xl overflow-auto rounded-lg border border-slate-700 bg-slate-900 p-4"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h3 className="mb-3 font-medium">{title}</h3>
        {children}
      </div>
    </div>
  )
}

function Toggle({ checked, onChange, label }: { checked: boolean; onChange(on: boolean): void; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={`relative h-5 w-9 shrink-0 rounded-full transition-colors ${checked ? 'bg-emerald-600' : 'bg-slate-700'}`}
    >
      <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all ${checked ? 'left-[1.125rem]' : 'left-0.5'}`} />
    </button>
  )
}

function NumberSlider({
  label,
  hint,
  value,
  step = 0.01,
  onChange,
}: {
  label: string
  hint: string
  value: number
  step?: number
  onChange(value: number): void
}) {
  return (
    <div>
      <div className="flex flex-wrap items-center gap-3">
        <span className="w-40">{label}</span>
        <input type="range" min={0} max={1} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} className="w-56" />
        <input
          type="number"
          min={0}
          max={1}
          step={step}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          className="w-24 rounded bg-slate-800 px-2 py-1"
        />
      </div>
      <p className="mt-0.5 text-xs text-slate-500">{hint}</p>
    </div>
  )
}

function FieldErrors({ errors }: { errors: PolicyError[] }) {
  if (errors.length === 0) return null
  return (
    <ul className="mt-1 space-y-0.5">
      {errors.map((e) => (
        <li key={e.path + e.message} className="text-xs text-red-300">
          {e.message}
        </li>
      ))}
    </ul>
  )
}
