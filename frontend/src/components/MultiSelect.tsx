import { useEffect, useRef, useState, type ReactNode } from 'react'

interface Props {
  label: string
  options: string[]
  selected: string[]
  onChange(next: string[]): void
  onOpen?(): void
  renderOption?(value: string): ReactNode
}

const SEARCH_THRESHOLD = 8

/**
 * Lista z checkboxami do filtrów: wartości pochodzą z danych (facets), więc nie trzeba pamiętać
 * nazw. Przy dłuższej liście pojawia się pole do zawężenia po fragmencie.
 */
export default function MultiSelect({ label, options, selected, onChange, onOpen, renderOption }: Props) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  // Wybrane wartości zawsze na liście, nawet jeśli zniknęły z danych (np. link z innego filtra).
  const all = [...new Set([...selected, ...options])]
  const needle = search.trim().toLowerCase()
  const visible = needle ? all.filter((o) => o.toLowerCase().includes(needle)) : all

  function toggle(value: string) {
    onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value])
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => {
          if (!open) onOpen?.()
          setOpen(!open)
        }}
        className={`flex items-center gap-1.5 rounded px-3 py-1.5 ${selected.length ? 'bg-indigo-900/60 text-indigo-200' : 'bg-slate-800 text-slate-300'} hover:bg-slate-700`}
      >
        {label}
        {selected.length > 0 && <span className="rounded bg-indigo-600 px-1.5 text-xs text-white">{selected.length}</span>}
        <span className="text-xs text-slate-500">▾</span>
      </button>

      {open && (
        <div className="absolute left-0 z-20 mt-1 w-72 rounded-lg border border-slate-700 bg-slate-900 p-2 shadow-xl">
          {all.length > SEARCH_THRESHOLD && (
            <input
              autoFocus
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="zawęź listę…"
              className="mb-2 w-full rounded bg-slate-800 px-2 py-1 text-sm outline-none focus:ring-1 focus:ring-indigo-500"
            />
          )}
          <ul className="max-h-64 overflow-auto">
            {visible.map((value) => (
              <li key={value}>
                <label className="flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-sm hover:bg-slate-800">
                  <input type="checkbox" checked={selected.includes(value)} onChange={() => toggle(value)} />
                  <span className="truncate" title={value}>
                    {renderOption ? renderOption(value) : value}
                  </span>
                </label>
              </li>
            ))}
            {visible.length === 0 && <li className="px-2 py-1 text-sm text-slate-500">brak wartości</li>}
          </ul>
          {selected.length > 0 && (
            <button type="button" onClick={() => onChange([])} className="mt-1 px-2 text-xs text-slate-400 hover:text-slate-200">
              odznacz wszystkie
            </button>
          )}
        </div>
      )}
    </div>
  )
}
