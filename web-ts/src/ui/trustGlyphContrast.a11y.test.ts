// CYP-806 — token-level contrast guard for the trust/issuer glyph+label pairs (uiux2 CYP-803 UX-QA deferral).
// Values/targets = uiux2's DS-owner reference `docs/design/cyp806-contrast-guard-token-reference.md` (one CYP-806 unit).
// FEASIBILITY (measured): the contrast pairs ARE token-level assertable, so this is a REAL tooth (not guided-live) —
// the DS token hex values live in the single source (via `maritimeTokens.ts` → `maritimeTokens.data.mjs`, light+dark),
// the WCAG math is the canonical `contrastRatio` in `maritimeTokens.ts`, and the glyph→token mapping is in shipped `index.css`.
//
// Drift-proof by construction: it READS the shipped index.css to discover which `--md-sys-color-*` token each glyph /
// pill actually uses (not a hand-copied pair), resolves that token to hex via the DS source, and computes the ratio —
// so a token swap in the CSS (e.g. CYP-803 primary→on-surface) is automatically re-checked against the new value,
// in BOTH themes. Thresholds are the WCAG floors the ticket cites: glyph = graphic (1.4.11, ≥3:1); label = normal
// text (1.4.3, ≥4.5:1). (a11y AT / focus behaviour stays guided — this tooth is the COLOUR-CONTRAST slice only.)
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
// Reuse — NO new util (uiux2 CYP-806 ref §0): the WCAG calculator + token source already exist canonically here.
import { contrastRatio, MARITIME_TOKENS, WCAG_NON_TEXT_MIN } from './maritimeTokens'

const GRAPHIC_MIN = WCAG_NON_TEXT_MIN // 3 — WCAG 1.4.11 non-text contrast — the glyph FORM
const TEXT_MIN = 4.5 // WCAG 1.4.3 normal text — the label WORD (no exported const; the AA text floor)
const THEMES = ['light', 'dark'] as const

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

/** kebab custom-property (`--md-sys-color-on-surface-variant`) → DS source role key (`onSurfaceVariant`). */
const roleOf = (cssVar: string) =>
  cssVar.replace(/^--md-sys-color-/, '').replace(/-([a-z])/g, (_m, c: string) => c.toUpperCase())

const hex = (theme: (typeof THEMES)[number], cssVar: string): string => {
  const role = roleOf(cssVar)
  const h = (MARITIME_TOKENS[theme] as Record<string, string>)[role]
  if (h === undefined) throw new Error(`CYP-806: token ${cssVar} (role ${role}) missing in ${theme} — non-vacuity fail`)
  return h
}

/** Extract the `<prop>: var(--md-sys-color-X)` token from the FIRST rule whose selector text ends with `selector`. */
const tokenIn = (selector: string, prop: 'color' | 'background'): string | null => {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const block = css.match(new RegExp(esc + '\\s*\\{([^}]*)\\}'))?.[1]
  if (block === undefined) return null
  return block.match(new RegExp(prop + ':\\s*var\\((--md-sys-color-[a-z-]+)\\)'))?.[1] ?? null
}

// ── discover the SHIPPED token pairs (fg glyph/text token ON its container bg token) ─────────────────────────────
const hubPillBg = tokenIn('.hub-trust-pill', 'background')
const hubPillText = tokenIn('.hub-trust-pill', 'color')
const issuerBg = tokenIn('.issuer-not-trusted', 'background')
const issuerFg = tokenIn('.issuer-not-trusted', 'color') // on-warn-container: carries BOTH the ▲ glyph and the text

type Pair = { name: string; fg: string | null; bg: string | null; min: number }
const PAIRS: Pair[] = [
  // axis-a hub-trust glyphs (graphic) on the pill's surface-variant background
  { name: 'hub-trust unknown/pending glyph', fg: tokenIn('.hub-trust-pending .hub-trust-glyph', 'color'), bg: hubPillBg, min: GRAPHIC_MIN },
  { name: 'hub-trust trusted glyph (CYP-803 on-surface)', fg: tokenIn('.hub-trust-trusted .hub-trust-glyph', 'color'), bg: hubPillBg, min: GRAPHIC_MIN },
  { name: 'hub-trust rejected glyph', fg: tokenIn('.hub-trust-rejected .hub-trust-glyph', 'color'), bg: hubPillBg, min: GRAPHIC_MIN },
  { name: 'hub-trust stale glyph', fg: tokenIn('.hub-trust-stale .hub-trust-glyph', 'color'), bg: hubPillBg, min: GRAPHIC_MIN },
  // axis-a hub-trust label (text) on the pill background
  { name: 'hub-trust label text', fg: hubPillText, bg: hubPillBg, min: TEXT_MIN },
  // axis-c issuer-not-trusted: the ▲ glyph (graphic) and the title/detail/oob text share on-warn-container on warn-container
  { name: 'issuer-not-trusted glyph', fg: issuerFg, bg: issuerBg, min: GRAPHIC_MIN },
  { name: 'issuer-not-trusted text', fg: issuerFg, bg: issuerBg, min: TEXT_MIN },
]

describe('CYP-806 — trust/issuer glyph+label token-level contrast (WCAG, DS tokens, both themes)', () => {
  it('★ every pair was DISCOVERED in the shipped CSS (non-vacuity — a dropped rule/token fails here, not silently passes)', () => {
    for (const p of PAIRS) {
      expect(p.fg, `${p.name}: fg token not found in index.css`).toBeTruthy()
      expect(p.bg, `${p.name}: bg token not found in index.css`).toBeTruthy()
    }
    expect(PAIRS.length).toBeGreaterThanOrEqual(7)
  })

  for (const p of PAIRS) {
    for (const theme of THEMES) {
      it(`★ ${p.name} ≥ ${p.min}:1 on ${theme} (${p.min === TEXT_MIN ? 'text 1.4.3' : 'graphic 1.4.11'})`, () => {
        const ratio = contrastRatio(hex(theme, p.fg!), hex(theme, p.bg!))
        expect(ratio, `${p.name} on ${theme}: ${hex(theme, p.fg!)} on ${hex(theme, p.bg!)} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(p.min)
      })
    }
  }
})
