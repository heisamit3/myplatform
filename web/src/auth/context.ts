import { createContext, useContext } from 'react'
import type { Me, Org } from '../api/types'

export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; me: Me }

export interface Auth {
  state: AuthState
  login(email: string, password: string): Promise<void>
  register(email: string, password: string, displayName: string): Promise<void>
  logout(): Promise<void>
  switchOrg(orgId: string): Promise<void>
  /** Creates the org and moves the session into it. */
  createOrg(name: string, slug: string): Promise<Org>
}

export const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return auth
}

/** For pages behind <RequireAuth>, where the user is always known. */
export function useMe(): Me {
  const { state } = useAuth()
  if (state.status !== 'authenticated') {
    throw new Error('useMe needs an authenticated session')
  }
  return state.me
}
