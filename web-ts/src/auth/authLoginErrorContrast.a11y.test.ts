// CYP-813 — token-level contrast guard for the auth login error / unavailable TEXT (a11y-baseline sweep, beyond CYP-806).
// Both `.auth-login-error` and `.auth-login-unavailable` render saturated `--md-sys-color-error` TEXT, and they sit on an
// INHERITED background: neither the text, nor `.auth-screen`, nor `.auth-login-form` sets a background → the text inherits
// `body { background: surface }`. CYP-806 covers the hub-trust/issuer pairs but NOT these two (a coverage asymmetry: their
// siblings `.lifecycle-error`/`.load-error` are token-guarded, these were in no guard). Drift-proof by the SAME construction
// as CYP-806: READ the shipped index.css for each element's colour token + the effective (inherited body) bg token, resolve
// to DS hex via maritimeTokens, and compute the WCAG ratio in BOTH themes. A token swap is auto-re-checked.
// Threshold = WCAG 1.4.3 normal text ≥ 4.5:1. (AT-announce / focus stays guided-live; this is the colour-contrast slice.)
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { contrastRatio, MARITIME_TOKENS } from '../ui/maritimeTokens'

const TEXT_MIN = 4.5
const THEMES = ['light', 'dark'] as const
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

const roleOf = (cssVar: string) => cssVar.replace(/^--md-sys-color-/, '').replace(/-([a-z])/g, (_m, c: string) => c.toUpperCase())
const hex = (theme: (typeof THEMES)[number], cssVar: string): string => {
  const role = roleOf(cssVar)
  const h = (MARITIME_TOKENS[theme] as Record<string, string>)[role]
  if (h === undefined) throw new Error(`CYP-813: token ${cssVar} (role ${role}) missing in ${theme} — non-vacuity fail`)
  return h
}
/** `<prop>: var(--md-sys-color-X)` from the FIRST rule whose selector ends with `selector` (mirrors CYP-806). */
const tokenIn = (selector: string, prop: 'color' | 'background'): string | null => {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const block = css.match(new RegExp(esc + '\\s*\\{([^}]*)\\}'))?.[1]
  if (block === undefined) return null
  return block.match(new RegExp(prop + ':\\s*var\\((--md-sys-color-[a-z-]+)\\)'))?.[1] ?? null
}

// The effective mount bg is the INHERITED body surface — pinned real, read drift-proof (a body-bg swap re-checks here).
const bg = tokenIn('body', 'background')
const SELECTORS = ['.auth-login-error', '.auth-login-unavailable'] as const

describe('CYP-813 — auth login error/unavailable text ≥4.5:1 on its inherited mount surface (WCAG 1.4.3, both themes)', () => {
  it('★ the inheritance holds: the text + .auth-screen + .auth-login-form set NO local bg → body surface IS the mount bg', () => {
    // If any of these gains a local background the effective bg changes and this guard's assumption breaks → RED (re-point).
    expect(bg, 'body background token').toBeTruthy()
    for (const sel of [...SELECTORS, '.auth-screen', '.auth-login-form']) {
      expect(tokenIn(sel, 'background'), `${sel} must have NO local background (inherits body surface)`).toBeNull()
    }
  })

  for (const sel of SELECTORS) {
    const fg = tokenIn(sel, 'color')
    it(`★ ${sel} discovered its colour token in the shipped CSS (non-vacuity — a dropped rule fails, not silently passes)`, () => {
      expect(fg, `${sel} color token not found`).toBeTruthy()
    })
    for (const theme of THEMES) {
      it(`★ ${sel} text ≥ ${TEXT_MIN}:1 on ${theme}`, () => {
        const ratio = contrastRatio(hex(theme, fg!), hex(theme, bg!))
        expect(ratio, `${sel} on ${theme}: ${hex(theme, fg!)} on ${hex(theme, bg!)} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(TEXT_MIN)
      })
    }
  }
})
