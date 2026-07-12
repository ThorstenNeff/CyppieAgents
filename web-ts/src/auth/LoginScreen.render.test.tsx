// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, waitFor, act } from '@testing-library/react'
import { LoginScreen } from './LoginScreen'
import { AUTH_TEXT } from './authModel'
import type { LoginResult } from './authModel'

const setup = (over: { login?: (e: string, p: string) => Promise<LoginResult> } = {}) => {
  const login = vi.fn(over.login ?? (async () => ({ kind: 'rejected' }) as LoginResult))
  const onVerified = vi.fn()
  const onUnverified = vi.fn()
  const utils = render(<LoginScreen login={login} onVerified={onVerified} onUnverified={onUnverified} />)
  return { ...utils, login, onVerified, onUnverified }
}

const type = (el: Element, value: string) => fireEvent.change(el, { target: { value } })

/** Any attribute (other than the live `value` property, which React doesn't reflect) carrying the secret = a leak. */
function attrsLeak(container: HTMLElement, secret: string): boolean {
  for (const el of Array.from(container.querySelectorAll('*'))) {
    for (const attr of Array.from(el.attributes)) {
      if (attr.name !== 'value' && attr.value.includes(secret)) return true
    }
  }
  return false
}

afterEach(cleanup)

describe('LoginScreen (CYP-515 (a) login-core)', () => {
  it('renders email(type=email) + password(type=password) + reveal + submit; submit disabled until both filled', () => {
    const { getByTestId } = setup()
    expect((getByTestId('auth.login.email') as HTMLInputElement).type).toBe('email')
    expect((getByTestId('auth.login.password') as HTMLInputElement).type).toBe('password')
    const submit = getByTestId('auth.login.submit') as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    type(getByTestId('auth.login.email'), 'a@b.co')
    expect(submit.disabled).toBe(true) // password still empty
    type(getByTestId('auth.login.password'), 'pw')
    expect(submit.disabled).toBe(false)
  })

  it('① password lives ONLY in the input value — never in a data-*/aria/title attribute (no exfil surface)', () => {
    const { getByTestId, container } = setup()
    type(getByTestId('auth.login.password'), 'topsecret42')
    expect((getByTestId('auth.login.password') as HTMLInputElement).value).toBe('topsecret42')
    expect(attrsLeak(container, 'topsecret42')).toBe(false)
  })

  it('① clear-after-submit: the password value is emptied once the submit resolves (never lingers)', async () => {
    const { getByTestId } = setup({ login: async () => ({ kind: 'rejected' }) })
    type(getByTestId('auth.login.email'), 'a@b.co')
    type(getByTestId('auth.login.password'), 'topsecret42')
    fireEvent.submit(getByTestId('auth.login.form'))
    await waitFor(() => expect((getByTestId('auth.login.password') as HTMLInputElement).value).toBe(''))
  })

  it('② a rejected login shows the ONE generic error (role=alert), as plain text (no server message, no HTML)', async () => {
    const { getByTestId } = setup({ login: async () => ({ kind: 'rejected' }) })
    type(getByTestId('auth.login.email'), 'a@b.co')
    type(getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(getByTestId('auth.login.form'))
    const err = await waitFor(() => getByTestId('auth.login.error'))
    expect(err.getAttribute('role')).toBe('alert')
    expect(err.textContent).toBe(AUTH_TEXT.loginErrorGeneric) // static generic string, never server text
  })

  it('③ a 429 shows the amber rate-limit (role=status, not alert) with the retry hint; submit is disabled', async () => {
    const { getByTestId, queryByTestId } = setup({ login: async () => ({ kind: 'rateLimited', retryAfter: '30' }) })
    type(getByTestId('auth.login.email'), 'a@b.co')
    type(getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(getByTestId('auth.login.form'))
    const rl = await waitFor(() => getByTestId('auth.login.rateLimited'))
    expect(rl.getAttribute('role')).toBe('status') // throttled ≠ error
    expect(rl.textContent).toContain('30')
    expect(queryByTestId('auth.login.error')).toBeNull()
    expect((getByTestId('auth.login.submit') as HTMLButtonElement).disabled).toBe(true) // disabled while throttled
  })

  it('verified → onVerified(operator); unverified → onUnverified', async () => {
    const v = setup({ login: async () => ({ kind: 'verified', operator: true }) })
    type(v.getByTestId('auth.login.email'), 'a@b.co')
    type(v.getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(v.getByTestId('auth.login.form'))
    await waitFor(() => expect(v.onVerified).toHaveBeenCalledWith(true))
    cleanup()
    const u = setup({ login: async () => ({ kind: 'unverified' }) })
    type(u.getByTestId('auth.login.email'), 'a@b.co')
    type(u.getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(u.getByTestId('auth.login.form'))
    await waitFor(() => expect(u.onUnverified).toHaveBeenCalled())
  })

  it('reveal toggles the password input type (password↔text) with a TEXT label, no emoji', () => {
    const { getByTestId } = setup()
    const pw = getByTestId('auth.login.password') as HTMLInputElement
    const reveal = getByTestId('auth.login.passwordReveal')
    expect(pw.type).toBe('password')
    expect(reveal.textContent).toBe(AUTH_TEXT.passwordShow)
    fireEvent.click(reveal)
    expect(pw.type).toBe('text')
    expect(reveal.textContent).toBe(AUTH_TEXT.passwordHide)
  })

  it('⑧ submitting = label swap + disabled fields, NO spinner (phase axis, not a progressbar)', async () => {
    let release: (r: LoginResult) => void = () => {}
    const pending = new Promise<LoginResult>((res) => (release = res))
    const { getByTestId, container } = setup({ login: () => pending })
    type(getByTestId('auth.login.email'), 'a@b.co')
    type(getByTestId('auth.login.password'), 'pw')
    fireEvent.submit(getByTestId('auth.login.form'))
    await waitFor(() => expect(getByTestId('auth.login.submit').textContent).toBe(AUTH_TEXT.submitting))
    expect((getByTestId('auth.login.password') as HTMLInputElement).disabled).toBe(true)
    expect(container.querySelector('[role="progressbar"]')).toBeNull() // no spinner
    await act(async () => {
      release({ kind: 'rejected' })
      await pending
    })
  })
})
