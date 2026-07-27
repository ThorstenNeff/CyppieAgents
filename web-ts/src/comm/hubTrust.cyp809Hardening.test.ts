// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-809 Honesty-Hardening — closes two mutation-survivable gaps my CYP-805 adversarial pass found in the MERGED
// hub-trust badge (real test-coverage blind spots, not code defects):
//   G3: per-state GLYPH and LABEL IDENTITY was unpinned — only pairwise distinctness was checked, so swapping
//       GLYPH.TRUSTED ↔ GLYPH.REJECTED (`●` ↔ `⊘`) or label.TRUSTED ↔ label.REJECTED survived green: a TRUSTED hub
//       renders the barred ⊘ / says "abgelehnt". For a WCAG-1.4.1 "form + word carry the meaning" component that is a
//       real honesty hole. Pinned via the public hubTrustGlyphSpec (GLYPH/label maps are module-private).
//   G2: the `rejected`/`stale` glyph COLOUR tokens were unpinned (CYP-803 pinned only trusted+unknown), and jsdom is
//       CSS-blind — so `rejected error→on-surface` rendered visually neutral while `data-tone="warn"` stayed asserted.
//       Pinned here via a readFileSync guard on the shipped stylesheet (same lens as the CYP-801/803 colour teeth).
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { hubTrustGlyphSpec } from './hubTrustView'
import { HUB_TRUST_STATES } from '../connector/hubTrustModel'
import type { HubTrustState } from '../connector/hubTrustModel'

// ── G3: the frozen per-state IDENTITY (the DS glyph FORMS ◯◔●⊘◑ + the DE meaning WORDS). Swapping any two REDs. ──────
// Glyph FORMS are a stable DS vocabulary (hubTrustView.ts documents them); LABEL words are uiux2 copy — a legit copy
// re-point would redden the label rows here (prove-red = re-point, not current status), which is the honest trade for
// catching a state↔word swap. Glyph rows are the load-bearing G3 pin (the ●↔⊘ swap the finding named).
const GLYPH_IDENTITY: Record<HubTrustState, string> = {
  UNKNOWN: '◯',
  PENDING: '◔',
  TRUSTED: '●',
  REJECTED: '⊘',
  STALE: '◑',
}
const LABEL_IDENTITY: Record<HubTrustState, string> = {
  UNKNOWN: 'Vertrauen nicht geprüft',
  PENDING: 'wird geprüft…',
  TRUSTED: 'vertraut',
  REJECTED: 'abgelehnt',
  STALE: 'nicht mehr aktuell — erneut bestätigen',
}

describe('CYP-809 G3 — hub-trust per-state glyph + label IDENTITY (not just distinctness — a swap must RED)', () => {
  it('non-vacuity: the identity maps cover exactly the wire vocabulary (no state left unpinned)', () => {
    expect([...HUB_TRUST_STATES].sort()).toEqual(Object.keys(GLYPH_IDENTITY).sort())
    expect([...HUB_TRUST_STATES].sort()).toEqual(Object.keys(LABEL_IDENTITY).sort())
  })

  it('★ each state renders ITS glyph FORM — GLYPH.TRUSTED↔REJECTED (●↔⊘) swap REDs (WCAG 1.4.1 meaning-carrier)', () => {
    for (const state of HUB_TRUST_STATES) {
      expect(hubTrustGlyphSpec(state).glyph, `glyph for ${state}`).toBe(GLYPH_IDENTITY[state])
    }
  })

  it('★ each state renders ITS label WORD — a TRUSTED↔REJECTED word swap REDs (re-point if uiux2 changes copy)', () => {
    for (const state of HUB_TRUST_STATES) {
      expect(hubTrustGlyphSpec(state).label, `label for ${state}`).toBe(LABEL_IDENTITY[state])
    }
  })
})

// ── G2: the rejected/stale glyph COLOUR tokens in the shipped stylesheet (jsdom CSS-blind → readFileSync) ────────────
/** The `--md-sys-color-<token>` on a `.hub-trust-<state> .hub-trust-glyph` rule. `[^{]*\{` tolerates the grouped
 *  unknown/pending selector; comment-safe (the trailing `/* … *\/` never contains `color: var(--md-sys-color-…)`). */
function glyphColorToken(css: string, state: string): string | null {
  // CYP-838: STALE's glyph now uses the `--event-sev-*` role family (not `--md-sys-color-*`), so match both namespaces
  // and capture the token suffix (rejected → 'error' via md-sys-color-, stale → 'warn' via event-sev-).
  return css.match(new RegExp(`\\.hub-trust-${state}\\s+\\.hub-trust-glyph[^{]*\\{[^}]*?color:\\s*var\\(--(?:md-sys-color-|event-sev-)([a-z-]+)\\)`))?.[1] ?? null
}

describe('CYP-809 G2 — rejected/stale hub-trust glyph COLOUR tokens are pinned (the CYP-803 lens left them open)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

  it('non-vacuity: the rejected + stale glyph rules bind a colour token (a dropped rule must fail, not pass)', () => {
    expect(glyphColorToken(css, 'rejected')).not.toBeNull()
    expect(glyphColorToken(css, 'stale')).not.toBeNull()
  })

  it('★ rejected glyph = error token (a real trust refusal warns) — mutation error→on-surface REDs', () => {
    expect(glyphColorToken(css, 'rejected')).toBe('error')
  })

  it('★ stale glyph = the event-sev-warn amber token (CYP-838: action-neutral in BOTH themes, NOT the green-in-dark tertiary) — mutation REDs', () => {
    expect(glyphColorToken(css, 'stale')).toBe('warn')
    expect(glyphColorToken(css, 'stale')).not.toBe('tertiary') // CYP-838 fix: no longer the teal-light/GREEN-dark tertiary
    expect(glyphColorToken(css, 'stale')).not.toBe('on-surface-variant') // still not collapsed into absence
  })
})
