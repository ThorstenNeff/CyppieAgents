// CYP-805 (S1c) — teeth for the pure issuer-trust decision (axis c). F2-safe: the states under test come from the
// frozen HUB_ISSUER_TRUST_VALUES, not hand-typed literals, so a tooth witnesses the real value→decision path.
import { describe, it, expect } from 'vitest'
import { issuerConnectDecision, HUB_ISSUER_TRUST_VALUES } from './issuerTrustModel'
import { HUB_TRUST_STATES } from './hubTrustModel'

describe('CYP-805 — issuerConnectDecision (only NOT_TRUSTED blocks)', () => {
  it('★ NOT_TRUSTED → the terminal block (the UX win — the client mirrors the server refusal)', () => {
    // RED if NOT_TRUSTED ever proceeds (the block is the whole point).
    expect(issuerConnectDecision('NOT_TRUSTED')).toBe('block')
  })

  it('★ absent (unknown) → proceed, never block (no signal ⇒ the SERVER is the gate, not this render)', () => {
    // RED if an absent/unknown issuer signal ever fabricates a block (false hard-stop on old servers with no field).
    expect(issuerConnectDecision(null)).toBe('proceed')
    expect(issuerConnectDecision(undefined)).toBe('proceed')
  })

  it('TRUSTED and REMOTE_NOT_CONFIGURED → proceed (nothing to block)', () => {
    expect(issuerConnectDecision('TRUSTED')).toBe('proceed')
    expect(issuerConnectDecision('REMOTE_NOT_CONFIGURED')).toBe('proceed')
  })

  it('★ across ALL frozen values, EXACTLY one (NOT_TRUSTED) blocks — no other value collapses into a block', () => {
    // RED if a second value ever blocks (over-blocking) or NOT_TRUSTED stops blocking (under-blocking).
    const blocking = HUB_ISSUER_TRUST_VALUES.filter((v) => issuerConnectDecision(v) === 'block')
    expect(blocking).toEqual(['NOT_TRUSTED'])
  })

  it('★ axis discipline: the issuer axis (c) is a DISTINCT value set from the TOFU axis (a) — never folded', () => {
    // The two share the name TRUSTED but are different axes; their value SETS differ (issuer has NOT_TRUSTED/
    // REMOTE_NOT_CONFIGURED; TOFU has PENDING/STALE/UNKNOWN/REJECTED). RED if someone re-points one at the other's enum.
    const issuer = new Set<string>(HUB_ISSUER_TRUST_VALUES)
    const tofu = new Set<string>(HUB_TRUST_STATES)
    expect(issuer).not.toEqual(tofu)
    expect(HUB_ISSUER_TRUST_VALUES).toContain('REMOTE_NOT_CONFIGURED') // issuer-only
    expect(HUB_TRUST_STATES).not.toContain('REMOTE_NOT_CONFIGURED' as never)
  })
})
