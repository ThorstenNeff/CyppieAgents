// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent } from '@testing-library/react'
import { AuthGate } from './AuthGate'
import type { AuthMe } from '../types/generated/contract'
import type { LoginResult } from './authModel'

const me = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })

const setup = (over: {
  authMe?: AuthMe
  reject?: boolean
  breakGlass?: boolean
  login?: (e: string, p: string) => Promise<LoginResult>
} = {}) => {
  const login = vi.fn(over.login ?? (async () => ({ kind: 'rejected' }) as LoginResult))
  const redirectToLogout = vi.fn()
  const fetchAuthMe = vi.fn(() =>
    over.reject ? Promise.reject(new Error('down')) : Promise.resolve(over.authMe ?? me({ role: 'OPERATOR' })),
  )
  let installed: (() => void) | null = null
  const onInstallUnauthorized = vi.fn((h: (() => void) | null) => {
    installed = h
  })
  const childOperator = vi.fn()
  const utils = render(
    <AuthGate
      fetchAuthMe={fetchAuthMe as () => Promise<AuthMe>}
      login={login}
      redirectToLogout={redirectToLogout}
      breakGlass={over.breakGlass}
      onInstallUnauthorized={onInstallUnauthorized}
    >
      {(operator) => {
        childOperator(operator)
        return <div data-testid="app-content">app operator={String(operator)}</div>
      }}
    </AuthGate>,
  )
  return { ...utils, login, redirectToLogout, fetchAuthMe, childOperator, onInstallUnauthorized, fireInstalled: () => installed?.() }
}

const type = (el: Element, value: string) => fireEvent.change(el, { target: { value } })

afterEach(cleanup)

describe('AuthGate (CYP-515 (a) — in-app login)', () => {
  it('resolve-then-render: renders NOTHING (no app, no login flash) before whoami resolves', () => {
    const { getByTestId, queryByTestId } = setup()
    expect(getByTestId('auth.loading')).toBeTruthy()
    expect(queryByTestId('app-content')).toBeNull()
    expect(queryByTestId('auth.login.form')).toBeNull() // no login flash either
  })

  it('active + OPERATOR → the app + a content-free session indicator', async () => {
    const { findByTestId, getByTestId } = setup({ authMe: me({ role: 'OPERATOR' }) })
    expect(await findByTestId('app-content')).toBeTruthy()
    expect(getByTestId('app-content').textContent).toContain('operator=true')
    expect(getByTestId('auth.role').textContent).toBe('Angemeldet als Operator')
  })

  it('active + MEMBER → app operator=false (whoami drives it, fail-closed)', async () => {
    const { findByTestId, getByTestId } = setup({ authMe: me({ role: 'MEMBER' }) })
    await findByTestId('app-content')
    expect(getByTestId('app-content').textContent).toContain('operator=false')
  })

  it('unverified → verify-gate, NO app access, NO login form', async () => {
    const { findByTestId, queryByTestId } = setup({ authMe: me({ verified: false, role: null }) })
    expect(await findByTestId('auth.verifyGate')).toBeTruthy()
    expect(queryByTestId('app-content')).toBeNull()
    expect(queryByTestId('auth.login.form')).toBeNull()
  })

  it('none (whoami error, fail-closed) → the in-app LoginScreen (NOT a redirect), no app content', async () => {
    const { findByTestId, queryByTestId } = setup({ reject: true })
    expect(await findByTestId('auth.login.form')).toBeTruthy() // login rendered in-app — no window redirect anywhere
    expect(queryByTestId('app-content')).toBeNull()
  })

  it('a successful in-app login mounts the app at the returned tier', async () => {
    const { findByTestId, getByTestId } = setup({ reject: true, login: async () => ({ kind: 'verified', operator: true }) })
    await findByTestId('auth.login.form')
    type(getByTestId('auth.login.email'), 'a@b.co')
    type(getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(getByTestId('auth.login.form'))
    expect(await findByTestId('app-content')).toBeTruthy()
    expect(getByTestId('app-content').textContent).toContain('operator=true')
  })

  it('★ a protected /api 401 flips the gate back to the in-app LoginScreen (in-app re-auth, no redirect loop)', async () => {
    const { findByTestId, queryByTestId, fireInstalled } = setup({ authMe: me({ role: 'OPERATOR' }) })
    await findByTestId('app-content') // active
    act(() => fireInstalled()) // simulate the global 401 handler firing
    expect(await findByTestId('auth.login.form')).toBeTruthy()
    expect(queryByTestId('app-content')).toBeNull() // app is gone; user re-auths in-app
  })

  it('logout → the server-authoritative logout flow', async () => {
    const { findByTestId, getByTestId, redirectToLogout } = setup({ authMe: me({ role: 'OPERATOR' }) })
    await findByTestId('app-content')
    fireEvent.click(getByTestId('auth.logout'))
    expect(redirectToLogout).toHaveBeenCalled()
  })

  it('break-glass (injected token) → the app operator=true WITHOUT calling whoami', async () => {
    const { findByTestId, fetchAuthMe } = setup({ breakGlass: true })
    expect(await findByTestId('app-content')).toBeTruthy()
    expect(fetchAuthMe).not.toHaveBeenCalled()
  })

  it('the credential surface appears ONLY in the none state (never with app content or the verify-gate)', async () => {
    // active: no credential field
    const a = setup({ authMe: me({ role: 'OPERATOR' }) })
    await a.findByTestId('app-content')
    expect(a.container.querySelector('input')).toBeNull()
    cleanup()
    // unverified: no credential field
    const u = setup({ authMe: me({ verified: false, role: null }) })
    await u.findByTestId('auth.verifyGate')
    expect(u.container.querySelector('input')).toBeNull()
  })
})
