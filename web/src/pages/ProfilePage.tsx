import { useAuth, useMe } from '../auth/context'

export function ProfilePage() {
  const me = useMe()
  const { switchOrg } = useAuth()

  return (
    <>
      <h1>Profile</h1>
      <section className="card">
        <dl className="details">
          <dt>Name</dt>
          <dd>{me.displayName}</dd>
          <dt>Email</dt>
          <dd>{me.email}</dd>
          <dt>User ID</dt>
          <dd>
            <code>{me.id}</code>
          </dd>
        </dl>
      </section>
      <section className="card">
        <h2>Organizations</h2>
        {me.organizations.length === 0 ? (
          <p className="muted">You're not a member of any organization yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Slug</th>
                <th>Role</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {me.organizations.map((org) => (
                <tr key={org.id}>
                  <td>{org.name}</td>
                  <td>
                    <code>{org.slug}</code>
                  </td>
                  <td>
                    <span className="badge">{org.role}</span>
                  </td>
                  <td>
                    {org.id === me.activeOrgId ? (
                      <span className="muted">Active</span>
                    ) : (
                      <button type="button" className="secondary" onClick={() => void switchOrg(org.id)}>
                        Switch
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}
