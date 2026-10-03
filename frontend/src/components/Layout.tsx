import { NavLink, Outlet } from 'react-router-dom'
import { isMocked, type Feature } from '../api/client'
import { useAuth, useCurrentUser } from '../auth/AuthContext'
import { PlaygroundProvider } from '../playground/PlaygroundContext'

/** `adminOnly`: ekran oparty o /api/**, które backend wpuszcza tylko z rolą ADMIN (SecurityConfig). */
const NAV: { to: string; label: string; feature?: Feature; adminOnly?: boolean }[] = [
  { to: '/playground', label: 'Playground', feature: 'chat' },
  { to: '/dashboard', label: 'Dashboard', feature: 'stats', adminOnly: true },
  { to: '/audit', label: 'Audit log', feature: 'audit', adminOnly: true },
  { to: '/sessions', label: 'Session graph', adminOnly: true },
  { to: '/policies', label: 'Policies', feature: 'policy', adminOnly: true },
]

/** Oznaczenie, skąd ekran bierze dane: żywy gateway, dane przykładowe (mock) albo jeszcze nic. */
function SourceTag({ feature }: { feature?: Feature }) {
  if (!feature) return <span className="text-[10px] uppercase text-slate-600">soon</span>
  if (isMocked(feature))
    return (
      <span className="text-[10px] uppercase text-amber-400/80" title="Backend does not implement this endpoint yet — sample data">
        mock
      </span>
    )
  return (
    <span className="text-[10px] uppercase text-emerald-400" title="Live data from the gateway">
      live
    </span>
  )
}

export default function Layout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const isAdmin = user.role === 'admin'

  return (
    <div className="flex min-h-screen">
      <aside className="flex w-56 shrink-0 flex-col border-r border-slate-800 bg-slate-900 p-4">
        <h1 className="mb-6 text-lg font-semibold">AI Control Layer</h1>
        <nav className="flex flex-col gap-1">
          {NAV.filter((item) => isAdmin || !item.adminOnly).map((item) => (
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
        <div className="mt-auto border-t border-slate-800 pt-3 text-sm">
          <p className="truncate font-medium" title={user.login}>
            {user.login}
          </p>
          <p className="text-xs text-slate-500">role: {user.role ?? '—'}</p>
          <button onClick={logout} className="mt-2 text-xs text-slate-400 hover:text-slate-200">
            Sign out
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto p-6">
        {/* Layout nie odmontowuje się przy zmianie zakładki — rozmowa z Playground tu przeżywa. */}
        <PlaygroundProvider key={user.login} owner={user.login}>
          <Outlet />
        </PlaygroundProvider>
      </main>
    </div>
  )
}
