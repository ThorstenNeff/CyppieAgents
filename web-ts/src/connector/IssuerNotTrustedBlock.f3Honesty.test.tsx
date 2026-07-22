// @vitest-environment jsdom
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-805 F3 render-honesty — INDEPENDENT Tester2 lens, de-duped against Dev5's own teeth (measured at 68f1c2ce) so this
// is the genuine REMAINDER, not a vacuum duplicate. Dev5's IssuerNotTrustedBlock.render.test.tsx already proves:
// NOT_TRUSTED → distinct terminal block (role=alert, aria-live=assertive, aria-label, class, glyph+text, OOB testid,
// NO button) [spec #2/#3/#4], className ∉ hub-trust-* [partial #5], proceed/absent → null, exactly-one-value-blocks,
// CSS class shipped; and issuerTrustModel.test.ts proves the decision directions + axis-c≠a VALUE-SET. This tooth does
// NOT re-assert those. It adds the F3 honesty they don't reach:
//   #1 (spec §7.1) ★ WARN-amber TONE TOKEN — the block binds `warn-container`/`on-warn-container`, NOT `error-container`/
//      `on-error-container` (axis-b AuthRejected "broken"/over-alarm) and NOT `on-surface`/`on-surface-variant` (axis-a
//      trust badge, CYP-803 — downplaying a hard block as a neutral status). Dev5 pins only the class STRING; the COLOUR
//      TOKEN is unpinned and jsdom is CSS-blind → a readFileSync guard on the shipped stylesheet.
//   #6 (spec §7.6) ★★ SECURITY construction guarantee — the self-asserted `issuer: String?` id (the CLAIMED name of a
//      NOT-trusted peer) can never reach the visible surface, because the component does not ACCEPT it. A `@ts-expect-error`
//      on an `issuer` prop reddens the build (tsc -b covers src/**/*.test.tsx) the moment anyone adds it, + a props-shape
//      source check. Rendering an unverified self-asserted name as fact is a spoofing surface.
//   render-seam ★ axis-c≠a AT THE SEAM — no axis-a `HubTrustState` value fed to the block ever renders it (only axis-c
//      NOT_TRUSTED does). Dev5's axis guard is a static value-SET inequality; this is the runtime render-seam behaviour.
//   #5 (spec §7.5) compact testid namespace distinctness — the block's anchor is the issuer tag, never the axis-a
//      hub.trust.* nor the axis-b authRejected anchor.
//
// Bind source = Dev5's LOCAL frozen-shape type (issuerTrustModel), NOT generated contract.ts (CYP-804 not landed);
// follow-up post-export = a cross-source parity tooth (local mirror == generated authority) — noted, not now.
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { IssuerNotTrustedBlock } from './IssuerNotTrustedBlock'
import type { HubIssuerTrust } from './issuerTrustModel'
import { HUB_TRUST_STATES } from './hubTrustModel'

afterEach(cleanup)

const BLOCK_TESTID = 'remote.connect.error.issuerNotTrusted'
const AUTH_REJECTED_TESTID = 'remote.connect.error.authRejected' // axis-b (operator identity) — must NOT appear here

/** The body of the `.issuer-not-trusted` rule in the shipped index.css (bounded by the first `}`, so the sibling
 *  `.issuer-not-trusted-body/-title/-oob` rules are never captured). */
function issuerBlockCss(css: string): string {
  return css.match(/\.issuer-not-trusted\s*\{([^}]*)\}/)?.[1] ?? ''
}
/** The `--md-sys-color-<token>` bound to `<prop>` inside a CSS rule body, or null if the prop is absent. */
function tokenOf(ruleBody: string, prop: string): string | null {
  return ruleBody.match(new RegExp(`${prop}:\\s*var\\(--md-sys-color-([a-z-]+)\\)`))?.[1] ?? null
}

describe('CYP-805 F3 — IssuerNotTrusted render honesty (WARN tone · security · axis-c≠a at the seam)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  const block = issuerBlockCss(css)

  // ── #1 (spec §7.1) WARN-amber, not ERROR-red, not neutral ───────────────────────────────────────────────────────
  it('non-vacuity: the shipped .issuer-not-trusted rule binds both a background and a text/glyph colour token', () => {
    expect(tokenOf(block, 'background')).not.toBeNull()
    expect(tokenOf(block, 'color')).not.toBeNull()
  })

  it('★ #1 TONE: block background is warn-container (NOT error-container, NOT a surface token) — WARN-amber, not broken/neutral', () => {
    const bg = tokenOf(block, 'background')
    expect(bg).toBe('warn-container')
    expect(bg).not.toBe('error-container') // error-red = axis-b "broken" over-alarm
    expect(bg).not.toBe('surface-variant') // neutral surface = axis-a badge downplay
  })

  it('★ #1 TONE: block text/glyph colour is on-warn-container (NOT on-error-container, NOT an on-surface token)', () => {
    const fg = tokenOf(block, 'color')
    expect(fg).toBe('on-warn-container')
    expect(fg).not.toBe('on-error-container') // axis-b ERROR register
    expect(fg).not.toBe('on-surface') // axis-a CYP-803 neutral badge
    expect(fg).not.toBe('on-surface-variant') // axis-a muted/absence
  })

  // ── #6 (spec §7.6) SECURITY — the self-asserted issuer name never reaches the surface, by construction ────────────
  it('★★ #6 SECURITY: the block does NOT accept an `issuer` prop — a self-asserted name can never reach the surface', () => {
    render(
      // @ts-expect-error — SECURITY (spec §4): the terminal block must NOT accept an `issuer` prop. A self-asserted issuer
      // name is unverified; rendering it as fact is a spoofing surface. If someone ADDS the prop this directive goes unused
      // → tsc -b (which type-checks src/**/*.test.tsx) fails → build RED. React drops the unknown prop at runtime.
      <IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" issuer="evil-self-asserted-name" />,
    )
    // + props-shape source guarantee (independent of the type-level directive): the Props interface declares no `issuer`
    // member. `\bissuer\b` does NOT match `issuerTrust` (no word boundary inside the identifier), so the legit prop is safe.
    const propsBody =
      readFileSync(resolve(process.cwd(), 'src/connector/IssuerNotTrustedBlock.tsx'), 'utf8').match(
        /interface IssuerNotTrustedBlockProps\s*\{([^}]*)\}/,
      )?.[1] ?? ''
    expect(propsBody, 'IssuerNotTrustedBlockProps interface must exist').not.toBe('')
    expect(/\bissuer\b\s*\??\s*:/.test(propsBody), 'Props must declare NO `issuer` member').toBe(false)
  })

  // ── render-seam axis-c≠a + #5 namespace distinctness ────────────────────────────────────────────────────────────
  it('non-vacuity + positive control: axis-c NOT_TRUSTED DOES render the block (so the guards below are not vacuous)', () => {
    const { queryByTestId } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    expect(queryByTestId(BLOCK_TESTID)).not.toBeNull()
  })

  it('★ axis-c≠a AT THE RENDER SEAM: NO axis-a HubTrustState value renders the issuer block — only axis-c NOT_TRUSTED', () => {
    // The axes share the literal 'TRUSTED' but are DISTINCT; axis-a has no NOT_TRUSTED. Feeding ANY axis-a value — incl.
    // the shared 'TRUSTED' and the axis-a hub-key refusal 'REJECTED' — must proceed → no block. RED if an axis fold ever
    // let a hub-key state trigger the issuer refusal (one axis's refusal masquerading as the other's).
    for (const axisA of HUB_TRUST_STATES) {
      cleanup()
      // deliberate wrong-axis leak: a HubTrustState is NOT a HubIssuerTrust — cast to exercise the runtime seam.
      const { queryByTestId } = render(<IssuerNotTrustedBlock issuerTrust={axisA as unknown as HubIssuerTrust} />)
      expect(queryByTestId(BLOCK_TESTID), `axis-a '${axisA}' must NOT render the issuer block`).toBeNull()
    }
  })

  it('★ #5 namespace: the block anchors ONLY in its issuer tag, never the axis-b authRejected anchor', () => {
    const { queryByTestId } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    expect(queryByTestId(BLOCK_TESTID)).not.toBeNull() // present in its OWN (axis-c) namespace
    expect(queryByTestId(AUTH_REJECTED_TESTID)).toBeNull() // never masquerades as the axis-b operator-auth refusal
  })
})
