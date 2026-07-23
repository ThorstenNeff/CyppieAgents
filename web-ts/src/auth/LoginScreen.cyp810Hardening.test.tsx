// @vitest-environment jsdom
// CYP-810 LoginScreen (Dev5 flagged "error==unavailable G2/G3") — ★ MEASURE-THE-OBJECT REFRAME: `.auth-login-error` and
// `.auth-login-unavailable` INTENTIONALLY share the error colour token (index.css comment: "Same error tone … the
// DISTINCTION is carried by the TEXT (system vs input), never by colour alone"). So the colour-sharing is BY DESIGN, not
// a G2 gap. The real honesty gap is G3 IDENTITY: a SYSTEM fault (`unavailable`) must be a DISTINCT, honestly-worded state
// from a credential rejection (`error`) — since colour can't distinguish them, the TEXT must. This pins that distinction.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, waitFor } from '@testing-library/react'
import { LoginScreen } from './LoginScreen'
import type { LoginResult } from './authModel'

afterEach(cleanup)

async function submitWith(result: LoginResult) {
  const utils = render(<LoginScreen login={vi.fn(async () => result)} onVerified={vi.fn()} onUnverified={vi.fn()} />)
  fireEvent.change(utils.getByTestId('auth.login.email'), { target: { value: 'a@b.co' } })
  fireEvent.change(utils.getByTestId('auth.login.password'), { target: { value: 'pw' } })
  fireEvent.submit(utils.getByTestId('auth.login.form'))
  return utils
}

describe('CYP-810 (G3) — LoginScreen: a SYSTEM fault (unavailable) is a distinct, honest state from a credential error', () => {
  it('★ a rejected login → the credential-error state ONLY (not the system-fault state)', async () => {
    const u = await submitWith({ kind: 'rejected' })
    await waitFor(() => expect(u.queryByTestId('auth.login.error')).not.toBeNull())
    expect(u.queryByTestId('auth.login.unavailable')).toBeNull() // a wrong password is not a system fault
  })

  it('★ an unavailable result → the SYSTEM-fault state ONLY (not the credential-error state)', async () => {
    const u = await submitWith({ kind: 'unavailable' })
    await waitFor(() => expect(u.queryByTestId('auth.login.unavailable')).not.toBeNull())
    expect(u.queryByTestId('auth.login.error')).toBeNull() // a system outage is not "wrong credentials"
  })

  it('★ the two states carry DISTINCT text (colour is shared by design → the wording MUST distinguish them)', async () => {
    const err = await submitWith({ kind: 'rejected' })
    const errText = (await waitFor(() => err.getByTestId('auth.login.error'))).textContent?.trim()
    cleanup()
    const unavail = await submitWith({ kind: 'unavailable' })
    const unavailText = (await waitFor(() => unavail.getByTestId('auth.login.unavailable'))).textContent?.trim()
    expect(errText).toBeTruthy()
    expect(unavailText).toBeTruthy()
    expect(errText).not.toBe(unavailText) // the honesty the shared colour relies on: distinct wording
  })
})
