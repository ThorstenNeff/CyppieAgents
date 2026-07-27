// CYP-838 — the STALE hub-trust glyph must be action-neutral in BOTH themes. It used --md-sys-color-tertiary, which is
// teal in light but GREEN (#40D6A0) in DARK → STALE read as "trusted/ok" at night (a CYP-300 trust-inversion). It now
// uses the theme-aware --event-sev-warn amber role. jsdom is CSS-blind, so this reads the SHIPPED index.css to discover
// the bound token, then resolves both theme values from the DS source (EVENT_SEVERITY) and pins amber-not-green.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { EVENT_SEVERITY, MARITIME_TOKENS } from '../ui/maritimeTokens'

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const staleGlyphVar = css.match(/\.hub-trust-stale\s+\.hub-trust-glyph[^{]*\{[^}]*?color:\s*var\((--[a-z-]+)\)/)?.[1] ?? null

describe('CYP-838 — the STALE glyph is action-neutral amber in BOTH themes (fixes the green-in-dark tertiary inversion)', () => {
  it('★ the shipped STALE glyph rule binds --event-sev-warn, NOT --md-sys-color-tertiary', () => {
    // MUT: revert index.css to --md-sys-color-tertiary → this reds. The token swap IS the fix.
    expect(staleGlyphVar).toBe('--event-sev-warn')
  })

  it('★ --event-sev-warn resolves to amber in BOTH themes; its DARK value is NOT the tertiary green (#40D6A0)', () => {
    // The inversion was specifically the dark tertiary reading green/trusted. Resolved from the DS source (what ships) —
    // the dark STALE token must not equal the #40D6A0 green it replaced.
    expect(EVENT_SEVERITY.light.warn.toLowerCase()).toBe('#9a6400') // light amber (brownish)
    expect(EVENT_SEVERITY.dark.warn.toLowerCase()).toBe('#ffc857') // dark amber (gold)
    expect(EVENT_SEVERITY.dark.warn).not.toBe(MARITIME_TOKENS.dark.tertiary) // amber ≠ the #40D6A0 green it replaced
  })
})
