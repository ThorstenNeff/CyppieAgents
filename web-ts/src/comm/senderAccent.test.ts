import { describe, it, expect } from 'vitest'
import { senderAccent } from './senderAccent'
import { PO_ACCENT_RAW, WORKER_ACCENTS_RAW } from '../ui/senderAccents.data.mjs'
import { readableAccentOn, contrastRatio } from '../ui/colorAdapt.mjs'
import { MARITIME_TOKENS } from '../ui/maritimeTokens'

const LIGHT = MARITIME_TOKENS.light.surface
const DARK = MARITIME_TOKENS.dark.surface
const TEXT_MIN = 4.5

describe('senderAccent (CYP-407/CYP-436) — theme-adaptive CSS var', () => {
  it('the PO/hub gets the reserved slot var (by role or by id)', () => {
    expect(senderAccent('po')).toBe('var(--sender-accent-po)')
    expect(senderAccent('whoever', 'PO')).toBe('var(--sender-accent-po)')
  })

  it('non-PO ids map deterministically into a worker slot var, never the PO slot', () => {
    expect(senderAccent('frontend')).toBe(senderAccent('frontend'))
    expect(senderAccent('frontend')).toMatch(/^var\(--sender-accent-[0-7]\)$/)
    expect(senderAccent('frontend')).not.toBe('var(--sender-accent-po)')
    expect(senderAccent('backend')).toMatch(/^var\(--sender-accent-[0-7]\)$/)
  })
})

describe('sender accent contrast (CYP-436 — WCAG 1.4.3 as TEXT, MEASURED in BOTH themes)', () => {
  const all = [PO_ACCENT_RAW, ...WORKER_ACCENTS_RAW]

  it('every accent, adapted per theme, clears the 4.5:1 text floor on its surface', () => {
    for (const raw of all) {
      expect(contrastRatio(readableAccentOn(raw, LIGHT), LIGHT), `light ${raw}`).toBeGreaterThanOrEqual(TEXT_MIN)
      expect(contrastRatio(readableAccentOn(raw, DARK), DARK), `dark ${raw}`).toBeGreaterThanOrEqual(TEXT_MIN)
    }
  })

  it('the adaptation actually bites: the RAW pastels fail as text on the light surface (the CYP-436 bug)', () => {
    // If any raw pastel already cleared 4.5:1 on white, the regression this guards wouldn't exist.
    for (const raw of all) {
      expect(contrastRatio(raw, LIGHT), `raw ${raw} on light`).toBeLessThan(TEXT_MIN)
    }
    // …and on the dark surface they already clear, so readableAccentOn leaves them unchanged there.
    for (const raw of all) {
      expect(readableAccentOn(raw, DARK)).toBe(raw)
    }
  })
})
