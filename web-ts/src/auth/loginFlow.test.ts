// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { createLogin } from './loginFlow'
import { setOnUnauthorized } from '../net/rest'
import type { AuthMe } from '../types/generated/contract'

const K = '/k' // test kratos base

/** A minimal fake Response for the two calls the flow makes. */
function resp(r: { status?: number; ok?: boolean; json?: unknown; retryAfter?: string }): Response {
  const status = r.status ?? 200
  const ok = r.ok ?? (status >= 200 && status < 300)
  return {
    ok,
    status,
    headers: { get: (h: string) => (h.toLowerCase() === 'retry-after' ? (r.retryAfter ?? null) : null) },
    json: async () => r.json ?? {},
  } as unknown as Response
}

// A valid Kratos browser-flow init always carries a flow id AND a csrf_token node.
const FLOW = resp({ status: 200, json: { id: 'flow-123', ui: { nodes: [{ attributes: { name: 'csrf_token', value: 'CS-1' } }] } } })
const FLOW_NO_CSRF = resp({ status: 200, json: { id: 'flow-123' } }) // malformed browser flow → must fail closed
const FLOW_NO_ID = resp({ status: 200, json: { ui: { nodes: [{ attributes: { name: 'csrf_token', value: 'CS-1' } }] } } })

/** Build a fetch that returns `init` for the flow-init GET and `submit` for the login POST, recording every call. */
function makeFetch(init: Response, submit: Response) {
  const calls: { url: string; init?: RequestInit }[] = []
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, opts?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init: opts })
    return url.includes('/login/browser') ? init : submit
  }) as unknown as typeof fetch
  return { fetchImpl, calls }
}

const authMe = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })
const okFetchAuthMe = (me: AuthMe) => vi.fn(async () => me)

afterEach(() => setOnUnauthorized(null))

describe('createLogin (CYP-515 (a) in-app login-core — Kratos BROWSER flow, native httpOnly cookie)', () => {
  it('inits the BROWSER flow (cookie-setting) with Accept: application/json — not the API flow', async () => {
    const { fetchImpl, calls } = makeFetch(FLOW, resp({ status: 200 }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({ role: 'MEMBER' })), kratos: K })
    await login('a@b.co', 'pw')
    const init = calls.find((c) => (c.init?.method ?? 'GET') === 'GET')!
    expect(init.url).toContain('/self-service/login/browser') // the cookie-setting flow, not /login/api
    expect((init.init?.headers as Record<string, string>).accept).toBe('application/json') // → flow JSON, no 303
    expect(init.init?.credentials).toBe('include')
  })

  it('① credentials go in the BODY, never the URL/query (no referrer/history/log leak)', async () => {
    const { fetchImpl, calls } = makeFetch(FLOW, resp({ status: 200 }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({ role: 'MEMBER' })), kratos: K })
    await login('user@example.com', 's3cret')
    const post = calls.find((c) => c.init?.method === 'POST')!
    expect(post.url).not.toContain('s3cret') // password NEVER in the URL
    expect(post.url).not.toContain('user@example.com') // identifier NEVER in the URL
    const body = JSON.parse(post.init!.body as string)
    expect(body).toMatchObject({ method: 'password', identifier: 'user@example.com', password: 's3cret', csrf_token: 'CS-1' })
    expect(post.init!.credentials).toBe('include') // Kratos sets the httpOnly cookie on this response
  })

  it('② enumeration: 400 AND 401 both collapse to the SAME generic rejected (no distinguishable outcome)', async () => {
    for (const status of [400, 401, 403, 500]) {
      const { fetchImpl } = makeFetch(FLOW, resp({ status }))
      const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
      expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
    }
  })

  it('④ a login 401 does NOT fire the global setOnUnauthorized re-auth hook (no login-surface loop, CYP-515)', async () => {
    const hook = vi.fn()
    setOnUnauthorized(hook)
    const { fetchImpl } = makeFetch(FLOW, resp({ status: 401 }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
    expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
    expect(hook).not.toHaveBeenCalled() // direct fetch bypasses the RestClient hook
  })

  it('② never writes the session to JS storage — httpOnly cookie only, even if the body carries a token', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const { fetchImpl } = makeFetch(FLOW, resp({ status: 200, json: { session_token: 'SECRET-TOKEN' } }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({ role: 'OPERATOR' })), kratos: K })
    expect(await login('a@b.co', 'pw')).toEqual({ kind: 'verified', operator: true })
    expect(setItem).not.toHaveBeenCalled() // the raw session_token is never persisted in JS
    setItem.mockRestore()
  })

  it('⑤ a 429 → honest rateLimited with the server Retry-After hint, never a fake success', async () => {
    const { fetchImpl } = makeFetch(FLOW, resp({ status: 429, retryAfter: '30' }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
    expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rateLimited', retryAfter: '30' })
  })

  it('⑥ stale/malformed flow: no id OR no csrf → rejected AND no credential POST (fail-closed, never submit blind)', async () => {
    for (const bad of [FLOW_NO_ID, FLOW_NO_CSRF]) {
      const { fetchImpl, calls } = makeFetch(bad, resp({ status: 200 }))
      const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
      expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
      expect(calls.some((c) => c.init?.method === 'POST')).toBe(false)
    }
  })

  it('a non-ok flow-init → rejected, no submit (fail-closed)', async () => {
    const { fetchImpl, calls } = makeFetch(resp({ status: 500 }), resp({ status: 200 }))
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
    expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
    expect(calls.some((c) => c.init?.method === 'POST')).toBe(false)
  })

  it('success maps the whoami state (server is source of truth): verified/unverified/none', async () => {
    const mk = (me: AuthMe) => createLogin({ fetchImpl: makeFetch(FLOW, resp({ status: 200 })).fetchImpl, fetchAuthMe: okFetchAuthMe(me), kratos: K })
    expect(await mk(authMe({ role: 'OPERATOR' }))('a@b.co', 'pw')).toEqual({ kind: 'verified', operator: true })
    expect(await mk(authMe({ role: 'MEMBER' }))('a@b.co', 'pw')).toEqual({ kind: 'verified', operator: false })
    expect(await mk(authMe({ verified: false, role: null }))('a@b.co', 'pw')).toEqual({ kind: 'unverified' })
    expect(await mk({ authenticated: false, verified: false })('a@b.co', 'pw')).toEqual({ kind: 'rejected' }) // post-2xx none → fail-closed
  })

  it('any transport/parse error → generic rejected (fail-closed)', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new Error('network down')
    }) as unknown as typeof fetch
    const login = createLogin({ fetchImpl, fetchAuthMe: okFetchAuthMe(authMe({})), kratos: K })
    expect(await login('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
  })
})
