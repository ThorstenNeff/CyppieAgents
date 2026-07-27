import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Nacht-Lane committed-CSS guard (Team2 QA), sibling of comm/commBannerCss.test.ts (CYP-437 #3).
//
// `.comm-status-revoked` is guarded to stay the error-container tone and never `tertiary` (which turns green at
// night and would read as "ok"). `.event-browse-revoked` carries the SAME meaning — an event stream whose access
// was REVOKED is permanently lost, a terminal error — yet nothing pins its shipped style. If it drifts to a neutral
// or tertiary/success tone, a revoked (unrecoverable) stream reads as a normal panel. The reference guard exists
// precisely because a state that must read as "lost" is the kind that silently drifts to "fine".
//
// `.handoff-banner` states its own rule in a comment ("NEVER green ... NEVER error-red") but nothing enforces it:
// green would turn a context-loss CAUTION into an all-clear; error-red would falsely imply a crash. It is warn.
//
// These read the SHIPPED src/index.css, not a hand-copy, so they measure what actually paints.

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

/** Body of the LAST matching rule for a selector (some selectors appear twice; the meaning-bearing one is the
 *  fuller declaration). Fails loud if the selector is absent — an emptied file must never read as "correct". */
const ruleBody = (selector: string): string => {
  const re = new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`, 'g')
  let last: string | null = null
  for (const m of css.matchAll(re)) last = m[1]
  expect(last, `rule for ${selector} must exist in the shipped index.css`).not.toBeNull()
  return last as string
}

describe('event-browse revoked tone (Nacht-Lane, sibling of CYP-437 #3)', () => {
  const revoked = ruleBody('.event-browse-revoked')

  it('a REVOKED event stream carries the terminal error-container tone, never a soft/ok tone', () => {
    expect(revoked).toContain('var(--md-sys-color-error-container)')
    expect(revoked).toContain('var(--md-sys-color-on-error-container)')
    // the exact drifts that would turn "permanently lost" into "fine":
    expect(revoked).not.toMatch(/tertiary/) // green-at-night = reads as ok
    expect(revoked).not.toMatch(/warn-container/) // a recoverable caution, not a terminal loss
    expect(revoked).not.toMatch(/success|-green/) // an all-clear on a revoked stream
  })
})

describe('handoff banner tone (Nacht-Lane) — the rule its own comment states, now enforced', () => {
  const banner = ruleBody('.handoff-banner')

  it('context-loss handoff is the WARN caution tone: never green (all-clear), never error-red (crash)', () => {
    expect(banner).toContain('var(--md-sys-color-warn-container)')
    expect(banner).toContain('var(--md-sys-color-on-warn-container)')
    expect(banner).not.toMatch(/tertiary/) // "NEVER green"
    expect(banner).not.toMatch(/success|-green/)
    expect(banner).not.toMatch(/error-container|--md-sys-color-error\b/) // "NEVER error-red"
  })

  it('the caution and the terminal-error tones are DISTINCT (a handoff is not a crash)', () => {
    const revoked = ruleBody('.event-browse-revoked')
    expect(banner).not.toBe(revoked)
  })
})

describe('CYP-812 — event-browse compact/resume summary WARN tone (long-tail sibling of the handoff/revoked guards)', () => {
  const summary = ruleBody('.event-browse-summary-warn')

  it('★ a compact-timeout / context-lost summary is the WARN caution tone: never green (all-clear), never error-red (crash)', () => {
    // The class toggle is render-tested; the shipped COLOUR was unpinned. A drift to green would read a partial/lost
    // compaction as "all done"; error-red would falsely imply a crash. MUT: swap warn-container→tertiary/error → reds.
    expect(summary).toContain('var(--md-sys-color-warn-container)')
    expect(summary).toContain('var(--md-sys-color-on-warn-container)')
    expect(summary).not.toMatch(/tertiary/) // "NEVER green" (green-at-night reads as ok)
    expect(summary).not.toMatch(/success|-green/)
    expect(summary).not.toMatch(/error-container|--md-sys-color-error\b/) // never a crash tone
  })
})
