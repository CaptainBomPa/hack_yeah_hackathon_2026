import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import PlaygroundPage from './pages/PlaygroundPage'
import DashboardPage from './pages/DashboardPage'
import AuditLogPage from './pages/AuditLogPage'
import SessionGraphPage from './pages/SessionGraphPage'
import PoliciesPage from './pages/PoliciesPage'

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/playground" replace />} />
        <Route path="/playground" element={<PlaygroundPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/audit" element={<AuditLogPage />} />
        <Route path="/sessions" element={<SessionGraphPage />} />
        <Route path="/policies" element={<PoliciesPage />} />
      </Route>
    </Routes>
  )
}
