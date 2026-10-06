import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api/api'
import { AuthContext, type Auth, type AuthState } from './context'

/**
 * Holds who is signed in. On start it tries to resume a session from the refresh cookie, so a page
 * reload doesn't log the user out even though the access token only lives in memory.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  const loadMe = useCallback(async () => {
    setState({ status: 'authenticated', me: await api.me() })
  }, [])

  useEffect(() => {
    let active = true
    // StrictMode runs this twice in development; both runs share one refresh request (see ApiClient).
    api
      .refresh()
      .then(async (resumed) => {
        const me = resumed ? await api.me() : null
        if (active) setState(me ? { status: 'authenticated', me } : { status: 'anonymous' })
      })
      .catch(() => {
        if (active) setState({ status: 'anonymous' })
      })
    const unsubscribe = api.onSessionEnded(() => setState({ status: 'anonymous' }))
    return () => {
      active = false
      unsubscribe()
    }
  }, [])

  const auth = useMemo<Auth>(
    () => ({
      state,
      async login(email, password) {
        await api.login(email, password)
        await loadMe()
      },
      async register(email, password, displayName) {
        await api.register(email, password, displayName)
        await api.login(email, password)
        await loadMe()
      },
      async logout() {
        try {
          await api.logout()
        } finally {
          setState({ status: 'anonymous' })
        }
      },
      async switchOrg(orgId) {
        await api.switchOrg(orgId)
        await loadMe()
      },
      async createOrg(name, slug) {
        const org = await api.createOrg(name, slug)
        await api.switchOrg(org.id)
        await loadMe()
        return org
      },
    }),
    [state, loadMe],
  )

  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
}
