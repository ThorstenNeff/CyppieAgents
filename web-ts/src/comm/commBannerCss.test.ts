import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-437 (#3) — the banner tones live on tokens in the committed index.css. Guard the shipped CSS: offline and
// revoked carry DISTINCT, correct tokens and NEVER `tertiary` (which turns green at night → would read as "ok").
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const ruleBody = (selector: string): string => {
  const m = css.match(new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`))
  expect(m, `rule for ${selector}`).not.toBeNull()
  return m![1]
}

describe('comm banner tones (CYP-437 #3)', () => {
  const offline = ruleBody('.comm-status-offline')
  const revoked = ruleBody('.comm-status-revoked')

  it('offline is the WARN amber container, revoked is the error container — distinct, never tertiary', () => {
    expect(offline).toContain('var(--md-sys-color-warn-container)')
    expect(revoked).toContain('var(--md-sys-color-error-container)')
    expect(offline).not.toBe(revoked)
    expect(offline).not.toMatch(/tertiary/)
    expect(revoked).not.toMatch(/tertiary/)
    // a reconnectable offline must not borrow the terminal error tone
    expect(offline).not.toMatch(/error-container/)
  })
})
