// CYP-833 — mutation-proven teeth for the fail-closed WS-close verdict. Both halves the coordinator asked for: an
// unknown close reds as terminal (fail-closed default) AND a known-reconnectable (1006) reds as reconnect (allowlist),
// so the test proves the allowlist path, not only the default.
import { describe, it, expect } from 'vitest'
import { isTerminalClose, RECONNECTABLE_CLOSE_CODES } from './closeVerdict'

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
