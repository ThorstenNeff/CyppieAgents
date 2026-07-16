// CYP-641 — the pure activity-badge policy. These teeth pin the fail-closed rules (unknown ≠ activity), the
// attention-dominates-and-suppresses-busy rule, and the layout-stable token formatting. Reddening mutations noted
// per block.
import { describe, it, expect } from 'vitest'
import { deriveWindowActivity, formatContextTokens } from './activityBadge'

describe('deriveWindowActivity — fail-closed activity policy', () => {
  it('returns null when NOTHING is known (absent run-state, not busy, no token count)', () => {
    // RED if the derive ever returns a non-null "empty" badge → a phantom accessory on an idle, unseen agent.
    expect(deriveWindowActivity({ runState: undefined, busy: false, contextTokens: undefined })).toBeNull()
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: false, contextTokens: null })).toBeNull()
    expect(deriveWindowActivity({ runState: 'STOPPED', busy: false, contextTokens: undefined })).toBeNull()
  })

  it('busy=true (no error) lights the busy marker, no attention', () => {
    const a = deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: undefined })
    expect(a).toEqual({ attention: false, busy: true, contextTokens: null })
  })

  it('unknown ≠ busy: an absent busy flag never lights the marker', () => {
    // The caller passes `busyByAgent.get(id) ?? false`; here false must stay not-busy (RED if busy defaulted true).
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: false, contextTokens: 42 })).toEqual({
      attention: false,
      busy: false,
      contextTokens: 42,
    })
  })

  it('ERROR raises attention AND suppresses busy (no mixed "working + error" signal)', () => {
    // RED if busy survives under ERROR (attention must clear it).
    const a = deriveWindowActivity({ runState: 'ERROR', busy: true, contextTokens: 100 })
    expect(a).toEqual({ attention: true, busy: false, contextTokens: 100 })
  })

  it('context-token count shows only for a finite non-negative number; null/undefined/NaN/negative → no number', () => {
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: 0 })?.contextTokens).toBe(0)
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: 12345 })?.contextTokens).toBe(12345)
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: null })?.contextTokens).toBeNull()
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: Number.NaN })?.contextTokens).toBeNull()
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: true, contextTokens: -5 })?.contextTokens).toBeNull()
  })

  it('a token count ALONE (idle, no error) is enough to show a badge', () => {
    // The passive size hint is shown independent of busy — RED if a non-busy numeric-token agent gets null.
    expect(deriveWindowActivity({ runState: 'RUNNING', busy: false, contextTokens: 8000 })).toEqual({
      attention: false,
      busy: false,
      contextTokens: 8000,
    })
  })
})

describe('formatContextTokens — layout-stable compact', () => {
  it('< 1000 → the bare number', () => {
    expect(formatContextTokens(0)).toBe('0')
    expect(formatContextTokens(999)).toBe('999')
  })
  it('thousands → N.Nk, trailing .0 trimmed', () => {
    expect(formatContextTokens(1000)).toBe('1k')
    expect(formatContextTokens(1200)).toBe('1.2k')
    expect(formatContextTokens(15900)).toBe('15.9k')
  })
  it('millions → N.Nm', () => {
    expect(formatContextTokens(1_000_000)).toBe('1m')
    expect(formatContextTokens(2_500_000)).toBe('2.5m')
  })
})
