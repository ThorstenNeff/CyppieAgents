// CYP-732 — teeth for the mark-read focus gate. The rule is pure precisely so it can be proved here; the browser
// wiring is exercised at App level (App.render.test.tsx).
import { describe, it, expect } from 'vitest'
import { canAdvanceReadCursor, type FocusState } from './markReadGate'

const focus = (over: Partial<FocusState> = {}): FocusState => ({
  documentVisible: true,
  windowFocused: true,
  commWindowFocused: true,
  ...over,
})

describe('CYP-732 — the read cursor advances only when someone can actually be reading', () => {
  it('all three signals present → may advance (non-vacuous control)', () => {
    expect(canAdvanceReadCursor(focus())).toBe(true)
  })

  it('★ a hidden tab never advances — this is the case that marked messages read while you were away', () => {
    expect(canAdvanceReadCursor(focus({ documentVisible: false }))).toBe(false)
  })

  it('★ a visible tab behind another application never advances — visible is not "being read"', () => {
    expect(canAdvanceReadCursor(focus({ windowFocused: false }))).toBe(false)
  })

  it('★ a focused browser window showing a DIFFERENT app window never advances', () => {
    // the conversation can be open, on screen, and still not the thing you are looking at.
    expect(canAdvanceReadCursor(focus({ commWindowFocused: false }))).toBe(false)
  })

  it('★ each signal is independently load-bearing — none is implied by the others', () => {
    // guards against a future "simplification" that drops one as redundant. Each single-false case must block.
    const keys: (keyof FocusState)[] = ['documentVisible', 'windowFocused', 'commWindowFocused']
    for (const k of keys) expect(canAdvanceReadCursor(focus({ [k]: false }))).toBe(false)
    expect(canAdvanceReadCursor(focus())).toBe(true) // …and all-true still passes, so it is not just always-false
  })

  it('★ fails CLOSED: nothing observable ⇒ no advance', () => {
    // the asymmetry that decides the direction — leaving something unread self-corrects when you look at it;
    // wrongly marking read destroys a durable, cross-device signal that cannot be recovered.
    expect(canAdvanceReadCursor({ documentVisible: false, windowFocused: false, commWindowFocused: false })).toBe(false)
  })
})
