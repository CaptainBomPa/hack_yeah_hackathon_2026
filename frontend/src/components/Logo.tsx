/**
 * Logo LLMinator: tarcza (gateway chroniący model) z wizjerem i czerwonym „okiem” skanującym ruch
 * — mrugnięcie do Terminatora. Ten sam znak jest w public/favicon.svg.
 */
export function LogoMark({ size = 32, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" className={className} role="img" aria-label="LLMinator logo">
      <defs>
        <linearGradient id="llm-shield" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#312e81" />
          <stop offset="1" stopColor="#1e1b4b" />
        </linearGradient>
        <radialGradient id="llm-eye" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#fecaca" />
          <stop offset="0.35" stopColor="#ef4444" />
          <stop offset="1" stopColor="#ef4444" stopOpacity="0" />
        </radialGradient>
      </defs>
      <path
        d="M32 4 L55 12 V30 C55 44.5 45.5 54.5 32 60 C18.5 54.5 9 44.5 9 30 V12 Z"
        fill="url(#llm-shield)"
        stroke="#818cf8"
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <rect x="16" y="24" width="32" height="9" rx="4.5" fill="#020617" stroke="#475569" strokeWidth="1.5" />
      <circle cx="39" cy="28.5" r="7" fill="url(#llm-eye)" />
      <circle cx="39" cy="28.5" r="2.4" fill="#fee2e2" />
      <path d="M22 41 H42 M25 46 H39" stroke="#6366f1" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  )
}

/** Znak + nazwa. `tagline` pokazuje, czym jest produkt. */
export default function Logo({ size = 32, tagline = false }: { size?: number; tagline?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <LogoMark size={size} />
      <div className="leading-tight">
        <div className="text-lg font-semibold tracking-tight">
          LLM<span className="text-red-400">inator</span>
        </div>
        {tagline && <div className="text-[11px] uppercase tracking-wider text-slate-500">AI Control Layer</div>}
      </div>
    </div>
  )
}
