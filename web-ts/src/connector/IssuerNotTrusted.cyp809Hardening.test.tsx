// @vitest-environment jsdom
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-809 Honesty-Hardening — closes two mutation-survivable gaps my own CYP-805 adversarial pass found in the MERGED
// IssuerNotTrusted block (real test-coverage blind spots, not code defects):
//   G1: the WARN glyph `▲` was asserted NOWHERE — `glyph: '▲' → '⚠'` (the forbidden ERROR glyph, conflating the axis-c
//       protective refusal with the axis-b "broken" register) survived every test. The spec (issuerTrustModel.ts) is
//       emphatic: "Glyph ▲ = the web-ts WARN glyph (NOT ⚠ = ERROR)". Pinned here at BOTH the copy const and the render.
//   G4: "TERMINAL, no retry/dismiss" was enforced only vs `<button>` — a `role="button"` anchor / `onClick` div survived.
//       Re-pinned ROLE-AGNOSTICALLY: no interactive element/role in the DOM + no click handler in the source.
// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { IssuerNotTrustedBlock } from './IssuerNotTrustedBlock'
import { ISSUER_NOT_TRUSTED_BLOCK_COPY } from './issuerTrustModel'

afterEach(cleanup)

const WARN_GLYPH = '▲' // web-ts severity language: ▲ = WARN (AclPanel/eventLog/remoteSecurityTierModel)
const ERROR_GLYPH = '⚠' // ⚠ = ERROR ("broken"); reserved for axis-b AuthRejected — the block must NEVER use it

describe('CYP-809 G1 — IssuerNotTrusted block glyph is the WARN ▲, never the ERROR ⚠ (protective refusal ≠ broken)', () => {
  it('★ the copy const glyph is the WARN ▲ (mutation ▲→⚠ REDs — over-alarm + axis-b conflation)', () => {
    expect(ISSUER_NOT_TRUSTED_BLOCK_COPY.glyph).toBe(WARN_GLYPH)
    expect(ISSUER_NOT_TRUSTED_BLOCK_COPY.glyph).not.toBe(ERROR_GLYPH)
  })

  it('★ the RENDERED glyph (what the operator sees) is the WARN ▲, not the ERROR ⚠', () => {
    const { container } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    const glyph = container.querySelector('.issuer-not-trusted-glyph')
    expect(glyph, 'the glyph span must render').toBeTruthy()
    expect(glyph?.textContent?.trim()).toBe(WARN_GLYPH)
    expect(glyph?.textContent).not.toContain(ERROR_GLYPH)
  })
})

describe('CYP-809 G4 — the terminal block has NO retry/dismiss affordance (role-agnostic, not just <button>)', () => {
  // The no-retry invariant is load-bearing (recovery is OOB-only); the old test only excluded `<button>`.
  const INTERACTIVE = 'button, a, [role="button"], [role="link"], input, select, textarea, [tabindex]'

  it('★ no interactive element or ARIA control role in the rendered block (a role="button"/anchor retry REDs)', () => {
    const { container } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    const block = container.querySelector('.issuer-not-trusted')
    expect(block, 'the block must render').toBeTruthy()
    expect(block?.querySelectorAll(INTERACTIVE).length, 'no retry/dismiss/grant affordance is permitted').toBe(0)
  })

  it('★ source guarantee: the component render wires NO click/activation handler (catches a container onClick React hides from the DOM)', () => {
    // A React `onClick` on the container adds no DOM attribute → the query above can't see it. This source lens closes
    // that: the render body must contain no onClick/onPointerDown/onMouseDown handler and no href. Not comment-fooled —
    // the block's prose says "NOT a button", never the literal `onClick=`/`href=`.
    const src = readFileSync(resolve(process.cwd(), 'src/connector/IssuerNotTrustedBlock.tsx'), 'utf8')
    expect(src, 'no click/activation handler in the terminal block').not.toMatch(/on(Click|PointerDown|MouseDown|KeyDown)\s*=/)
    expect(src, 'no navigation affordance either').not.toMatch(/\shref\s*=/)
  })
})
