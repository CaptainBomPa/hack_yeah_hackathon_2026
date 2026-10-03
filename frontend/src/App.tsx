import { Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import Layout from './components/Layout'
import AuditLogPage from './pages/AuditLogPage'
import DashboardPage from './pages/DashboardPage'
import LoginPage from './pages/LoginPage'
import PlaygroundPage from './pages/PlaygroundPage'
import PoliciesPage from './pages/PoliciesPage'
import SessionGraphPage from './pages/SessionGraphPage'

export default function App() {
  const { state } = useAuth()

  if (state.status === 'loading') {
    return <div className="flex min-h-screen items-center justify-center text-sm text-slate-500">Ładowanie…</div>
  }
  // Bez sesji każda ścieżka pokazuje logowanie; po zalogowaniu zostajemy na tej samej ścieżce.
  if (state.status === 'anonymous') return <LoginPage expired={state.expired} />

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/playground" replace />} />
        <Route path="/playground" element={<PlaygroundPage />} />
        {/* Ekrany oparte o /api/** — backend wpuszcza tam tylko rolę ADMIN (SecurityConfig). */}
        <Route element={<AdminOnly isAdmin={state.user.role === 'admin'} />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/audit" element={<AuditLogPage />} />
          <Route path="/sessions" element={<SessionGraphPage />} />
          <Route path="/policies" element={<PoliciesPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/playground" replace />} />
      </Route>
    </Routes>
  )
}

function AdminOnly({ isAdmin }: { isAdmin: boolean }) {
  return isAdmin ? <Outlet /> : <Navigate to="/playground" replace />
}
