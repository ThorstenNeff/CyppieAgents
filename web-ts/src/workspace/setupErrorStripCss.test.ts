// CYP-758 — a CSS-presence guard for the degraded-workspace setup-error strip. jsdom render tests never apply
// index.css, so a dropped `.workspace-setup-error` rule is INVISIBLE to them (the inner LoadErrorRetry still renders,
// so the error text + retry survive — the honesty invariant holds via the render tooth). What this guards is the
// STANDING-STRIP presence + its error-TONE reinforcement, which a render test structurally cannot see. Same class as
// [[jsdom-tests-are-css-blind]]: guard shipped CSS by reading the file.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const ruleBody = (selector: string): string => {
  const m = css.match(new RegExp(`\\${selector}(?![\\w-])[^{}]*\\{([^}]*)\\}`))
  return m?.[1] ?? ''
}

describe('CYP-758 — the workspace setup-error strip ships its CSS', () => {
  it('★ .workspace-setup-error has a non-empty rule — markup without it renders as an unstyled, non-strip line', () => {
    expect(ruleBody('.workspace-setup-error').trim().length).toBeGreaterThan(0)
  })

  it('★ it carries the ERROR tone (distinct from the neutral unconfigured / warn overload strips)', () => {
    // colour only REINFORCES (the message text is the WCAG-1.4.1 carrier), but a failure strip must not read as a
    // neutral notice — a dropped error-container background would soften a real failure into a calm one.
    expect(ruleBody('.workspace-setup-error')).toMatch(/error-container/)
  })
})
