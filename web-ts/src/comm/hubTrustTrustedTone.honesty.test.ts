// @vitest-environment jsdom
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-803 §6 Tooth 10 — TRUSTED honesty (uiux2 never-green ruling). STANDING honesty guard, independent of Dev5's apply
// dev-test (hubTrustView.test.ts:70). Dev5's apply flipped TONE.TRUSTED 'positive'→'neutral' and the shipped glyph token
// primary→on-surface; this tooth pins the honest surface so a revert reddens. Two directions (uiux2):
//   (a) OVERCLAIM-BACK: TRUSTED tone is NEUTRAL (not 'positive') AND the shipped glyph token is on-surface (not primary)
//       — trust is issuer-vouched + REVOCABLE, so no affirming/green accent. Mutation back to positive/primary → RED.
//   (b) OVER-NEUTRALIZE: TRUSTED stays DISTINCT from UNKNOWN — its glyph token is on-surface (full emphasis: evaluated),
//       NOT UNKNOWN's on-surface-variant (muted absence). Mutation trusted-token → on-surface-variant (collapse into
//       absence) → RED. (Form ●≠◯ + label WORD distinction are ALREADY guarded by the CYP-801 distinct-per-state teeth
//       + HubTrustBadge.render.test.tsx — kept below only as a documented sanity, NOT as this tooth's load-bearing claim.)
//
// ★ F2 (Assist2): the (a)-tone claim consumes the REAL wire→state map — hubTrustBadgeState(<a real HUB_TRUST_STATES
//   member>, 'VALID') → hubTrustGlyphSpec(...).tone — NOT a bare hubTrustGlyphSpec('TRUSTED') literal (the dev-test's
//   form). Because wire values are UPPERCASE a literal compiles and would witness only the render, not the wire→state
//   mapping; routing through hubTrustBadgeState + a HUB_TRUST_STATES non-vacuity check makes the mutation bite the MAP.
//
// ★ De-dup / additive value: the dev-test at :70 asserts .tone only, via a literal, and jsdom is CSS-blind — so the
//   glyph COLOUR TOKEN (the actual visual honesty) is pinned by NO existing test. These CSS guards are the new coverage.
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { hubTrustBadgeState, hubTrustGlyphSpec } from './hubTrustView'
import { HUB_TRUST_STATES } from '../connector/hubTrustModel'
import type { HubTrustState } from '../connector/hubTrustModel'

// The UPPERCASE wire tokens (those ARE the wire values); non-vacuity below asserts they are REAL vocabulary members so
// this can never pin a dead literal that a renamed enum quietly orphaned.
const TRUSTED = 'TRUSTED' as HubTrustState
const UNKNOWN = 'UNKNOWN' as HubTrustState

/** The `--md-sys-color-<token>` bound to a `.hub-trust-<state> .hub-trust-glyph` rule in the SHIPPED index.css.
 *  `[^{]*` (not `\s*`) tolerates a GROUPED selector — unknown/pending share one rule
 *  (`.hub-trust-unknown .hub-trust-glyph,\n  .hub-trust-pending .hub-trust-glyph {`) — measured at the object.
 *  Comment-safe: the value line carries a multi-line `/* ... *\/` that mentions on-surface/primary as PROSE, but the
 *  capture requires the literal `color: var(--md-sys-color-…)` structure the comment never has, and `[^}]*?` (non-greedy,
 *  brace-bounded) lands on the real declaration. Returns null if the rule is absent → a VISIBLE failure, never a silent pass. */
function glyphColorToken(css: string, state: string): string | null {
  const re = new RegExp(`\\.hub-trust-${state}\\s+\\.hub-trust-glyph[^{]*\\{[^}]*?color:\\s*var\\(--md-sys-color-([a-z-]+)\\)`)
  const m = css.match(re)
  return m ? m[1] : null
}

describe('CYP-803 §6 Tooth 10 — TRUSTED is honest: neutral (not overclaimed) yet distinct from UNKNOWN (not collapsed)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

  it('non-vacuity: TRUSTED + UNKNOWN are REAL wire vocabulary members (guards against a dead re-pinned literal)', () => {
    expect(HUB_TRUST_STATES).toContain(TRUSTED)
    expect(HUB_TRUST_STATES).toContain(UNKNOWN)
  })

  it('non-vacuity: the shipped CSS binds a glyph token for both trusted + unknown (a missing rule must fail, not pass)', () => {
    expect(glyphColorToken(css, 'trusted')).not.toBeNull()
    expect(glyphColorToken(css, 'unknown')).not.toBeNull()
  })

  // ── (a) OVERCLAIM-BACK — TRUSTED carries no affirming accent ─────────────────────────────────────────────────────
  it('★ (a) TRUSTED tone is NEUTRAL, not positive — via the wire→state map (hubTrustBadgeState), not a bare literal', () => {
    // hubTrustBadgeState('TRUSTED','VALID') → 'TRUSTED' → hubTrustGlyphSpec → tone. Mutation TONE.TRUSTED→'positive' REDs.
    const view = hubTrustGlyphSpec(hubTrustBadgeState(TRUSTED, 'VALID'))
    expect(view.tone).toBe('neutral')
    expect(view.tone).not.toBe('positive') // the affirming/green overclaim uiux2 forbids (trust is revocable)
  })

  it('★ (a) SHIPPED CSS: TRUSTED glyph token is on-surface, NOT primary (jsdom is CSS-blind → readFileSync guard)', () => {
    // The colour token lives in CSS, not the view core, and no other test pins it. Mutation → primary REDs.
    expect(glyphColorToken(css, 'trusted')).toBe('on-surface')
    expect(glyphColorToken(css, 'trusted')).not.toBe('primary')
  })

  // ── (b) OVER-NEUTRALIZE — neutral must not mean collapsed-into-absence ───────────────────────────────────────────
  it('★ (b) TRUSTED glyph token ≠ UNKNOWN glyph token (on-surface ≠ on-surface-variant — not collapsed into absence)', () => {
    const trustedTok = glyphColorToken(css, 'trusted')
    const unknownTok = glyphColorToken(css, 'unknown')
    expect(trustedTok).not.toBe(unknownTok) // collapse guard: mutation trusted→on-surface-variant REDs here
    // pin the specific pair so a TWO-SIDED drift (both moved together, still ≠) can't hide a semantic collapse:
    expect(unknownTok).toBe('on-surface-variant') // muted absence
    expect(trustedTok).toBe('on-surface') // full emphasis: evaluated, distinct from absence
  })

  it('sanity: TRUSTED vs UNKNOWN carry distinct glyph FORM + label WORD (already CYP-801-guarded; documents the axis)', () => {
    const t = hubTrustGlyphSpec(hubTrustBadgeState(TRUSTED, 'VALID'))
    const u = hubTrustGlyphSpec(hubTrustBadgeState(UNKNOWN, 'VALID'))
    expect(t.glyph).not.toBe(u.glyph) // ● ≠ ◯
    expect(t.label).not.toBe(u.label) // meaning WORD ≠ WORD
  })
})
