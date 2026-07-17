// CYP-644 — the pure handoff/context-lost banner policy. Teeth pin the fail-closed rule (banner ONLY on the two
// settled states, never on MEDIATED / the transient HANDING_* / absent) + the holder/since fallbacks.
import { describe, it, expect } from 'vitest'
import { handoffBanner, formatHandoffSince, handoffHolderLabel } from './handoffBannerModel'
import type { AgentTerminalControlEvent } from '../types/generated/contract'

const ev = (state: AgentTerminalControlEvent['state'], extra: Partial<AgentTerminalControlEvent> = {}): AgentTerminalControlEvent => ({
  agentId: 'backend',
  state,
  ...extra,
})

describe('handoffBanner', () => {
  it('INTERACTIVE → handoff banner carrying heldBy + since', () => {
    expect(handoffBanner(ev('INTERACTIVE', { heldBy: 'po', since: 1000 }))).toEqual({ kind: 'handoff', heldBy: 'po', since: 1000 })
  })

  it('CONTEXT_LOST → context-lost banner', () => {
    expect(handoffBanner(ev('CONTEXT_LOST'))).toEqual({ kind: 'contextLost' })
  })

  it('fail-closed: MEDIATED / HANDING_OVER / HANDING_BACK / undefined → NO banner', () => {
    // RED if a transient hand-off or the mediated default ever shows a banner (only settled INTERACTIVE/CONTEXT_LOST do).
    expect(handoffBanner(ev('MEDIATED'))).toBeNull()
    expect(handoffBanner(ev('HANDING_OVER'))).toBeNull()
    expect(handoffBanner(ev('HANDING_BACK'))).toBeNull()
    expect(handoffBanner(undefined)).toBeNull()
    expect(handoffBanner(null)).toBeNull()
  })

  it('INTERACTIVE with absent heldBy/since → nulls (the component renders ?/—)', () => {
    expect(handoffBanner(ev('INTERACTIVE'))).toEqual({ kind: 'handoff', heldBy: null, since: null })
  })
})

describe('formatHandoffSince', () => {
  it('null/undefined/non-finite → "—"', () => {
    expect(formatHandoffSince(null)).toBe('—')
    expect(formatHandoffSince(undefined)).toBe('—')
    expect(formatHandoffSince(Number.NaN)).toBe('—')
  })
  it('a real epoch-ms → a HH:MM clock string', () => {
    expect(formatHandoffSince(1_700_000_000_000)).toMatch(/^\d{2}:\d{2}$/)
  })
})

describe('handoffHolderLabel', () => {
  it('null/empty → "?"; a name → "@name"', () => {
    expect(handoffHolderLabel(null)).toBe('?')
    expect(handoffHolderLabel('')).toBe('?')
    expect(handoffHolderLabel('po')).toBe('@po')
  })
})
