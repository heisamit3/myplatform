// Shapes from contracts/openapi/identity.yaml, as the browser sees them through the gateway
// (no refreshToken in token responses: it lives in an HttpOnly cookie, ADR 0008).

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface TokenResponse {
  accessToken: string
  tokenType: string
  /** Access-token lifetime in seconds. */
  expiresIn: number
}

export interface User {
  id: string
  email: string
  displayName: string
}

export interface Org {
  id: string
  name: string
  slug: string
  /** The caller's role in this org. */
  role: Role
}

export interface Me extends User {
  /** Org of the current access token; null until the user has one. */
  activeOrgId: string | null
  /** All orgs of the user, oldest membership first. */
  organizations: Org[]
}

/** RFC 9457 problem details, the error format of the gateway and identity-service. */
export interface Problem {
  type?: string
  title?: string
  status: number
  detail?: string
}
