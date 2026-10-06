import type { Me, Org, Problem, TokenResponse, User } from './types'

/** A non-2xx answer from the gateway, with its problem+json body when there is one. */
export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`)
    this.name = 'ApiError'
    this.status = problem.status
    this.problem = problem
  }
}

export interface ApiClientOptions {
  /** The gateway, e.g. http://localhost:8080. The app never calls a service directly. */
  baseUrl: string
  fetch?: typeof fetch
}

interface RequestOptions {
  method?: string
  body?: unknown
}

/**
 * Talks to the gateway and owns the session (ADR 0008):
 * - the access token lives only in this object (memory), never in storage;
 * - the refresh token is an HttpOnly cookie the browser sends to /auth/* by itself (credentials: 'include');
 * - a 401 on an API call triggers one refresh and one retry. Concurrent callers share a single refresh,
 *   because refresh tokens are single-use: a second refresh with the same cookie would look like token
 *   theft to identity-service and end the session.
 */
export class ApiClient {
  private readonly baseUrl: string
  private readonly fetchFn: typeof fetch
  private accessToken: string | null = null
  private refreshing: Promise<boolean> | null = null
  private readonly sessionEndedListeners = new Set<() => void>()

  constructor(options: ApiClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, '')
    // Bound: window.fetch throws "Illegal invocation" when called with another `this`.
    this.fetchFn = options.fetch ?? window.fetch.bind(window)
  }

  get hasAccessToken(): boolean {
    return this.accessToken !== null
  }

  /** Called when the session can't be renewed (refresh rejected), so the UI can show the login page. */
  onSessionEnded(listener: () => void): () => void {
    this.sessionEndedListeners.add(listener)
    return () => this.sessionEndedListeners.delete(listener)
  }

  // ---- session endpoints (public at the gateway) ----

  async register(email: string, password: string, displayName: string): Promise<User> {
    return this.send<User>('/auth/register', { method: 'POST', body: { email, password, displayName } })
  }

  async login(email: string, password: string): Promise<void> {
    this.accept(await this.send<TokenResponse>('/auth/login', { method: 'POST', body: { email, password } }))
  }

  /**
   * Trades the refresh cookie for a new access token (and a rotated cookie). Returns false when there is
   * no valid session, e.g. on first visit. Safe to call concurrently: callers share one request.
   */
  refresh(): Promise<boolean> {
    this.refreshing ??= this.send<TokenResponse>('/auth/refresh', { method: 'POST' })
      .then((tokens) => {
        this.accept(tokens)
        return true
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          this.accessToken = null
          return false
        }
        throw error
      })
      .finally(() => {
        this.refreshing = null
      })
    return this.refreshing
  }

  /** Moves the session into another org: new tokens whose `org` claim is that org. */
  async switchOrg(orgId: string): Promise<void> {
    this.accept(await this.send<TokenResponse>('/auth/switch-org', { method: 'POST', body: { orgId } }))
  }

  /** Ends the session on the server and deletes the cookie. Local state is cleared even if that fails. */
  async logout(): Promise<void> {
    try {
      await this.send<void>('/auth/logout', { method: 'POST' })
    } finally {
      this.accessToken = null
    }
  }

  // ---- API calls (need an access token) ----

  me(): Promise<Me> {
    return this.authorized<Me>('/me')
  }

  createOrg(name: string, slug: string): Promise<Org> {
    return this.authorized<Org>('/orgs', { method: 'POST', body: { name, slug } })
  }

  // ---- internals ----

  private accept(tokens: TokenResponse): void {
    this.accessToken = tokens.accessToken
  }

  /** Sends with the access token; on 401 refreshes once and retries once. */
  private async authorized<T>(path: string, options: RequestOptions = {}): Promise<T> {
    try {
      return await this.send<T>(path, options, this.accessToken)
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) {
        throw error
      }
    }
    if (!(await this.refresh())) {
      this.sessionEndedListeners.forEach((listener) => listener())
      throw new ApiError({ status: 401, title: 'Unauthorized', detail: 'Your session has ended. Please log in again.' })
    }
    return this.send<T>(path, options, this.accessToken)
  }

  private async send<T>(path: string, options: RequestOptions, token: string | null = null): Promise<T> {
    const headers: Record<string, string> = {}
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    const response = await this.fetchFn(this.baseUrl + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      // Sends and accepts the refresh cookie on this cross-origin call (gateway allows credentials).
      credentials: 'include',
    })
    if (!response.ok) {
      throw new ApiError(await problemOf(response))
    }
    if (response.status === 204) {
      return undefined as T
    }
    return (await response.json()) as T
  }
}

async function problemOf(response: Response): Promise<Problem> {
  if (response.status === 429) {
    return { status: 429, title: 'Too Many Requests', detail: 'Too many attempts. Wait a minute and try again.' }
  }
  try {
    const body = (await response.json()) as Partial<Problem>
    return { ...body, status: response.status }
  } catch {
    return { status: response.status, title: response.statusText }
  }
}
