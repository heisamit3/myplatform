import { NavLink, Outlet } from 'react-router'
import { useAuth, useMe } from '../auth/context'
import { OrgSwitcher } from './OrgSwitcher'

/** Header with navigation, org switcher and logout around every signed-in page. */
export function Layout() {
  const me = useMe()
  const { logout } = useAuth()

  return (
    <>
      <header className="topbar">
        <span className="brand">myplatform</span>
        <nav>
          <NavLink to="/" end>
            Dashboard
          </NavLink>
          <NavLink to="/profile">Profile</NavLink>
        </nav>
        <div className="topbar-right">
          <OrgSwitcher />
          <span className="muted">{me.displayName}</span>
          <button type="button" className="secondary" onClick={() => void logout()}>
            Log out
          </button>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}
