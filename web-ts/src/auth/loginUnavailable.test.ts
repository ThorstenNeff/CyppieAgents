// @vitest-environment jsdom
// CYP-515 (loud error, UIUX2 spec 62f125e6 §3/§5) — a SYSTEM failure is not a credential verdict.
//
// The bug this closes: when the hardened proxy answers the Kratos flow-init with HTML instead of JSON (or is
// simply not reachable), the old code collapsed that into `rejected` → "Anmeldung fehlgeschlagen. Bitte prüfe
// deine Eingaben." That is a FALSE ACCUSATION: the user's input was never evaluated, and it hides a broken
// deployment behind a credential message. Now those paths report `unavailable`.
//
// THE INVARIANT THAT MAKES THE SPLIT SAFE (§3): `unavailable` fires PRE-CREDENTIAL, so it is identical for every
// e-mail — existing or not. It therefore adds ZERO per-account signal and does not weaken the enumeration-safety
// of CYP-515 (a) §2.3②. That is pinned by a test below, not just asserted in a comment.
import { describe, it, expect, vi } from 'vitest'
import { createLogin } from './loginFlow'
import type { AuthMe } from '../types/generated/contract'

const K = '/k'

function resp(r: { status?: number; ok?: boolean; json?: unknown; retryAfter?: string }): Response {
  const status = r.status ?? 200
  const ok = r.ok ?? (status >= 200 && status < 300)
  return {
    ok,
    status,
    headers: { get: (h: string) => (h.toLowerCase() === 'retry-after' ? (r.retryAfter ?? null) : null) },
    json: async () => {
      if (r.json === undefined) throw new SyntaxError('Unexpected token < in JSON at position 0')
      return r.json
    },
  } as unknown as Response
}

const FLOW = resp({ status: 200, json: { id: 'flow-123', ui: { nodes: [{ attributes: { name: 'csrf_token', value: 'CS-1' } }] } } })
const me = (over: Partial<AuthMe> = {}): AuthMe => ({ authenticated: true, verified: true, ...over })

function makeFetch(init: Response | (() => Promise<never>), submit: Response) {
  const calls: string[] = []
  const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    if (url.includes('/login/browser')) return typeof init === 'function' ? init() : init
    return submit
  }) as unknown as typeof fetch
  return { fetchImpl, calls }
}

const login = (fetchImpl: typeof fetch, fetchAuthMe = vi.fn(async () => me())) => createLogin({ fetchImpl, fetchAuthMe, kratos: K })

describe('CYP-515 — system failure vs credential verdict', () => {
  it('flow-init not ok (proxy/Kratos down) → unavailable, and NO credential POST is attempted', async () => {
    const { fetchImpl, calls } = makeFetch(resp({ status: 502 }), resp({ status: 200 }))
    expect(await login(fetchImpl)('a@b.co', 'pw')).toEqual({ kind: 'unavailable' })
    expect(calls.filter((u) => !u.includes('/login/browser'))).toEqual([]) // credentials never left the browser
  })

  it('proxy returns HTML instead of JSON (the CYP-515 contract break) → unavailable, not "check your input"', async () => {
    // `json: undefined` makes .json() throw a SyntaxError — exactly what an HTML error page does. This IS the
    // Content-Type guard: a non-JSON body can never be read as a flow.
    const { fetchImpl, calls } = makeFetch(resp({ status: 200 }), resp({ status: 200 }))
    expect(await login(fetchImpl)('a@b.co', 'pw')).toEqual({ kind: 'unavailable' })
    expect(calls.filter((u) => !u.includes('/login/browser'))).toEqual([])
  })

  it('flow contract broken (no id / no csrf) → unavailable, still fail-closed (never submits blind)', async () => {
    for (const bad of [resp({ status: 200, json: { id: 'f' } }), resp({ status: 200, json: { ui: { nodes: [] } } })]) {
      const { fetchImpl, calls } = makeFetch(bad, resp({ status: 200 }))
      expect(await login(fetchImpl)('a@b.co', 'pw')).toEqual({ kind: 'unavailable' })
      expect(calls.filter((u) => !u.includes('/login/browser'))).toEqual([])
    }
  })

  it('transport failure reaching the flow-init → unavailable', async () => {
    const { fetchImpl } = makeFetch(() => Promise.reject(new TypeError('Failed to fetch')), resp({ status: 200 }))
    expect(await login(fetchImpl)('a@b.co', 'pw')).toEqual({ kind: 'unavailable' })
  })

  it('★ a REAL 4xx rejection of the submit stays `rejected` — the only "check your input" path', async () => {
    for (const status of [400, 401, 403]) {
      const { fetchImpl } = makeFetch(FLOW, resp({ status }))
      expect(await login(fetchImpl)('a@b.co', 'pw')).toEqual({ kind: 'rejected' })
    }
  })

  it('429 stays rateLimited and 2xx still resolves via whoami (unchanged paths)', async () => {
    const { fetchImpl: f429 } = makeFetch(FLOW, resp({ status: 429, retryAfter: '30s' }))
    expect(await login(f429)('a@b.co', 'pw')).toEqual({ kind: 'rateLimited', retryAfter: '30s' })

    const { fetchImpl: fOk } = makeFetch(FLOW, resp({ status: 200 }))
    expect(await login(fOk, vi.fn(async () => me({ role: 'OPERATOR' })))('a@b.co', 'pw')).toEqual({ kind: 'verified', operator: true })
    const { fetchImpl: fOk2 } = makeFetch(FLOW, resp({ status: 200 }))
    expect(await login(fOk2, vi.fn(async () => me({ verified: false })))('a@b.co', 'pw')).toEqual({ kind: 'unverified' })
  })

  it('★ enumeration-safety holds: `unavailable` is IDENTICAL for a known and an unknown e-mail (pre-credential)', async () => {
    // The outcome must not vary with the identifier — that is what keeps the new state from becoming an oracle.
    const outcomes = await Promise.all(
      ['known@example.com', 'nobody@example.com', ''].map(async (email) => {
        const { fetchImpl } = makeFetch(resp({ status: 502 }), resp({ status: 200 }))
        return login(fetchImpl)(email, 'pw')
      }),
    )
    expect(outcomes).toEqual([{ kind: 'unavailable' }, { kind: 'unavailable' }, { kind: 'unavailable' }])
  })

  it('the identifier is never sent on a system-failure path (no credential leak to a broken proxy)', async () => {
    const { fetchImpl, calls } = makeFetch(resp({ status: 502 }), resp({ status: 200 }))
    await login(fetchImpl)('secret-user@example.com', 'hunter2')
    expect(JSON.stringify(calls)).not.toContain('secret-user')
    expect(JSON.stringify(calls)).not.toContain('hunter2')
  })
})
