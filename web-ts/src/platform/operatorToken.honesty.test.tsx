// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { operatorToken, isOperatorServe } from './operatorToken'
import { AuthGate } from '../auth/AuthGate'
import type { AuthMe } from '../types/generated/contract'
import type { LoginResult } from '../auth/authModel'

// CYP-749 — the operator-posture decision is derived from an UNCHECKED deploy global, and the current read is
// fail-OPEN. `operatorToken()` = `CYPPIE_OPERATOR_TOKEN ?? null`; `??` only fills null/undefined, so an injected
// EMPTY or whitespace-only string is treated as a PRESENT token → isOperatorServe() true.
//
// Coordinator's two sharpenings, both encoded here:
//   (1) FIX THE CLASS, NOT THE INSTANCE. `empty→null` closes only "" — the twin " " (or any present-but-not-genuine
//       token) re-opens it. The rule pinned below is: operatorToken() returns the token ONLY for a genuine, non-blank
//       string, and null (fail-CLOSED) for EVERYTHING else — "", " ", "\t", "\n", mixed blanks.
//   (2) NON-VACUOUS, AT THE CONSEQUENCE. Assert the real downstream, not just the helper: for every non-genuine token
//       the whoami gate is NOT skipped (access is decided by the real session, not a blank Bearer) — main.tsx wires
//       `breakGlass={isOperatorServe()}` into AuthGate, and breakGlass=true is exactly what SKIPS whoami and grants
//       operator immediately. PLUS a positive control: a genuine token DOES skip whoami and grant — proving the deny
//       is BECAUSE the token is non-genuine, not because the gate is broken shut.
//
// STATUS vs current develop: the ★ cases are EXPECTED-RED (the fix has not landed). They pin the fail-closed contract
// the file's own header promises ("Absent → null → fail-closed MEMBER posture"). When operatorToken() normalises any
// non-genuine value to null, every ★ flips GREEN with no other change.

const KEY = 'CYPPIE_OPERATOR_TOKEN' as const
const g = globalThis as Record<string, unknown>

const GENUINE = 'op-secret-123'
// every one of these is "present-but-not-genuine" — a blank Bearer authenticates nobody
const NON_GENUINE = ['', ' ', '   ', '\t', '\n', '  \t \n '] as const

beforeEach(() => {
  delete g[KEY]
})
afterEach(() => {
  delete g[KEY]
  cleanup()
})

// ── (1) class-level: genuine token only; fail-CLOSED on everything else ──────────────────────────────────────────
describe('CYP-749 (1) operatorToken is genuine-only, fail-CLOSED on any non-genuine value', () => {
  it('a GENUINE token is operator posture (positive control — the guard is not vacuously closed)', () => {
    g[KEY] = GENUINE
    expect(operatorToken()).toBe(GENUINE)
    expect(isOperatorServe()).toBe(true)
  })

  it('an ABSENT global is MEMBER posture (the case the current code already handles)', () => {
    delete g[KEY]
    expect(operatorToken()).toBeNull()
    expect(isOperatorServe()).toBe(false)
  })

  it.each(NON_GENUINE)('★ a non-genuine token %j is MEMBER posture — a blank token is no token', (tok) => {
    g[KEY] = tok
    expect(operatorToken()).toBeNull()
    expect(isOperatorServe()).toBe(false)
  })
})

// ── (2) consequence-level: the whoami gate + operator grant, mounted exactly as main.tsx wires it ────────────────
const me = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })

/** Mount AuthGate the way main.tsx does: breakGlass = isOperatorServe(). Returns the whoami spy + a record of the
 *  operator tier the app was mounted at (null = app not mounted / still gated). */
function mountAsMain() {
  const fetchAuthMe = vi.fn<() => Promise<AuthMe>>(() => Promise.resolve(me({ role: 'OPERATOR' })))
  const login = vi.fn(async () => ({ kind: 'rejected' }) as LoginResult)
  const grantedAt: (boolean | null)[] = []
  const utils = render(
    <AuthGate
      fetchAuthMe={fetchAuthMe}
      login={login}
      redirectToLogout={vi.fn()}
      breakGlass={isOperatorServe()} // ← the exact main.tsx wiring
      onInstallUnauthorized={vi.fn()}
    >
      {(operator) => {
        grantedAt.push(operator)
        return <div data-testid="app-content">operator={String(operator)}</div>
      }}
    </AuthGate>,
  )
  return { fetchAuthMe, utils, grantedImmediately: () => grantedAt.length > 0 }
}

describe('CYP-749 (2) a non-genuine token does NOT skip the whoami gate (consequence)', () => {
  it('positive control: a GENUINE token SKIPS whoami and grants operator immediately (break-glass)', () => {
    g[KEY] = GENUINE
    const { fetchAuthMe, utils, grantedImmediately } = mountAsMain()
    expect(fetchAuthMe).not.toHaveBeenCalled() // whoami skipped — the Bearer authenticates
    expect(grantedImmediately()).toBe(true)
    expect(utils.queryByTestId('app-content')).toBeTruthy()
    expect(utils.queryByTestId('auth.loading')).toBeNull()
  })

  it.each(NON_GENUINE)('★ a non-genuine token %j does NOT skip whoami — access is decided by the real session', (tok) => {
    g[KEY] = tok
    const { fetchAuthMe, utils, grantedImmediately } = mountAsMain()
    // the whole point: with an empty Bearer the app must NOT break-glass into operator; it must resolve whoami.
    expect(fetchAuthMe).toHaveBeenCalled() // whoami NOT skipped
    expect(grantedImmediately()).toBe(false) // no immediate operator grant on a blank token
    expect(utils.queryByTestId('auth.loading')).toBeTruthy() // still gated on the real session
  })
})
