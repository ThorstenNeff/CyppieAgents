// CYP-833 — mutation-proven teeth for the fail-closed WS-close verdict. Both halves the coordinator asked for: an
// unknown close reds as terminal (fail-closed default) AND a known-reconnectable (1006) reds as reconnect (allowlist),
// so the test proves the allowlist path, not only the default.
import { describe, it, expect } from 'vitest'
import { isTerminalClose, isLegacyReconnectableClose, RECONNECTABLE_CLOSE_CODES } from './closeVerdict'

describe('CYP-833 — isTerminalClose (single-sourced, fail-closed)', () => {
  it('★ known-reconnectable codes → NOT terminal (Tester2 baseline allowlist proven, not just the default)', () => {
    // MUT: drop a code from RECONNECTABLE_CLOSE_CODES → it becomes terminal → this reds.
    for (const code of [1001, 1005, 1006, 1011, 1012, 1013]) expect(isTerminalClose(code)).toBe(false)
  })

  it('★ an UNRECOGNIZED numeric code → terminal (fail-closed — such a drop must NEVER silently reconnect)', () => {
    // MUT: flip the default to reconnectable (fail-OPEN, the CYP-289 hazard) → this reds.
    expect(isTerminalClose(4999)).toBe(true)
    expect(isTerminalClose(1002)).toBe(true) // protocol error: not on the transient allowlist → terminal
  })

  it('★ 1008 (policy violation / revoke) → terminal', () => {
    expect(isTerminalClose(1008)).toBe(true)
  })

  it('★ 1000 (normal close) → terminal (a clean server close = done, not a transient blip)', () => {
    expect(isTerminalClose(1000)).toBe(true)
  })

  it('★ undefined (transport gave no code) → NOT terminal (a no-code close is the abnormal-transient, ≈1005/1006 — Tester2 baseline)', () => {
    // Reverses the earlier undefined→terminal reading: no-code is a common transient blip, not a security signal
    // (security closes carry an explicit 1008). MUT: flip undefined→terminal → this reds.
    expect(isTerminalClose(undefined)).toBe(false)
  })

  it('the allowlist is the transient set (1006 in, 1008 out)', () => {
    expect(RECONNECTABLE_CLOSE_CODES.has(1006)).toBe(true)
    expect(RECONNECTABLE_CLOSE_CODES.has(1008)).toBe(false)
  })
})

describe('CYP-839 — isLegacyReconnectableClose (local-hub deny-{1008}): reconnect EVERYTHING except 1008 (no narrowing)', () => {
  it('★ 1008 is the ONLY terminal close (not reconnectable)', () => {
    expect(isLegacyReconnectableClose(1008)).toBe(false)
  })

  it('★ every non-1008 code is reconnectable — incl undefined AND unrecognized numerics (Tester2 baseline, NO narrowing)', () => {
    // MUT: narrow the legacy policy toward fail-closed (e.g. reuse isTerminalClose / an allowlist) → an unknown or
    // undefined would stop reconnecting → this reds. That narrowing IS the local-hub reconnect-regression to prevent.
    for (const code of [1000, 1001, 1005, 1006, 1011, 1012, 1013, 1002, 3000, 4999, undefined] as const) {
      expect(isLegacyReconnectableClose(code), `code ${code}`).toBe(true)
    }
  })

  it('★ the two policies are OPPOSITE on an unrecognized numeric BY DESIGN (local reconnects; A5 fail-closed terminates)', () => {
    // Trust-context contrast, not a bug: local-hub = trusted loopback → reconnect; A5 remote = security surface → terminal.
    for (const unknown of [4999, 1002, 3000]) {
      expect(isLegacyReconnectableClose(unknown), `local reconnects ${unknown}`).toBe(true)
      expect(isTerminalClose(unknown), `A5 terminates ${unknown}`).toBe(true)
    }
    // They agree where trust context does not diverge: 1008 is terminal for both.
    expect(isLegacyReconnectableClose(1008)).toBe(false)
    expect(isTerminalClose(1008)).toBe(true)
  })
})
