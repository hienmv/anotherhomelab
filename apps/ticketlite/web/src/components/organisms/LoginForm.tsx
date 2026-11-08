import { useState, type FormEvent } from 'react'
import { errorMessage } from '../../api'
import { Button } from '../atoms/Button'
import { FormField } from '../molecules/FormField'
import { StatusMessage } from '../molecules/StatusMessage'

export function LoginForm({ onLogin }: { onLogin: (username: string, password: string) => Promise<void> }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onLogin(username, password)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="login" onSubmit={submit}>
      <h2>Log in</h2>
      <FormField id="username" label="Username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
      <FormField id="password" label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      <StatusMessage status={error ? { kind: 'error', text: error } : null} />
      <Button type="submit" disabled={busy}>
        {busy ? 'Logging in…' : 'Log in'}
      </Button>
      <p className="muted hint">
        Sample accounts: <code>alice / alice123</code>, <code>bob / bob123</code>, <code>carol / carol123</code>
      </p>
    </form>
  )
}
