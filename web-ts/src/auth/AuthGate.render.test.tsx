// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent, waitFor } from '@testing-library/react'
import { AuthGate } from './AuthGate'
import type { AuthMe } from '../types/generated/contract'

const me = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })

const setup = (over: {
  authMe?: AuthMe | Promise<AuthMe>
  reject?: boolean
  breakGlass?: boolean
  flowReturnPresent?: boolean
} = {}) => {
  const redirectToLogin = vi.fn()
  const redirectToLogout = vi.fn()
  const fetchAuthMe = vi.fn(() =>
    over.reject ? Promise.reject(new Error('down')) : Promise.resolve(over.authMe ?? me({ role: 'OPERATOR' })),
  )
  const childOperator = vi.fn()
  const utils = render(
    <AuthGate
      fetchAuthMe={fetchAuthMe as () => Promise<AuthMe>}
      redirectToLogin={redirectToLogin}
      redirectToLogout={redirectToLogout}
      breakGlass={over.breakGlass}
      flowReturnPresent={over.flowReturnPresent}
    >
      {(operator) => {
        childOperator(operator)
        return <div data-testid="app-content">app operator={String(operator)}</div>
      }}
    </AuthGate>,
  )
  return { ...utils, redirectToLogin, redirectToLogout, fetchAuthMe, childOperator }
}

const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve() })

afterEach(cleanup)

describe('AuthGate (CYP-470)', () => {
  it('resolve-then-render: renders NOTHING of the app before whoami resolves (anti-flash, tooth 7)', () => {
    const { getByTestId, queryByTestId } = setup()
    // synchronously (whoami not yet resolved) → only the loading screen, no app content
    expect(getByTestId('auth.loading')).toBeTruthy()
    expect(queryByTestId('app-content')).toBeNull()
  })

  it('active + OPERATOR → renders the app with operator=true + a content-free session indicator (tooth 2)', async () => {
    const { findByTestId, getByTestId } = setup({ authMe: me({ role: 'OPERATOR' }) })
    expect(await findByTestId('app-content')).toBeTruthy()
    expect(getByTestId('app-content').textContent).toContain('operator=true')
    expect(getByTestId('auth.role').textContent).toBe('Angemeldet als Operator')
  })

  it('active + MEMBER → app with operator=false (whoami drives it, fail-closed)', async () => {
    const { findByTestId, getByTestId } = setup({ authMe: me({ role: 'MEMBER' }) })
    await findByTestId('app-content')
    expect(getByTestId('app-content').textContent).toContain('operator=false')
  })

  it('unverified → verify-gate, NO app access (tooth 5)', async () => {
    const { findByTestId, queryByTestId } = setup({ authMe: me({ verified: false, role: null }) })
    expect(await findByTestId('auth.verifyGate')).toBeTruthy()
    expect(queryByTestId('app-content')).toBeNull()
  })

  it('none (whoami error, fail-closed) → redirect to login, no app content (tooth 2 fail-closed)', async () => {
    const { findByTestId, queryByTestId, redirectToLogin } = setup({ reject: true })
    expect(await findByTestId('auth.redirect')).toBeTruthy()
    await waitFor(() => expect(redirectToLogin).toHaveBeenCalled()) // redirect fires in an effect after the state flips
    expect(queryByTestId('app-content')).toBeNull()
  })

  it('none + ?flow= return → does NOT auto re-redirect (CYP-515 loop guard, UIUX tooth: no re-redirect, no loop)', async () => {
    const { findByTestId, redirectToLogin } = setup({ reject: true, flowReturnPresent: true })
    expect(await findByTestId('auth.flowStranded')).toBeTruthy() // neutral continue-to-login, not the redirect screen
    await flush()
    expect(redirectToLogin).not.toHaveBeenCalled() // the loop driver is suppressed — no rate-limit hammering
  })

  it('none + ?flow= → a crafted flow return reaches NO app content and NO credential field (fail-closed, no bypass)', async () => {
    const { container, queryByTestId } = setup({ reject: true, flowReturnPresent: true })
    await flush()
    expect(queryByTestId('app-content')).toBeNull() // still gated: no session → no app, even with ?flow= present
    expect(container.querySelector('input')).toBeNull() // §1 boundary: never a credential field
  })

  it('none + ?flow= → the continue-to-login action re-initiates login MANUALLY (user-driven, one hop, no auto-loop)', async () => {
    const { findByTestId, getByTestId, redirectToLogin } = setup({ reject: true, flowReturnPresent: true })
    await findByTestId('auth.flowStranded')
    fireEvent.click(getByTestId('auth.retrySignin'))
    expect(redirectToLogin).toHaveBeenCalledTimes(1)
  })

  it('none WITHOUT a flow return → still auto-redirects (normal login path unaffected, regression guard)', async () => {
    const { findByTestId, redirectToLogin } = setup({ reject: true, flowReturnPresent: false })
    expect(await findByTestId('auth.redirect')).toBeTruthy()
    await waitFor(() => expect(redirectToLogin).toHaveBeenCalled())
  })

  it('logout → the Kratos logout flow (server-authoritative, no client cookie-clear) (tooth 3)', async () => {
    const { findByTestId, getByTestId, redirectToLogout } = setup({ authMe: me({ role: 'OPERATOR' }) })
    await findByTestId('app-content')
    fireEvent.click(getByTestId('auth.logout'))
    expect(redirectToLogout).toHaveBeenCalled()
  })

  it('break-glass (injected token) → renders the app operator=true WITHOUT calling whoami', async () => {
    const { findByTestId, fetchAuthMe } = setup({ breakGlass: true })
    expect(await findByTestId('app-content')).toBeTruthy()
    expect(fetchAuthMe).not.toHaveBeenCalled()
  })

  it('renders NO credential surface in any state (tooth 1) — never a password/email/login input', async () => {
    for (const s of [{ authMe: me({ role: 'OPERATOR' }) }, { authMe: me({ verified: false, role: null }) }, { reject: true }]) {
      const { container } = setup(s)
      await flush()
      expect(container.querySelector('input[type="password"]')).toBeNull()
      expect(container.querySelector('input[type="email"]')).toBeNull()
      expect(container.querySelector('input')).toBeNull() // no credential field at all
      cleanup()
    }
  })
})
