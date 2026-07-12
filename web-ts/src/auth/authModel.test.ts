import { describe, it, expect } from 'vitest'
import { resolveAuthState, signedInAs, loginOutcomeFromAuth, rateLimitedText, passwordRevealDesc, AUTH_TEXT } from './authModel'
import type { AuthMe } from '../types/generated/contract'

const me = (over: Partial<AuthMe>): AuthMe => ({ authenticated: true, verified: true, ...over })

describe('resolveAuthState (CYP-470 — whoami drives the gate, fail-closed)', () => {
  it('null (whoami error) → none (fail-closed → login redirect)', () => {
    expect(resolveAuthState(null)).toEqual({ kind: 'none' })
  })
  it('not authenticated → none', () => {
    expect(resolveAuthState(me({ authenticated: false }))).toEqual({ kind: 'none' })
  })
  it('authenticated but NOT verified → unverified (no access, tooth 5)', () => {
    expect(resolveAuthState(me({ verified: false, role: null }))).toEqual({ kind: 'unverified' })
  })
  it('active + role OPERATOR → operator (tooth 2)', () => {
    expect(resolveAuthState(me({ role: 'OPERATOR' }))).toEqual({ kind: 'active', operator: true })
  })
  it('active + role MEMBER → member (fail-closed: anything but OPERATOR)', () => {
    expect(resolveAuthState(me({ role: 'MEMBER' }))).toEqual({ kind: 'active', operator: false })
    expect(resolveAuthState(me({ role: null }))).toEqual({ kind: 'active', operator: false })
  })
})

describe('signedInAs (content-free — role only, text + label)', () => {
  it('names the role, no id/email', () => {
    expect(signedInAs(true)).toBe('Angemeldet als Operator')
    expect(signedInAs(false)).toBe('Angemeldet als Member')
  })
})

describe('loginOutcomeFromAuth (CYP-515 (a) — server whoami drives the post-login outcome)', () => {
  it('verified operator/member', () => {
    expect(loginOutcomeFromAuth(me({ role: 'OPERATOR' }))).toEqual({ kind: 'verified', operator: true })
    expect(loginOutcomeFromAuth(me({ role: 'MEMBER' }))).toEqual({ kind: 'verified', operator: false })
  })
  it('authenticated-but-unverified → unverified (the verify-gate)', () => {
    expect(loginOutcomeFromAuth(me({ verified: false, role: null }))).toEqual({ kind: 'unverified' })
  })
  it('none post-2xx (unexpected) / null → rejected, never optimistic (fail-closed)', () => {
    expect(loginOutcomeFromAuth(me({ authenticated: false }))).toEqual({ kind: 'rejected' })
    expect(loginOutcomeFromAuth(null)).toEqual({ kind: 'rejected' })
  })
})

describe('rateLimitedText (honest 429, never a fake success)', () => {
  it('with a retry hint → the wait variant carrying it', () => {
    expect(rateLimitedText('30')).toContain('30')
    expect(rateLimitedText('30')).not.toBe(AUTH_TEXT.rateLimited)
  })
  it('no/blank hint → the plain rate-limit string', () => {
    expect(rateLimitedText(null)).toBe(AUTH_TEXT.rateLimited)
    expect(rateLimitedText('')).toBe(AUTH_TEXT.rateLimited)
  })
})

describe('passwordRevealDesc (text a11y label, mirrors show/hide)', () => {
  it('reveals → hide label; hidden → show label', () => {
    expect(passwordRevealDesc(true)).toBe(AUTH_TEXT.passwordHide)
    expect(passwordRevealDesc(false)).toBe(AUTH_TEXT.passwordShow)
  })
})
