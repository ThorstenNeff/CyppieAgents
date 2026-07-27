// CYP-801 (N3) — the pure presentation core for the per-hub trust badge, from uiux2 spec CYP-755 (0ac8215e) §1.
// Standalone + prop-driven; HubTrustBadge draws these. The honesty rules (fail-closed default, distinct states,
// malformed-is-a-separate-signal) live HERE where they can be proven — like unreadModel/capacityModel/remoteSecurityTierModel.
//
// ★ AXIS + NAMING (CYP-798 §5, uiux2 §1): this is axis a (TOFU / hub-key trust). It IMPORTS the wire vocabulary from
// connector/hubTrustModel.ts (a-po's, parity-bound to :core HubTrust.kt) — it does NOT redefine it. Kept SEPARATE from
// the issuer axis (never folded) and from remote.security.tier* (the running-connection tier, a different concern).
//
// ★ malformed is NOT a trust state and NOT a badge arm (uiux2 correction / tier*≠trust* discipline, CYP-798 §4b): a
// MALFORMED descriptor yields TWO independent outputs — the badge falls to UNKNOWN (fail-closed: trust could not be
// evaluated, never rejected/trusted) AND a SEPARATE mandatory ⚠ upstream marker in its OWN token namespace
// (hub-descriptor-invalid), never hub-trust-*. Keeping them apart is the honesty core (a corrupt/hostile descriptor
// must stay diagnosable, not vanish into a benign unknown or masquerade as a trust verdict).
import type { HubTrustState, HubDescriptorValidity } from '../connector/hubTrustModel'
import { HUB_TRUST_STATE_DEFAULT } from '../connector/hubTrustModel'

/** Presentation tone (CYP-755 §1). unknown/pending/TRUSTED are NEUTRAL (no alarm, no affirming green — never-green DS,
 *  CYP-803: trust is revocable, so TRUSTED gets no positive accent, only a fuller CSS emphasis); only rejected warns;
 *  stale is action. Colour is secondary — the glyph FORM + label WORD carry the meaning (colour-never-sole).
 *  `'positive'` stays in the vocabulary (unused by trust) so the union isn't re-narrowed; no trust state maps to it. */
export type HubTrustTone = 'neutral' | 'positive' | 'warn' | 'action'

export interface HubTrustBadgeView {
  state: HubTrustState
  /** Plain-text glyph, distinct FORM per state so colour is never the sole carrier (WCAG 1.4.1), CYP-755 §1:
   *  ◯ unknown (empty ring) / ◔ pending (quarter) / ● trusted (full) / ⊘ rejected (barred) / ◑ stale (half). */
  glyph: string
  /** Stable short label (the meaning WORD — the aria-label, never just glyph/colour). */
  label: string
  /** Full a11y description. */
  a11yLabel: string
  tone: HubTrustTone
  /** lowercase presentation token (Q1 — plain 1:1 toLowerCase, no special case): class `hub-trust-{presentation}`. */
  presentation: string
  /** discriminating present-iff-state anchor (CYP-755 §1 a11y): hub.trust.{hubId}.{presentation}. */
  testId: (hubId: string) => string
}

// ── the two-signal derivation (§1) ───────────────────────────────────────────────────────────────────────────────
/**
 * The trust BADGE state. Two collapses, both fail-closed: a MALFORMED descriptor → UNKNOWN (trust couldn't be
 * evaluated — NEVER trusted/rejected), and an absent/null trust signal → the UNKNOWN default (never TRUSTED, never
 * absence). The badge is ALWAYS one of the 5 states — malformed is handled here (→UNKNOWN) + separately below, never
 * as a 6th value.
 */
export function hubTrustBadgeState(
  trust: HubTrustState | null | undefined,
  validity: HubDescriptorValidity,
): HubTrustState {
  if (validity === 'MALFORMED') return 'UNKNOWN' // couldn't evaluate → unknown; NEVER trusted/rejected (CYP-798 §4b)
  return trust ?? HUB_TRUST_STATE_DEFAULT // fail-closed: no signal ⇒ UNKNOWN, not absence, not TRUSTED
}

/** The SEPARATE upstream-error signal — its OWN namespace, present (⚠) iff the descriptor is malformed. ALWAYS
 *  surfaced on malformed (mandatory, CYP-798 §4b), so a corrupt/hostile descriptor is never silently lost. */
export const descriptorUpstreamError = (validity: HubDescriptorValidity): boolean => validity === 'MALFORMED'

/** The distinct token namespace for the ⚠ upstream marker — NOT hub-trust-* (tier*≠trust* discipline, uiux2 §4). */
export const HUB_DESCRIPTOR_INVALID = {
  className: 'hub-descriptor-invalid',
  testId: (hubId: string): string => `hub.trust.${hubId}.upstreamError`,
  label: 'Ungültiger Hub-Descriptor — Status nicht interpretierbar',
} as const

// ── the 5-state presentation seam (colour-never-sole: distinct GLYPH FORM + WORD per state) ──────────────────────
const GLYPH: Record<HubTrustState, string> = {
  UNKNOWN: '◯',
  PENDING: '◔',
  TRUSTED: '●',
  REJECTED: '⊘',
  STALE: '◑',
}
const TONE: Record<HubTrustState, HubTrustTone> = {
  UNKNOWN: 'neutral',
  PENDING: 'neutral',
  TRUSTED: 'neutral', // never-green DS (CYP-803): trust is issuer-vouched + REVOCABLE → no affirming accent. Its
  // full-emphasis distinction (evaluated, not muted) lives in CSS (on-surface vs unknown/pending's on-surface-variant).
  REJECTED: 'warn',
  STALE: 'action',
}
/** DE copy (web-ts is DE-inline, no i18n) — the meaning WORD per state (CYP-755 §1). */
export const HUB_TRUST_TEXT = {
  label: {
    UNKNOWN: 'Vertrauen nicht geprüft',
    PENDING: 'wird geprüft…',
    TRUSTED: 'vertraut',
    REJECTED: 'abgelehnt',
    STALE: 'nicht mehr aktuell — erneut bestätigen',
  } satisfies Record<HubTrustState, string>,
  a11y: {
    UNKNOWN: 'Hub-Vertrauen noch nicht geprüft.',
    PENDING: 'Hub-Vertrauen wird geprüft.',
    TRUSTED: 'Diesem Hub wird vertraut (Aussteller-vouched, widerrufbar).',
    REJECTED: 'Der Hub hat den Zugang abgelehnt.',
    STALE: 'Hub-Vertrauen nicht mehr aktuell — erneut bestätigen.',
  } satisfies Record<HubTrustState, string>,
} as const

/** Derive the honest badge presentation for a trust state. Pure: same state → same view. The maps are keyed by the
 *  real HubTrustState wire type, so a new enum value fails to COMPILE here rather than silently rendering nothing. */
export function hubTrustGlyphSpec(state: HubTrustState): HubTrustBadgeView {
  const presentation = state.toLowerCase() // Q1: plain 1:1 lowercase presentation token
  return {
    state,
    glyph: GLYPH[state],
    label: HUB_TRUST_TEXT.label[state],
    a11yLabel: HUB_TRUST_TEXT.a11y[state],
    tone: TONE[state],
    presentation,
    testId: (hubId: string) => `hub.trust.${hubId}.${presentation}`,
  }
}
