// @vitest-environment jsdom
// CYP-749 (HIGH/Security) — the operator token is fail-CLOSED on a BLANK value. A proxy that declares
// CYPPIE_OPERATOR_TOKEN but leaves it empty/whitespace is a MISCONFIGURATION, not an operator serve: the old
// `?? null` let it through (empty string is not null), so `isOperatorServe()` went true → operator surface unlocked
// AND the whoami gate skipped (AuthGate break-glass). A blank token authenticates nothing → it must resolve MEMBER.
//
// The tooth is NON-VACUOUS by construction: it pins the two blank shapes (`''`, `'   '`) to DENIED and, via the real
// break-glass wiring, to whoami-NOT-skipped — AND carries a POSITIVE CONTROL (a genuine token still unlocks + skips),
// so a fix that simply returned `null` always would fail here too. Mutation-verified RED against the old `?? null`.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { operatorToken, isOperatorServe } from './operatorToken'
import { AuthGate } from '../auth/AuthGate'
import type { AuthMe } from '../types/generated/contract'
import type { LoginResult } from '../auth/authModel'

const G = globalThis as { CYPPIE_OPERATOR_TOKEN?: string }
const setToken = (v: string | undefined) => {
  if (v === undefined) delete G.CYPPIE_OPERATOR_TOKEN
  else G.CYPPIE_OPERATOR_TOKEN = v
}

afterEach(() => {
  setToken(undefined)
  cleanup()
  vi.restoreAllMocks()
})

describe('CYP-749 — operatorToken() / isOperatorServe() are fail-closed on a blank value', () => {
  it('★ empty string ⇒ null and NOT an operator serve (no unlock)', () => {
    setToken('')
    expect(operatorToken()).toBeNull()
    expect(isOperatorServe()).toBe(false)
  })

  it('★ whitespace-only ⇒ null — a blank value authenticates nothing', () => {
    setToken('   ')
    expect(operatorToken()).toBeNull()
    expect(isOperatorServe()).toBe(false)
  })

  it('absent (undefined) ⇒ null — the original member posture, preserved', () => {
    setToken(undefined)
    expect(operatorToken()).toBeNull()
    expect(isOperatorServe()).toBe(false)
  })

  it('★ POSITIVE CONTROL: a genuine non-blank token is returned VERBATIM and unlocks — the guard is not "always null"', () => {
    setToken('op-secret-123')
    expect(operatorToken()).toBe('op-secret-123')
    expect(isOperatorServe()).toBe(true)
  })
})

describe('CYP-749 — a blank token does NOT skip the whoami gate (break-glass off), via the real wiring', () => {
  // main.tsx wires `breakGlass={isOperatorServe()}`, so reading it here exercises the exact path a blank token takes.
  const renderGate = () => {
    const fetchAuthMe = vi.fn(
      () => Promise.resolve({ authenticated: true, verified: true, role: 'MEMBER' } as AuthMe),
    )
    const childOperator = vi.fn()
    const utils = render(
      <AuthGate
        fetchAuthMe={fetchAuthMe as () => Promise<AuthMe>}
        login={vi.fn(async () => ({ kind: 'rejected' }) as LoginResult)}
        redirectToLogout={vi.fn()}
        breakGlass={isOperatorServe()}
        onInstallUnauthorized={vi.fn()}
      >
        {(operator) => {
          childOperator(operator)
          return <div data-testid="app-content" />
        }}
      </AuthGate>,
    )
    return { fetchAuthMe, childOperator, ...utils }
  }

  it('★ blank token ⇒ whoami IS fetched (gate not bypassed) and operator resolves to MEMBER, not auto-true', async () => {
    setToken('   ')
    const { fetchAuthMe, childOperator } = renderGate()
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(fetchAuthMe).toHaveBeenCalledTimes(1) // whoami ran — the security fix: not skipped on a blank token
    expect(childOperator).toHaveBeenCalledWith(false) // role MEMBER → not operator (server is the truth, fail-closed)
    expect(childOperator).not.toHaveBeenCalledWith(true)
  })

  it('★ POSITIVE CONTROL: a genuine token ⇒ break-glass, whoami skipped (Bearer authenticates every request)', () => {
    setToken('op-secret-123')
    const { fetchAuthMe, childOperator } = renderGate()
    // break-glass mounts active-operator synchronously; the whoami effect early-returns.
    expect(fetchAuthMe).not.toHaveBeenCalled()
    expect(childOperator).toHaveBeenCalledWith(true)
  })
})
