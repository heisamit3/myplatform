import { useState } from 'react'
import { useAuth, useMe } from '../auth/context'
import { useSubmit } from '../components/useSubmit'
import { slugify } from '../lib/slug'

function CreateOrgForm({ first }: { first: boolean }) {
  const { createOrg } = useAuth()
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [slugEdited, setSlugEdited] = useState(false)
  const { busy, error, onSubmit } = useSubmit(async () => {
    await createOrg(name.trim(), slug)
    setName('')
    setSlug('')
    setSlugEdited(false)
  })

  return (
    <section className="card">
      <h2>{first ? 'Create your first organization' : 'Create another organization'}</h2>
      <form onSubmit={onSubmit} className="inline-form">
        <label>
          Name
          <input
            required
            maxLength={100}
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (!slugEdited) setSlug(slugify(e.target.value))
            }}
          />
        </label>
        <label>
          Slug
          <input
            required
            maxLength={63}
            pattern="[a-z0-9]+(-[a-z0-9]+)*"
            title="Lowercase letters, digits and single dashes"
            value={slug}
            onChange={(e) => {
              setSlug(e.target.value)
              setSlugEdited(true)
            }}
          />
        </label>
        <button type="submit" disabled={busy}>
          {busy ? 'Creating…' : 'Create'}
        </button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  )
}

export function DashboardPage() {
  const me = useMe()
  const active = me.organizations.find((org) => org.id === me.activeOrgId)

  return (
    <>
      <h1>Welcome, {me.displayName}</h1>
      {active ? (
        <section className="card">
          <p className="muted">Active organization</p>
          <h2>{active.name}</h2>
          <p>
            <code>{active.slug}</code> · your role: <span className="badge">{active.role}</span>
          </p>
          <p className="muted">
            Your access token carries this org. Every tenant-scoped request is filtered by it.
          </p>
        </section>
      ) : (
        me.organizations.length > 0 && (
          <section className="card">
            <p>Pick an organization in the header to start working in it.</p>
          </section>
        )
      )}
      <CreateOrgForm first={me.organizations.length === 0} />
    </>
  )
}
