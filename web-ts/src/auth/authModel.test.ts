import { describe, it, expect } from 'vitest'
import { resolveAuthState, signedInAs } from './authModel'
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
