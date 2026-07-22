// @vitest-environment jsdom
// CYP-805 (S1c) — render teeth for the IssuerNotTrusted terminal block. These key on STRUCTURE (terminal, no-retry,
// assertive, distinct namespace), NOT the interim copy words — so the eventual uiux2 copy/tone re-point and Tester2's
// F3-teeth do not collide with these. jsdom is CSS-blind → a separate readFileSync guard pins the shipped class.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { IssuerNotTrustedBlock } from './IssuerNotTrustedBlock'
import { HUB_ISSUER_TRUST_VALUES } from './issuerTrustModel'

afterEach(cleanup)

describe('CYP-805 — IssuerNotTrustedBlock render', () => {
  it('★ NOT_TRUSTED renders the terminal block: assertive, glyph+text, and NO retry/dismiss control', () => {
    const { getByTestId, container } = render(<IssuerNotTrustedBlock hubId="h1" issuerTrust="NOT_TRUSTED" />)
    const block = getByTestId('issuer.notTrusted.h1')
    expect(block).toBeTruthy()
    expect(block.getAttribute('role')).toBe('alert') // assertive — the refusal announces at once
    expect(block.className).toContain('issuer-not-trusted')
    expect(block.textContent?.trim()).toBeTruthy() // the meaning WORD is present (colour never sole)
    expect(container.querySelector('.issuer-not-trusted-glyph')).toBeTruthy() // + the glyph
    // ★ TERMINAL: no retry/dismiss — RED if any interactive control creeps in (a re-try is never a client action here).
    expect(container.querySelector('button')).toBeNull()
  })

  it('★ distinct namespace: the block is issuer-not-trusted, never in the hub-trust-* (axis a) namespace', () => {
    const { getByTestId } = render(<IssuerNotTrustedBlock hubId="h1" issuerTrust="NOT_TRUSTED" />)
    expect(getByTestId('issuer.notTrusted.h1').className).not.toContain('hub-trust')
  })

  it('★ proceed states render NOTHING — absent/TRUSTED/REMOTE_NOT_CONFIGURED never show the block', () => {
    // RED if any non-NOT_TRUSTED value (incl. absent) ever fabricates the terminal block.
    for (const issuerTrust of ['TRUSTED', 'REMOTE_NOT_CONFIGURED'] as const) {
      cleanup()
      const { queryByTestId } = render(<IssuerNotTrustedBlock hubId="h9" issuerTrust={issuerTrust} />)
      expect(queryByTestId('issuer.notTrusted.h9')).toBeNull()
    }
    cleanup()
    const absent = render(<IssuerNotTrustedBlock hubId="h9" />) // absent → proceed
    expect(absent.queryByTestId('issuer.notTrusted.h9')).toBeNull()
  })

  it('★ exactly one frozen value renders the block (parity with the decision model — no render/model drift)', () => {
    const rendered = HUB_ISSUER_TRUST_VALUES.filter((v) => {
      cleanup()
      const { queryByTestId } = render(<IssuerNotTrustedBlock hubId="hx" issuerTrust={v} />)
      return queryByTestId('issuer.notTrusted.hx') !== null
    })
    expect(rendered).toEqual(['NOT_TRUSTED'])
  })

  it('★ SHIPPED CSS carries the .issuer-not-trusted class (jsdom is CSS-blind)', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    expect(css).toContain('.issuer-not-trusted')
  })
})
