import { useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/context'
import { useSubmit } from '../components/useSubmit'

export function RegisterPage() {
  const { register } = useAuth()
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // Registers, then logs in; <GuestOnly> redirects to the dashboard.
  const { busy, error, onSubmit } = useSubmit(() => register(email, password, displayName))

  return (
    <main className="auth-card">
      <h1>Create an account</h1>
      <form onSubmit={onSubmit}>
        <label>
          Name
          <input
            autoComplete="name"
            required
            maxLength={100}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </label>
        <label>
          Email
          <input
            type="email"
            autoComplete="email"
            required
            maxLength={254}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label>
          Password <span className="muted">(8 to 72 characters)</span>
          <input
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            maxLength={72}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={busy}>
          {busy ? 'Creating account…' : 'Register'}
        </button>
      </form>
      <p className="muted">
        Already registered? <Link to="/login">Log in</Link>
      </p>
    </main>
  )
}
