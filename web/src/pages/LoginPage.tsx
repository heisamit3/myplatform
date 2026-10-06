import { useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/context'
import { useSubmit } from '../components/useSubmit'

export function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // On success the auth state changes and <GuestOnly> redirects; no navigate() needed here.
  const { busy, error, onSubmit } = useSubmit(() => login(email, password))

  return (
    <main className="auth-card">
      <h1>Log in</h1>
      <form onSubmit={onSubmit}>
        <label>
          Email
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={busy}>
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className="muted">
        No account? <Link to="/register">Register</Link>
      </p>
    </main>
  )
}
