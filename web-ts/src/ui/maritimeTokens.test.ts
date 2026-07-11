import { describe, it, expect } from 'vitest'
import { MARITIME_TOKENS, MARITIME_ROLES, cssVarName, contrastRatio, WCAG_NON_TEXT_MIN } from './maritimeTokens'

describe('maritime token contrast (CYP-423 — WCAG 1.4.11, MEASURED not assumed)', () => {
  // The W6 bug was measuring the SOLID colour while shipping an ALPHA composite (~2.35:1). This guard measures the
  // exact token the scrollbar thumb binds to (outline) against the surface it sits on, in BOTH schemes.
  it('outline clears 3:1 on the surface in light AND dark', () => {
    const lightRatio = contrastRatio(MARITIME_TOKENS.light.outline, MARITIME_TOKENS.light.surface)
    const darkRatio = contrastRatio(MARITIME_TOKENS.dark.outline, MARITIME_TOKENS.dark.surface)
    expect(lightRatio).toBeGreaterThanOrEqual(WCAG_NON_TEXT_MIN)
    expect(darkRatio).toBeGreaterThanOrEqual(WCAG_NON_TEXT_MIN)
  })

  it('the contrast measure actually bites: the old alpha composite would FAIL 3:1', () => {
    // rgba(128,128,128,0.7) over white composites to ~#A9A9A9; the point is a sub-3 pair is reported as sub-3.
    expect(contrastRatio('#A9A9A9', '#FFFFFF')).toBeLessThan(WCAG_NON_TEXT_MIN)
    // outlineVariant (the near-invisible hairline) must NOT be mistaken for a valid thumb colour.
    expect(contrastRatio(MARITIME_TOKENS.light.outlineVariant, MARITIME_TOKENS.light.surface)).toBeLessThan(WCAG_NON_TEXT_MIN)
  })
})

describe('maritime token completeness (port tokens, not optics)', () => {
  it('both schemes define all 24 roles as valid #rrggbb', () => {
    expect(MARITIME_ROLES).toHaveLength(24)
    for (const scheme of [MARITIME_TOKENS.light, MARITIME_TOKENS.dark]) {
      for (const role of MARITIME_ROLES) {
        expect(scheme[role], role).toMatch(/^#[0-9A-Fa-f]{6}$/)
      }
    }
  })

  it('cssVarName maps camelCase roles to M3 kebab custom properties', () => {
    expect(cssVarName('outline')).toBe('--md-sys-color-outline')
    expect(cssVarName('onSurfaceVariant')).toBe('--md-sys-color-on-surface-variant')
    expect(cssVarName('primaryContainer')).toBe('--md-sys-color-primary-container')
  })
})
