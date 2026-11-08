import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { LoginForm } from '../components/organisms/LoginForm'
import { PageLayout } from '../components/templates/PageLayout'

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  if (user) return <Navigate to="/" replace />
  return (
    <PageLayout>
      <LoginForm onLogin={(u, p) => login(u, p).then(() => navigate('/'))} />
    </PageLayout>
  )
}
