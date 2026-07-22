import { describe, it, expect } from 'vitest'
import {
  HUB_TRUST_STATES,
  HUB_TRUST_STATE_DEFAULT,
  type HubTrustState,
  type HubDescriptorValidity,
} from '../connector/hubTrustModel'
import {
  hubTrustBadgeState,
  descriptorUpstreamError,
  hubTrustGlyphSpec,
  HUB_DESCRIPTOR_INVALID,
  type HubTrustBadgeView,
} from './hubTrustView'

// CYP-801 — the CYP-755 §6 honesty teeth for the Model-2 per-hub TRUST-STATE render, against Dev5's real render model
// (comm/hubTrustView.ts) + the real wire vocabulary (connector/hubTrustModel.ts, parity-bound to :core). Combined unit
// = Dev5's render + his component teeth + these N3 teeth.
//
// F2 (Assist2 pre-arm), all four axes, each mutation-proven by the discrimination arm:
//  • Q1 (uppercase-literal trap): the derivation/render teeth exercise the MAP — hubTrustBadgeState(absent→UNKNOWN) and
//    the `presentation` lowercase token — NOT a typed literal handed to a renderer. The mutation lives at the seam.
//  • Q2 malformed (sharpest, 3 signals): §6.9a malformed→UNKNOWN (never a fabricated REJECTED; MALFORMED ∉ HubTrustState
//    by type) · §6.9b the ⚠ upstream signal is MANDATORY on malformed, absent on valid · §6.9c the ⚠ lives in a DISTINCT
//    namespace (hub.trust.{id}.upstreamError / hub-descriptor-invalid), never a hub-trust-* state token.
//  • Q3/F4 fail-closed: absent trust → UNKNOWN, never optimistic TRUSTED.
//  • Q4: five pairwise-distinct glyphs AND labels (colour-never-sole) + a non-vacuity guard (all 5 real states).
//
// SCOPE: §6 teeth 1,2,3,4,7,9(a,b,c) have a locus in the badge model and are here. §6.5 (role-doesn't-travel), §6.6
// (no-optimistic-switch), §6.8 (inactive-not-stale-trusted) are SWITCH-FLOW / SWITCHER surface concerns (spec §2/§3),
// no locus in the badge model — they land with the hub-switcher surface (flagged, not faked).

const lower = (s: HubTrustState) => s.toLowerCase()

interface View {
  hubTrustBadgeState: (t: HubTrustState | null | undefined, v: HubDescriptorValidity) => HubTrustState
  descriptorUpstreamError: (v: HubDescriptorValidity) => boolean
  hubTrustGlyphSpec: (s: HubTrustState) => HubTrustBadgeView
  HUB_DESCRIPTOR_INVALID: { className: string; testId: (hubId: string) => string; label: string }
}
const real: View = { hubTrustBadgeState, descriptorUpstreamError, hubTrustGlyphSpec, HUB_DESCRIPTOR_INVALID }

// ── assertion helpers (reused by BOTH the real-model acceptance AND the synthetic discrimination) ──────────────────
/** §6.1/§6.3/Q3 fail-closed: absent trust (+valid) → UNKNOWN, never optimistic TRUSTED — via the MAP, not a literal. */
const failClosedDefault = (v: View): boolean =>
  v.hubTrustBadgeState(null, 'VALID') === 'UNKNOWN' && v.hubTrustBadgeState(undefined, 'VALID') === 'UNKNOWN'
/** §6.9a malformed → UNKNOWN (fail-closed), never a fabricated verdict (never REJECTED). */
const malformedFoldsToUnknown = (v: View): boolean =>
  v.hubTrustBadgeState('TRUSTED', 'MALFORMED') === 'UNKNOWN' && v.hubTrustBadgeState('REJECTED', 'MALFORMED') !== 'REJECTED'
/** §6.9b the ⚠ upstream signal is ALWAYS present on malformed, absent on valid (PL: mandatory). */
const upstreamAlwaysOnMalformed = (v: View): boolean =>
  v.descriptorUpstreamError('MALFORMED') === true && v.descriptorUpstreamError('VALID') === false
/** §6.2/§6.7/Q1/Q4: presentation is the lowercase map of the state, testId anchors on it, and the five glyphs AND
 *  labels are pairwise distinct (distinguishable without colour). */
const rendersDistinctPerState = (v: View): boolean => {
  const specs = HUB_TRUST_STATES.map((s) => ({ s, spec: v.hubTrustGlyphSpec(s) }))
  const mapOk = specs.every(({ s, spec }) => spec.presentation === lower(s) && spec.testId('h') === `hub.trust.h.${lower(s)}` && spec.glyph.length > 0 && spec.label.length > 0)
  const glyphs = new Set(specs.map(({ spec }) => spec.glyph))
  const labels = new Set(specs.map(({ spec }) => spec.label))
  return mapOk && glyphs.size === HUB_TRUST_STATES.length && labels.size === HUB_TRUST_STATES.length
}
/** §6.9c the ⚠ marker lives in a DISTINCT namespace — its testId/class is NEVER a hub-trust-* state token. */
const upstreamNamespaceDistinct = (v: View): boolean => {
  const upstream = v.HUB_DESCRIPTOR_INVALID.testId('h')
  const stateTestIds = new Set(HUB_TRUST_STATES.map((s) => v.hubTrustGlyphSpec(s).testId('h')))
  return !stateTestIds.has(upstream) && !v.HUB_DESCRIPTOR_INVALID.className.startsWith('hub-trust-') && upstream.endsWith('.upstreamError')
}

describe('CYP-801 / §6 trust-state honesty teeth (vs Dev5 comm/hubTrustView)', () => {
  it('non-vacuity: the wire vocabulary is exactly the 5 states + fail-closed default UNKNOWN', () => {
    expect([...HUB_TRUST_STATES].sort()).toEqual(['PENDING', 'REJECTED', 'STALE', 'TRUSTED', 'UNKNOWN'])
    expect(HUB_TRUST_STATE_DEFAULT).toBe('UNKNOWN')
  })

  it('★ §6.1/§6.3 (F4/Q3) fail-closed: absent trust → UNKNOWN via the map (not a literal)', () => expect(failClosedDefault(real)).toBe(true))
  it('★ §6.9a (Q2) malformed → UNKNOWN, never a fabricated REJECTED', () => expect(malformedFoldsToUnknown(real)).toBe(true))
  it('★ §6.9b (Q2) the ⚠ upstream signal is MANDATORY on malformed, absent on valid', () => expect(upstreamAlwaysOnMalformed(real)).toBe(true))
  it('★ §6.9c (Q2) the ⚠ marker is in a DISTINCT namespace, never a hub-trust-* state token', () => expect(upstreamNamespaceDistinct(real)).toBe(true))
  it('★ §6.2/§6.7 (Q1/Q4) presentation=lowercase map, five distinct glyphs+labels, colour-never-sole', () => expect(rendersDistinctPerState(real)).toBe(true))

  // ── discrimination (GREEN now): each helper greens on a correct model and REDS on its §6 mutation ────────────────
  const spec = (s: HubTrustState): HubTrustBadgeView => ({ state: s, glyph: { UNKNOWN: '◯', PENDING: '◔', TRUSTED: '●', REJECTED: '⊘', STALE: '◑' }[s], label: `L-${lower(s)}`, a11yLabel: `A-${s}`, tone: 'neutral', presentation: lower(s), testId: (h) => `hub.trust.${h}.${lower(s)}` })
  const good: View = {
    hubTrustBadgeState: (t, v) => (v === 'MALFORMED' || t == null ? 'UNKNOWN' : t),
    descriptorUpstreamError: (v) => v === 'MALFORMED',
    hubTrustGlyphSpec: spec,
    HUB_DESCRIPTOR_INVALID: { className: 'hub-descriptor-invalid', testId: (h) => `hub.trust.${h}.upstreamError`, label: 'x' },
  }
  it('discrimination: all helpers GREEN on a correct model', () => {
    expect(failClosedDefault(good) && malformedFoldsToUnknown(good) && upstreamAlwaysOnMalformed(good) && upstreamNamespaceDistinct(good) && rendersDistinctPerState(good)).toBe(true)
  })
  it('★ discrimination: each helper REDS on its §6 mutation', () => {
    expect(failClosedDefault({ ...good, hubTrustBadgeState: (t, v) => (v === 'MALFORMED' ? 'UNKNOWN' : (t ?? 'TRUSTED')) })).toBe(false) // §6.1 optimistic default
    expect(malformedFoldsToUnknown({ ...good, hubTrustBadgeState: (t, v) => (v === 'MALFORMED' ? 'REJECTED' : (t ?? 'UNKNOWN')) })).toBe(false) // §6.9a invented verdict
    expect(upstreamAlwaysOnMalformed({ ...good, descriptorUpstreamError: () => false })).toBe(false) // §6.9b ⚠ dropped
    expect(upstreamNamespaceDistinct({ ...good, HUB_DESCRIPTOR_INVALID: { className: 'hub-trust-malformed', testId: (h) => `hub.trust.${h}.unknown`, label: 'x' } })).toBe(false) // §6.9c conflated into trust namespace
    expect(rendersDistinctPerState({ ...good, hubTrustGlyphSpec: (s) => ({ ...spec(s), presentation: 'UNKNOWN' }) })).toBe(false) // §6.2/Q1 broken lowercase map
    expect(rendersDistinctPerState({ ...good, hubTrustGlyphSpec: (s) => ({ ...spec(s), glyph: s === 'PENDING' ? '◯' : spec(s).glyph }) })).toBe(false) // §6.2/Q4 glyph collapse
  })
})
