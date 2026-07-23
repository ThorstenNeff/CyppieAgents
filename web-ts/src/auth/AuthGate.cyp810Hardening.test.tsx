// @vitest-environment jsdom
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-810 G-c1 (★ SECURITY-ADJACENT) — the break-glass posture is an OPERATOR posture; its operator flag was unpinned.
//
// AuthGate.tsx:44 seeds the injected-operator-token (break-glass) path with `{ kind: 'active', operator: true }` and
// hands `state.operator` to `children(operator)` (:113) — the tier the whole app mounts at. The existing break-glass
// test (AuthGate.render.test.tsx:107-111) asserts only that app-content renders and whoami is NOT called; it never reads
// `operator`. So a mutation `operator: true → false` SILENTLY DOWNGRADES a break-glass operator to MEMBER — the app
// mounts at the wrong (lower) tier — and the whole suite stays green. This guard pins the operator flag the app receives
// AND the honest visible role, so that downgrade reddens.
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { AuthGate } from './AuthGate'
import { signedInAs } from './authModel'
import type { AuthMe } from '../types/generated/contract'

afterEach(cleanup)

function renderBreakGlass() {
  const childOperator = vi.fn<(operator: boolean) => void>()
  // whoami resolves to MEMBER — so if the break-glass path EVER fell through to whoami, or seeded operator:false, the
  // guard would catch it. Break-glass must NOT consult this (it authenticates by the injected Bearer).
  const fetchAuthMe = vi.fn(() => Promise.resolve({ authenticated: true, verified: true, role: 'MEMBER' } as AuthMe))
  const utils = render(
    <AuthGate
      activeHubId="local"
      fetchAuthMe={fetchAuthMe as () => Promise<AuthMe>}
      login={vi.fn(async () => ({ kind: 'rejected' as const }))}
      redirectToLogout={vi.fn()}
      breakGlass
      onInstallUnauthorized={vi.fn()}
    >
      {(operator) => {
        childOperator(operator)
        return <div data-testid="app-content">operator={String(operator)}</div>
      }}
    </AuthGate>,
  )
  return { ...utils, childOperator, fetchAuthMe }
}

describe('CYP-810 G-c1 (security-adjacent) — break-glass is an OPERATOR posture (no silent operator→member downgrade)', () => {
  it('★ the app receives operator === true from the break-glass path (mutation operator:true→false REDs)', () => {
    const { childOperator } = renderBreakGlass()
    // THE security property: the tier the app mounts at. A downgraded/mistyped flag never reaches the app as `true`.
    expect(childOperator).toHaveBeenCalledWith(true)
    expect(childOperator).not.toHaveBeenCalledWith(false)
  })

  it('★ the app mounts at the OPERATOR tier without consulting whoami (break-glass bypasses the gate by Bearer)', () => {
    const { getByTestId, fetchAuthMe } = renderBreakGlass()
    expect(getByTestId('app-content').textContent).toContain('operator=true')
    expect(fetchAuthMe).not.toHaveBeenCalled() // never falls through to whoami (which would say MEMBER)
  })

  it('★ the visible session role reads Operator, never Member (honest role indicator, colour-never-alone)', () => {
    const { getByTestId } = renderBreakGlass()
    const role = getByTestId('auth.role').textContent
    expect(role).toBe(signedInAs(true))
    expect(role).not.toBe(signedInAs(false)) // signedInAs distinguishes the two tiers, so this is non-vacuous
  })
})
