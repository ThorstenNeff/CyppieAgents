import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-888 (NR-1) committed-CSS guard. jsdom computes no layout, so the render tooth (NavRailShell.render.test.tsx) can
// only see structure, not that the rail actually lays out as a fixed-width left column beside a growing pane. This reads
// the SHIPPED src/index.css and pins the load-bearing layout declarations: the shell is a horizontal ROW that keeps the
// #root grow role, the rail is a fixed-width (non-shrinking) column, and the content pane grows. A regression that made
// the rail full-width, shrinkable, or a column would break the "rail left / content right" spec silently otherwise.
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const ruleBody = (selector: string): string => {
  const re = new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`, 'g')
  let last: string | null = null
  for (const m of css.matchAll(re)) last = m[1]
  expect(last, `rule for ${selector} must exist in the shipped index.css`).not.toBeNull()
  return (last as string).replace(/\/\*[\s\S]*?\*\//g, '') // strip comments — declarations only
}

describe('CYP-888 — nav-rail shell layout (rail left, content right)', () => {
  it('★ the shell is a horizontal ROW that keeps the #root grow role', () => {
    // MUT: flip flex-direction to column (rail stacks on top) → this reds. MUT: drop flex:1 1 auto → the shell would
    // collapse (breaking the CYP-631 height model) → reds.
    const layout = ruleBody('.nav-rail-layout')
    expect(layout).toMatch(/flex-direction:\s*row/)
    expect(layout).toMatch(/flex:\s*1 1 auto/)
    expect(layout).toMatch(/min-height:\s*0/)
  })

  it('★ the rail is a fixed-width, non-shrinking left column', () => {
    // MUT: make the rail flex:1 (grow to full width) → this reds; the rail must stay a narrow fixed column.
    const rail = ruleBody('.nav-rail')
    expect(rail).toMatch(/flex:\s*0 0 auto/) // never grows/shrinks
    expect(rail).toMatch(/width:\s*\d/) // a definite width
  })

  it('★ the content pane grows to fill the space right of the rail', () => {
    // MUT: drop flex:1 1 auto on the pane → the canvas would not fill the remaining width → reds.
    const pane = ruleBody('.nav-content-pane')
    expect(pane).toMatch(/flex:\s*1 1 auto/)
    expect(pane).toMatch(/min-height:\s*0/)
  })
})

describe('CYP-889 — active-item carrier (form + tone, secondary-container never tertiary)', () => {
  it('★ the active item uses a pill FORM (border-radius) — the non-colour carrier (pill fill is <3:1)', () => {
    // MUT: drop the border-radius → the selection would rely on tint alone (measured <3:1) → WCAG 1.4.1 fail → reds.
    const active = ruleBody('.nav-rail-item-active')
    expect(active).toMatch(/border-radius:\s*\d/)
  })

  it('★ the active pill is `secondary-container`, NEVER `tertiary` (hue-stable blue vs the green-flip trap)', () => {
    // MUT: swap to tertiary → green affirmation on a nav selection at night → this reds.
    const active = ruleBody('.nav-rail-item-active')
    expect(active).toContain('var(--md-sys-color-secondary-container)')
    expect(active).not.toMatch(/tertiary/)
    expect(active).not.toMatch(/success|-green/)
  })

  it('★ the active glyph tone shifts to on-secondary-container (a luminance carrier, not colour-only)', () => {
    const glyph = ruleBody('.nav-rail-item-active .nav-rail-item-glyph')
    expect(glyph).toContain('var(--md-sys-color-on-secondary-container)')
  })

  it('★ the worker block is the scrollable middle (fixed Canvas/Settings never displaced)', () => {
    const workers = ruleBody('.nav-rail-workers')
    expect(workers).toMatch(/overflow-y:\s*auto/)
    expect(workers).toMatch(/flex:\s*1 1 auto/)
  })
})
