import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
import { resolve } from 'node:path'

// These guards read the SHIPPED artifacts (the generated token CSS + the committed scrollbar rules), not a
// hand-copied expectation — so they measure what actually loads in the browser.

describe('generated token CSS (CYP-423 — from the single source, all four theme blocks)', () => {
  let css = ''
  beforeAll(() => {
    // regenerate (idempotent) so the test is self-sufficient even without a prior `npm run build`
    execSync('node scripts/generate-tokens-css.mjs', { cwd: process.cwd() })
    css = readFileSync(resolve(process.cwd(), 'src/ui/tokens.generated.css'), 'utf8')
  })

  it('emits the light outline in the base :root and the dark outline under prefers-color-scheme: dark', () => {
    const lightRoot = css.match(/:root\s*\{([^}]*)\}/)?.[1] ?? ''
    expect(lightRoot).toContain('--md-sys-color-outline: #6E8C9E')
    const darkMedia = css.match(/@media \(prefers-color-scheme: dark\)\s*\{\s*:root[^{]*\{([^}]*)\}/)?.[1] ?? ''
    expect(darkMedia).toContain('--md-sys-color-outline: #57707F')
    expect(darkMedia).toContain('--md-sys-color-surface: #06121A')
  })

  it('uses M3 kebab custom-property names and both explicit-theme overrides', () => {
    expect(css).toContain('--md-sys-color-on-surface-variant:')
    expect(css).toContain(":root[data-theme='dark']")
    expect(css).toContain(":root[data-theme='light']")
  })
})

describe('scrollbar a11y (CYP-423 — committed index.css)', () => {
  const scrollbarRules = (): string => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    const start = css.indexOf('.transcript-scroll')
    expect(start).toBeGreaterThanOrEqual(0)
    return css.slice(start)
  }

  it('binds the thumb to the outline token — solid, never an alpha composite', () => {
    const rules = scrollbarRules()
    expect(rules).toContain('var(--md-sys-color-outline)')
    // the whole point of CYP-423 #1: no rgba() alpha anywhere in the scrollbar rules
    expect(rules).not.toMatch(/rgba\(/)
  })

  it('gives the thumb a 24px min target size (WCAG 2.5.8)', () => {
    expect(scrollbarRules()).toContain('min-height: 24px')
  })
})
