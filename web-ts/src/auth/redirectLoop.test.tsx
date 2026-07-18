// @vitest-environment jsdom
// CYP-515 — the auth REDIRECT-LOOP guard. The reported bug (12.07.): the SPA looped "Weiterleiten zur Anmeldung",
// minting a fresh Kratos flow-id per iteration until it hit 429. CYP-515 (a) (`4e674d48`) already killed the loop
// STRUCTURALLY — the None state renders the in-app LoginScreen instead of navigating to the login flow-init, so at
// HEAD the loop no longer reproduces (verified: `loginUrl()` has zero call sites).
//
// But "no redirect" was only ever asserted by COMMENT (AuthGate.render.test.tsx: "no window redirect anywhere") —
// nothing OBSERVED navigation, and nothing stopped a future edit from re-wiring `loginUrl()` and silently bringing
// the loop back. These are the teeth that make the anti-loop property load-bearing instead of incidental:
//   ① the unauth (None) path performs ZERO navigations — observed on the injected navigation seam, not assumed;
//   ② the mid-session 401 re-auth path performs ZERO navigations (this is the path that LOOPED: every 401 re-fired
//      the redirect, and each redirect minted a new flow-id → 429);
//   ③ flow-inits are USER-DRIVEN and bounded: 0 without a submit, exactly N for N submits — a rejected login never
//      auto-re-inits. Unbounded/automatic re-init IS the loop, and is what produced the app-driven 429;
//   ④ structural: no module navigates to a LOGIN url. This is the tooth that would have caught the original bug,
//      and it fails closed on reintroduction anywhere in src/ — not just in the files this suite happens to mount.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent } from '@testing-library/react'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { AuthGate } from './AuthGate'
import { createLogin } from './loginFlow'
import type { AuthMe } from '../types/generated/contract'
import type { LoginResult } from './authModel'

// --- navigation observation ---------------------------------------------------------------------------------
// jsdom's `window.location` is [Unforgeable] (non-redefinable), so it cannot be stubbed. It does not need to be:
// AuthGate has exactly ONE navigation seam and it is INJECTED (`redirectToLogout` — main.tsx supplies the only real
// `window.location.assign`). So a spy on that seam observes every navigation the gate can perform by design, and
// tooth ④ closes the one remaining escape hatch — a module importing `location` directly. Together: complete.
afterEach(cleanup)

const me = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })

const mount = (over: { authMe?: AuthMe; reject?: boolean; login?: (e: string, p: string) => Promise<LoginResult> } = {}) => {
  let installed: (() => void) | null = null
  const navigate = vi.fn() // the ONLY navigation seam the gate has
  const fetchAuthMe = vi.fn(() => (over.reject ? Promise.reject(new Error('down')) : Promise.resolve(over.authMe ?? me({ role: 'OPERATOR' }))))
  const utils = render(
    <AuthGate
      fetchAuthMe={fetchAuthMe as () => Promise<AuthMe>}
      login={vi.fn(over.login ?? (async () => ({ kind: 'rejected' }) as LoginResult))}
      redirectToLogout={navigate}
      onInstallUnauthorized={(h) => {
        installed = h
      }}
    >
      {(operator) => <div data-testid="app-content">app operator={String(operator)}</div>}
    </AuthGate>,
  )
  return { ...utils, navigate, fireInstalled: () => installed?.() }
}

describe('CYP-515 — auth redirect loop (repro at HEAD + reintroduction guard)', () => {
  it('① unauth (None) renders the in-app login and performs ZERO navigations — the loop does not reproduce', async () => {
    const { findByTestId, navigate } = mount({ reject: true })
    expect(await findByTestId('auth.login.form')).toBeTruthy()
    expect(navigate).not.toHaveBeenCalled() // OBSERVED, not assumed: nothing navigated → no flow-init → no loop
  })

  it('② a mid-session 401 re-auths IN-APP with ZERO navigations (the path that looped)', async () => {
    const { findByTestId, fireInstalled, navigate } = mount({ authMe: me({ role: 'OPERATOR' }) })
    await findByTestId('app-content')
    act(() => fireInstalled())
    expect(await findByTestId('auth.login.form')).toBeTruthy()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('② repeated 401s (the loop driver) still never navigate — N re-auths, 0 redirects', async () => {
    const { findByTestId, fireInstalled, navigate } = mount({ authMe: me({ role: 'OPERATOR' }) })
    await findByTestId('app-content')
    for (let i = 0; i < 5; i++) act(() => fireInstalled())
    expect(await findByTestId('auth.login.form')).toBeTruthy()
    expect(navigate).not.toHaveBeenCalled() // pre-fix this was 5 redirects → 5 fresh flow-ids → 429
  })

  it('③ flow-inits are user-driven and bounded: 0 unattended, exactly 1 per submit, no auto-re-init after a reject', async () => {
    let inits = 0
    const fetchImpl = vi.fn(async (url: unknown) => {
      const u = String(url)
      if (u.includes('/self-service/login/browser')) {
        inits++
        return new Response(JSON.stringify({ id: `flow-${inits}`, ui: { nodes: [{ attributes: { name: 'csrf_token', value: 'c' } }] } }), { status: 200 })
      }
      return new Response('{}', { status: 401 }) // every submit is REJECTED — the pre-fix loop condition
    })
    const login = createLogin({ fetchImpl: fetchImpl as unknown as typeof fetch, fetchAuthMe: async () => me({ role: 'OPERATOR' }) })

    expect(inits).toBe(0) // nothing minted a flow just by existing

    for (let n = 1; n <= 3; n++) {
      const r = await login('a@b.co', 'pw')
      expect(r.kind).toBe('rejected') // generic, and critically: NOT a retry trigger
      expect(inits).toBe(n) // exactly one init per USER submit — never a self-sustaining storm
    }
  })

  it('③ a rejected in-app submit leaves the user on the form — it does not navigate (no new flow-id, no 429 path)', async () => {
    const { findByTestId, getByTestId, navigate } = mount({ reject: true, login: async () => ({ kind: 'rejected' }) })
    await findByTestId('auth.login.form')
    fireEvent.change(getByTestId('auth.login.email'), { target: { value: 'a@b.co' } })
    fireEvent.change(getByTestId('auth.login.password'), { target: { value: 'pw' } })
    await act(async () => void fireEvent.submit(getByTestId('auth.login.form')))
    expect(getByTestId('auth.login.form')).toBeTruthy()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('④ structural: no src module navigates to a LOGIN url (the exact shape of the original loop)', () => {
    // Scans real source (not just what this suite mounts), so re-wiring `loginUrl()` into a navigation ANYWHERE
    // fails closed. Logout navigation is legitimate and must stay allowed — it is a one-shot, server-authoritative
    // terminal action, not a gate the app bounces off.
    const walk = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
        const p = join(dir, e.name)
        if (e.isDirectory()) return e.name === 'generated' ? [] : walk(p)
        return /\.(ts|tsx)$/.test(e.name) && !/\.test\.(ts|tsx)$/.test(e.name) ? [p] : []
      })

    const NAV = /(?:window\.)?location\s*\.\s*(?:assign|replace)\s*\(([^)]*)\)|(?:window\.)?location\s*\.\s*href\s*=\s*([^\n;]*)/g
    const offenders: string[] = []
    for (const file of walk('src')) {
      const src = readFileSync(file, 'utf8')
      for (const m of src.matchAll(NAV)) {
        const arg = (m[1] ?? m[2] ?? '').trim()
        if (/login/i.test(arg)) offenders.push(`${file}: ${m[0].trim()}`)
      }
    }
    expect(offenders).toEqual([])
  })
})
