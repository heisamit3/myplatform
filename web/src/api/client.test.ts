import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from './client'

const BASE = 'http://gateway.test'

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>

/** A fetch stub that records calls and answers with the given handler. */
function stubFetch(handler: Handler) {
  const calls: { path: string; init: RequestInit }[] = []
  const fetchFn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input)
    calls.push({ path: url.replace(BASE, ''), init })
    return handler(url, init)
  })
  return { fetchFn: fetchFn as unknown as typeof fetch, calls }
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const tokens = (accessToken: string) => json(200, { accessToken, tokenType: 'Bearer', expiresIn: 900 })

const me = { id: 'u1', email: 'a@example.com', displayName: 'A', activeOrgId: null, organizations: [] }

const authHeader = (init: RequestInit) => (init.headers as Record<string, string>).Authorization

describe('ApiClient', () => {
  it('keeps the access token from login and sends it as a Bearer header', async () => {
    const { fetchFn, calls } = stubFetch((url) => (url.endsWith('/auth/login') ? tokens('at-1') : json(200, me)))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    await api.login('a@example.com', 'pw')
    await api.me()

    expect(calls.map((c) => c.path)).toEqual(['/auth/login', '/me'])
    expect(authHeader(calls[1].init)).toBe('Bearer at-1')
  })

  it('always sends credentials so the browser includes the refresh cookie', async () => {
    const { fetchFn, calls } = stubFetch(() => tokens('at-1'))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    await api.refresh()

    expect(calls[0].init.credentials).toBe('include')
    expect(calls[0].init.body).toBeUndefined() // the gateway adds the token from the cookie
  })

  it('refreshes once on a 401 and retries with the new token', async () => {
    let meCalls = 0
    const { fetchFn, calls } = stubFetch((url) => {
      if (url.endsWith('/auth/refresh')) return tokens('at-2')
      meCalls++
      return meCalls === 1 ? json(401, { status: 401 }) : json(200, me)
    })
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    await expect(api.me()).resolves.toEqual(me)
    expect(calls.map((c) => c.path)).toEqual(['/me', '/auth/refresh', '/me'])
    expect(authHeader(calls[2].init)).toBe('Bearer at-2')
  })

  it('shares one refresh between concurrent callers (refresh tokens are single-use)', async () => {
    let refreshes = 0
    const { fetchFn } = stubFetch(async (url) => {
      if (url.endsWith('/auth/refresh')) {
        refreshes++
        await new Promise((resolve) => setTimeout(resolve, 10))
        return tokens('at-2')
      }
      return json(200, me)
    })
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    const results = await Promise.all([api.refresh(), api.refresh(), api.refresh()])

    expect(results).toEqual([true, true, true])
    expect(refreshes).toBe(1)
  })

  it('reports no session when the refresh cookie is missing or rejected', async () => {
    const { fetchFn } = stubFetch(() => json(401, { status: 401 }))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    await expect(api.refresh()).resolves.toBe(false)
    expect(api.hasAccessToken).toBe(false)
  })

  it('ends the session when the retry refresh fails', async () => {
    const { fetchFn } = stubFetch(() => json(401, { status: 401 }))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })
    const ended = vi.fn()
    api.onSessionEnded(ended)

    await expect(api.me()).rejects.toMatchObject({ status: 401 })
    expect(ended).toHaveBeenCalledOnce()
  })

  it('does not refresh on other errors', async () => {
    const { fetchFn, calls } = stubFetch(() => json(409, { status: 409, detail: 'Organization slug is already taken' }))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    const error = await api.createOrg('Acme', 'acme').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toBe('Organization slug is already taken')
    expect(calls).toHaveLength(1)
  })

  it('turns a 429 from the rate limiter into a readable message', async () => {
    const { fetchFn } = stubFetch(() => new Response(null, { status: 429 }))
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })

    await expect(api.login('a@example.com', 'pw')).rejects.toThrow('Too many attempts')
  })

  it('forgets the access token on logout even if the request fails', async () => {
    let loggedIn = false
    const { fetchFn } = stubFetch((url) => {
      if (url.endsWith('/auth/login')) {
        loggedIn = true
        return tokens('at-1')
      }
      return json(503, { status: 503 })
    })
    const api = new ApiClient({ baseUrl: BASE, fetch: fetchFn })
    await api.login('a@example.com', 'pw')
    expect(loggedIn && api.hasAccessToken).toBe(true)

    await expect(api.logout()).rejects.toBeInstanceOf(ApiError)
    expect(api.hasAccessToken).toBe(false)
  })
})
