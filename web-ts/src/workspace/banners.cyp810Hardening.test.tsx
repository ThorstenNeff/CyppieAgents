// @vitest-environment jsdom
// CYP-810 G-a2/a3/a4/a5 — the two workspace banners' honesty (severity colour + neutral glyph + the primary CTA):
//   G-a2 OverloadBanner is WARN-amber, never neutral/error — its `.overload-banner` colour token was unpinned (jsdom is
//        CSS-blind; every sibling severity strip IS guarded, this was the lone gap).
//   G-a3 UnconfiguredBanner glyph is the NEUTRAL ● — asserted nowhere; ●→▲/⚠ turned a "nothing failed, one click away"
//        call-to-action into a WARN/ERROR alarm.
//   G-a4 UnconfiguredBanner colour is neutral surface-variant — unpinned; a drift to warn/error reads the standing CTA
//        as an alarm.
//   G-a5 the "Einrichten" primary action is present + wired to onSetUp — the banner's whole reason to exist was unpinned
//        (only its absence-of-a-dismiss was tested); the CTA could vanish or go dead and stay green.
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { UnconfiguredBanner } from './UnconfiguredBanner'

afterEach(cleanup)

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
/** The `--md-sys-color-<token>` bound to `background` on a `.<selector>` rule (comment-safe, single-rule bounded). */
function bgToken(selector: string): string | null {
  return css.match(new RegExp(`\\.${selector}\\s*\\{[^}]*?background:\\s*var\\(--md-sys-color-([a-z-]+)\\)`))?.[1] ?? null
}

describe('CYP-810 G-a2 — OverloadBanner is WARN-amber (never neutral, never error) [CSS, jsdom-blind]', () => {
  it('★ .overload-banner background is warn-container (mutation →surface-variant/error-container REDs)', () => {
    expect(bgToken('overload-banner')).toBe('warn-container')
    expect(bgToken('overload-banner')).not.toBe('error-container') // not a crash
    expect(bgToken('overload-banner')).not.toBe('surface-variant') // not neutral
  })
})

describe('CYP-810 G-a3/a4/a5 — UnconfiguredBanner: neutral call-to-action (glyph ●, neutral colour), primary action wired', () => {
  it('★ G-a3: the glyph is the NEUTRAL ● (mutation ●→▲/⚠ turns a call-to-action into an alarm)', () => {
    const { container } = render(<UnconfiguredBanner onSetUp={() => {}} />)
    const glyph = container.querySelector('.unconfigured-glyph')
    expect(glyph?.textContent?.trim()).toBe('●')
    expect(glyph?.textContent).not.toContain('▲') // not the WARN glyph
    expect(glyph?.textContent).not.toContain('⚠') // not the ERROR glyph
  })

  it('★ G-a4: .unconfigured-banner background is neutral surface-variant (mutation →warn/error-container REDs)', () => {
    expect(bgToken('unconfigured-banner')).toBe('surface-variant')
    expect(bgToken('unconfigured-banner')).not.toBe('warn-container')
    expect(bgToken('unconfigured-banner')).not.toBe('error-container')
  })

  it('★ G-a5: the primary action is present, labelled, and fires onSetUp (drop the button/handler REDs)', () => {
    const onSetUp = vi.fn()
    const { getByTestId } = render(<UnconfiguredBanner onSetUp={onSetUp} />)
    const action = getByTestId('workspace.unconfiguredBanner.action')
    expect(action.textContent?.trim()).toBeTruthy() // labelled (word carries it; not copy-pinned)
    fireEvent.click(action)
    expect(onSetUp).toHaveBeenCalledTimes(1) // wired to the set-up flow, not dead
  })
})
