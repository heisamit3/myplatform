import { useState } from 'react'
import { useAuth, useMe } from '../auth/context'

/** Picks the active org. Switching gets new tokens whose `org` claim is the chosen org. */
export function OrgSwitcher() {
  const me = useMe()
  const { switchOrg } = useAuth()
  const [busy, setBusy] = useState(false)

  if (me.organizations.length === 0) {
    return <span className="muted">No organization yet</span>
  }

  async function onChange(orgId: string) {
    setBusy(true)
    try {
      await switchOrg(orgId)
    } finally {
      setBusy(false)
    }
  }

  return (
    <label className="org-switcher">
      <span className="visually-hidden">Active organization</span>
      <select
        aria-label="Active organization"
        value={me.activeOrgId ?? ''}
        disabled={busy}
        onChange={(event) => void onChange(event.target.value)}
      >
        {me.activeOrgId === null && <option value="">Choose an organization…</option>}
        {me.organizations.map((org) => (
          <option key={org.id} value={org.id}>
            {org.name}
          </option>
        ))}
      </select>
    </label>
  )
}
