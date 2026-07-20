import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-760 — committed-CSS presence guards for semantic markers whose MEANING is carried by a colour token jsdom can't
// see. jsdom is CSS-blind: a *.render.test can assert a class/testid is present but never that it PAINTS the right
// thing. So a drift that turns an error tone neutral/green, or an attention badge indistinguishable from idle, makes
// the screen assert something false while every jsdom test stays green. Sibling of commBannerCss / tokenLayer /
// eventBrowseCss — reads the SHIPPED src/index.css, not a hand-copy, so it measures what actually paints.
//
// Priority (agreed with a2-po): 3 ≈ 4 > 1 > 2.
//   3 = -error load-failure family (a failed fetch reading as an empty "nothing here" is the most common false claim)
//   4 = destructive-confirm error tone (an irreversible action losing its danger affordance)
//   1 = the window attention badge (an agent needing attention looking idle)
//   2 = the event-severity glyph role→token binding (a real ERROR row tinted low-importance)
//
// event-browse-revoked + handoff-banner are already guarded (eventBrowseCss, merged 5613f6b9) → not repeated here.
// Mutation-proven: flip any pinned token in index.css → the matching assertion REDs (see the ticket).

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

/** Body of the LAST rule for `selector` (some appear twice; the meaning-bearing one is the fuller declaration).
 *  Fails LOUD when the selector is absent — a renamed/removed marker must never read as "correct", and an emptied
 *  file must never pass as "identical". Escapes regex metachars so descendant/compound selectors work. */
function ruleBody(selector: string): string {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+')
  const re = new RegExp(`${esc}\\s*\\{([^}]*)\\}`, 'g')
  let last: string | null = null
  for (const m of css.matchAll(re)) last = m[1]
  expect(last, `rule for \`${selector}\` must exist in the shipped index.css`).not.toBeNull()
  return last as string
}

const hasError = (b: string) => expect(b).toContain('var(--md-sys-color-error)')
const notSoftened = (b: string) => {
  // the drifts that would turn a "failed / destructive" signal into "fine": neutral, green-at-night, success
  expect(b).not.toMatch(/on-surface-variant/)
  expect(b).not.toMatch(/tertiary/)
  expect(b).not.toMatch(/success|-green\b/)
}

describe('CYP-760 #3 (Prio) — the -error load-failure family stays the error tone (failed ≠ empty)', () => {
  it.each(['.lifecycle-error', '.project-error', '.channel-share-error', '.load-error'])(
    '%s binds the error colour, never a neutral/ok tone (a failed fetch must not read as empty)',
    (sel) => {
      const b = ruleBody(sel)
      hasError(b)
      notSoftened(b)
    },
  )
})

describe('CYP-760 #4 (Prio) — destructive-confirm keeps its danger tone (irreversible ≠ routine)', () => {
  it.each(['.agent-mgmt-destructive', '.settings-discard-confirm', '.agent-settings-destructive', '.channel-share-destructive'])(
    '%s is the error-CONTAINER tone, never neutral/warn/success',
    (sel) => {
      const b = ruleBody(sel)
      expect(b).toContain('var(--md-sys-color-error-container)')
      expect(b).not.toMatch(/tertiary/)
      expect(b).not.toMatch(/success|-green\b/)
      expect(b).not.toMatch(/warn-container/) // a caution is not a destructive confirm
    },
  )
  it('.project-delete-confirm carries the error colour (the delete button is not routine)', () => {
    const b = ruleBody('.project-delete-confirm')
    hasError(b)
    notSoftened(b)
  })
})

describe('CYP-760 #1 (Prio) — the window attention badge is distinct from its idle siblings', () => {
  const attention = ruleBody('.wa-attention')
  it('.wa-attention binds the error colour, never the neutral sibling tone', () => {
    hasError(attention)
    expect(attention).not.toMatch(/on-surface-variant/) // that is the idle/busy sibling tone → would look idle
  })
  it('.wa-attention is DISTINCT from .wa-busy (attention must not collapse into busy)', () => {
    expect(attention).not.toBe(ruleBody('.wa-busy'))
  })
})

describe('CYP-760 #2 (Prio) — event-severity glyph binds its own role token (error ≠ low-importance)', () => {
  const errGlyph = ruleBody('.event-sev-error .event-sev-glyph')
  const warnGlyph = ruleBody('.event-sev-warn .event-sev-glyph')
  it('the ERROR glyph binds --event-sev-error, NOT the muted info/debug token', () => {
    expect(errGlyph).toContain('var(--event-sev-error)')
    expect(errGlyph).not.toMatch(/--event-sev-(info|debug)\b/) // muted → a real ERROR would look low-importance
  })
  it('the WARN glyph binds --event-sev-warn, and error/warn are DISTINCT roles', () => {
    expect(warnGlyph).toContain('var(--event-sev-warn)')
    expect(errGlyph).not.toBe(warnGlyph)
  })
  it('the product-lead severity mirror keeps the same role→token binding', () => {
    expect(ruleBody('.product-lead-sev.event-sev-error')).toContain('var(--event-sev-error)')
    expect(ruleBody('.product-lead-sev.event-sev-warn')).toContain('var(--event-sev-warn)')
  })
})
