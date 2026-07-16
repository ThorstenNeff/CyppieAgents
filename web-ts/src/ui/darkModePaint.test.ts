// CYP-648 — regression guard for the dark-mode paint. The CYP-643 toggle flipped the token vars, but the desktop
// stayed white because nothing bound the ROOT surface to those tokens. jsdom computes no CSS cascade (getComputedStyle
// returns no resolved custom-property paint), so a behavioural test can't see this — that's exactly why it slipped
// all three gates. This static guard reads index.css and asserts the load-bearing rule survives: `body` background
// bound to the FLIPPING `--md-sys-color-surface` token (+ on-surface text) and `color-scheme` pinned per explicit
// theme. If a future edit unbinds the root surface, this reddens even though the browser proof isn't in CI.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const css = readFileSync(fileURLToPath(new URL('../index.css', import.meta.url)), 'utf8')
// Collapse whitespace so the assertions are tolerant of formatting.
const flat = css.replace(/\s+/g, ' ')

describe('CYP-648 — the root surface is painted from the flipping theme token', () => {
  it('body background is bound to --md-sys-color-surface (the token that flips with data-theme)', () => {
    expect(flat).toMatch(/body\s*\{[^}]*background:\s*var\(--md-sys-color-surface\)/)
  })

  it('body text is bound to --md-sys-color-on-surface', () => {
    expect(flat).toMatch(/body\s*\{[^}]*color:\s*var\(--md-sys-color-on-surface\)/)
  })

  it('color-scheme is pinned for the explicit dark + light overrides (UA controls follow)', () => {
    expect(flat).toMatch(/:root\[data-theme='dark'\]\s*\{[^}]*color-scheme:\s*dark/)
    expect(flat).toMatch(/:root\[data-theme='light'\]\s*\{[^}]*color-scheme:\s*light/)
  })
})
