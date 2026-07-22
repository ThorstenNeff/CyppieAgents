// CYP-801 (N3) — teeth for the pure trust-badge model. ★ = an honesty boundary from CYP-755 §6. The wire vocabulary
// is imported from connector/ (a-po's), never redefined.
import { describe, it, expect } from 'vitest'
import {
  hubTrustBadgeState,
  descriptorUpstreamError,
  hubTrustGlyphSpec,
  HUB_DESCRIPTOR_INVALID,
} from './hubTrustView'
import { HUB_TRUST_STATES, type HubTrustState } from '../connector/hubTrustModel'

describe('CYP-801 — hubTrustBadgeState (two-signal, fail-closed)', () => {
  it('★ absent/null trust → UNKNOWN (fail-closed default — never TRUSTED, never absence)', () => {
    // RED if the default ever becomes TRUSTED/optimistic (the never-green-by-default core, F4/PL-0089).
    expect(hubTrustBadgeState(null, 'VALID')).toBe('UNKNOWN')
    expect(hubTrustBadgeState(undefined, 'VALID')).toBe('UNKNOWN')
  })

  it('a real trust value passes through when the descriptor is VALID', () => {
    expect(hubTrustBadgeState('TRUSTED', 'VALID')).toBe('TRUSTED')
    expect(hubTrustBadgeState('PENDING', 'VALID')).toBe('PENDING')
    expect(hubTrustBadgeState('REJECTED', 'VALID')).toBe('REJECTED')
    expect(hubTrustBadgeState('STALE', 'VALID')).toBe('STALE')
  })

  it('★ MALFORMED descriptor → badge UNKNOWN (fail-closed), NEVER trusted/rejected — even with a trust value present', () => {
    // RED if a corrupt/hostile descriptor ever renders as the trust value (masquerading as a verdict) or as REJECTED
    // (an invented verdict). Malformed means "couldn't evaluate" → UNKNOWN, and the ⚠ (below) carries the diagnosis.
    expect(hubTrustBadgeState('TRUSTED', 'MALFORMED')).toBe('UNKNOWN')
    expect(hubTrustBadgeState('REJECTED', 'MALFORMED')).toBe('UNKNOWN')
    expect(hubTrustBadgeState(null, 'MALFORMED')).toBe('UNKNOWN')
  })
})

describe('CYP-801 — descriptorUpstreamError (the SEPARATE ⚠ signal)', () => {
  it('★ true ONLY on MALFORMED (mandatory upstream marker), false on VALID', () => {
    // RED if the ⚠ is dropped on malformed (the distinct upstream error vanishes) or fires on VALID (false alarm).
    expect(descriptorUpstreamError('MALFORMED')).toBe(true)
    expect(descriptorUpstreamError('VALID')).toBe(false)
  })

  it('★ the upstream marker lives in a DISTINCT namespace — NOT hub-trust-* (tier*≠trust* discipline)', () => {
    // RED if the malformed marker is ever emitted in the hub-trust-* namespace (conflating descriptor-error with trust).
    expect(HUB_DESCRIPTOR_INVALID.className).toBe('hub-descriptor-invalid')
    expect(HUB_DESCRIPTOR_INVALID.className.startsWith('hub-trust')).toBe(false)
    expect(HUB_DESCRIPTOR_INVALID.testId('h1')).toBe('hub.trust.h1.upstreamError')
  })
})

describe('CYP-801 — hubTrustGlyphSpec (5 distinct renders, colour never sole)', () => {
  it('★ all 5 states have DISTINCT glyph FORMS and DISTINCT labels (WCAG 1.4.1 — a monochrome user tells them apart)', () => {
    const specs = HUB_TRUST_STATES.map(hubTrustGlyphSpec)
    // RED if any two states share a glyph (e.g. unknown==trusted look — the Sweep-#5 class) or a label.
    expect(new Set(specs.map((s) => s.glyph)).size).toBe(5)
    expect(new Set(specs.map((s) => s.label)).size).toBe(5)
  })

  it('★ PENDING ≠ UNKNOWN ≠ TRUSTED — distinct glyph AND label (pending is not a catch-all for "not connected")', () => {
    const [u, p, t] = (['UNKNOWN', 'PENDING', 'TRUSTED'] as HubTrustState[]).map(hubTrustGlyphSpec)
    expect(u.glyph).not.toBe(p.glyph)
    expect(p.glyph).not.toBe(t.glyph)
    expect(u.glyph).not.toBe(t.glyph)
    expect(u.label).not.toBe(p.label)
    expect(p.label).not.toBe(t.label)
  })

  it('over-alarm-avoidance: only TRUSTED positive, only REJECTED warns, unknown/pending neutral', () => {
    // RED if unknown/pending ever carry an alarm tone, or trusted is not the sole positive.
    expect(hubTrustGlyphSpec('TRUSTED').tone).toBe('positive')
    expect(hubTrustGlyphSpec('REJECTED').tone).toBe('warn')
    expect(hubTrustGlyphSpec('UNKNOWN').tone).toBe('neutral')
    expect(hubTrustGlyphSpec('PENDING').tone).toBe('neutral')
    expect(hubTrustGlyphSpec('STALE').tone).toBe('action')
  })

  it('presentation token is a plain 1:1 lowercase of the wire state (Q1); testid is state-discriminating', () => {
    expect(hubTrustGlyphSpec('TRUSTED').presentation).toBe('trusted')
    expect(hubTrustGlyphSpec('STALE').testId('h1')).toBe('hub.trust.h1.stale')
  })
})
