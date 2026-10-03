import { NavLink, Outlet } from 'react-router-dom'
import { isMocked, type Feature } from '../api/client'

const NAV: { to: string; label: string; feature?: Feature }[] = [
  { to: '/playground', label: 'Playground', feature: 'chat' },
  { to: '/dashboard', label: 'Dashboard', feature: 'stats' },
  { to: '/audit', label: 'Audit log', feature: 'audit' },
  { to: '/sessions', label: 'Session graph' },
  { to: '/policies', label: 'Polityki', feature: 'policy' },
]

/** Oznaczenie, skąd ekran bierze dane: żywy gateway, dane przykładowe (mock) albo jeszcze nic. */
function SourceTag({ feature }: { feature?: Feature }) {
  if (!feature) return <span className="text-[10px] uppercase text-slate-600">wkrótce</span>
  if (isMocked(feature))
    return (
      <span className="text-[10px] uppercase text-amber-400/80" title="Backend nie ma jeszcze tego endpointu — dane przykładowe">
        mock
      </span>
    )
  return (
    <span className="text-[10px] uppercase text-emerald-400" title="Dane z żywego gatewaya">
      live
    </span>
  )
}

export default function Layout() {
  return (
    <div className="flex min-h-screen">
      <aside className="w-56 shrink-0 border-r border-slate-800 bg-slate-900 p-4">
        <h1 className="mb-6 text-lg font-semibold">AI Control Layer</h1>
        <nav className="flex flex-col gap-1">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center justify-between rounded px-3 py-2 text-sm ${isActive ? 'bg-slate-700 text-white' : 'text-slate-400 hover:bg-slate-800'}`
              }
            >
              {item.label}
              <SourceTag feature={item.feature} />
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="flex-1 overflow-auto p-6">
        <Outlet />
      </main>
    </div>
  )
}
