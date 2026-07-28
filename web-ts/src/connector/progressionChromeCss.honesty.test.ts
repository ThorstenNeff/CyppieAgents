import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-855 committed-CSS guard (sibling of eventBrowseCss.honesty.test.ts). Reads the SHIPPED src/index.css, not a
// hand-copy, so it measures what actually paints. Pins the ONE colour-honesty point of the progression chrome (§5.6):
// the CONNECTED `●` is the LIVE idiom in `primary` (liveness) — it must NEVER drift to a trust-affirmation tone
// (tertiary/green = "trusted", the axis-a conflation CYP-803 forbids) nor an alarm/error tone; and the in-flight spinner
// stays neutral (never alarm — alarm is the terminal failure region).
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
/** Body of the LAST matching rule, with CSS comments STRIPPED — we assert the DECLARATIONS, never comment prose (a
 *  comment mentioning "error" must not trip an alarm-tone check). */
const ruleBody = (selector: string): string => {
  const re = new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`, 'g')
  let last: string | null = null
  for (const m of css.matchAll(re)) last = m[1]
  expect(last, `rule for ${selector} must exist in the shipped index.css`).not.toBeNull()
  return (last as string).replace(/\/\*[\s\S]*?\*\//g, '') // strip comments — declarations only
}

describe('CYP-855 — progression chrome tone (liveness ≠ trust, colour-never-sole)', () => {
  it('★ the CONNECTED `●` is the LIVE primary idiom, NEVER a trust-affirmation (tertiary/green) or alarm (error) tone', () => {
    const live = ruleBody('.remote-progression-live')
    expect(live).toContain('var(--md-sys-color-primary)') // LIVE idiom (liveness axis)
    expect(live).not.toMatch(/tertiary/) // never green = would read as axis-a "trusted" (CYP-803 conflation)
    expect(live).not.toMatch(/success|-green/)
    expect(live).not.toMatch(/error/) // liveness is not an alarm
  })

  it('★ the in-flight spinner is NEUTRAL — never an alarm/error tone (alarm belongs to the terminal failure region)', () => {
    const spinner = ruleBody('.remote-progression-spinner')
    expect(spinner).toMatch(/outline|on-surface-variant|surface/) // neutral in-flight tone
    expect(spinner).not.toMatch(/error|warn-container|-red/)
  })
})
