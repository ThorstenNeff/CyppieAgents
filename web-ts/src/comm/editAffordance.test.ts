// CYP-906 (Edit E3) — the operator-only, own-message affordance predicate.
import { describe, it, expect } from 'vitest'
import { canEditMessage } from './editAffordance'

describe('CYP-906 — canEditMessage (operator ∧ own)', () => {
  it('★ operator editing an OPERATOR-authored message → true', () => {
    expect(canEditMessage(true, 'operator', 'operator')).toBe(true)
  })
  it('★ operator editing an AGENT message → false (only operator-authored, MVP scope)', () => {
    // MUT: drop the from-check → an operator could edit any sender's message → reds.
    expect(canEditMessage(true, 'backend', 'operator')).toBe(false)
  })
  it('★ a non-operator → false even on an operator-authored message', () => {
    // MUT: drop the operator-check → a member could edit → reds.
    expect(canEditMessage(false, 'operator', 'operator')).toBe(false)
  })
})
