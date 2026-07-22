// @vitest-environment jsdom
// CYP-805 (S1c) — render teeth for the IssuerNotTrusted terminal block. These key on STRUCTURE (terminal, no-retry,
// assertive, distinct namespace), NOT the copy words — so the uiux2 copy and Tester2's F3-teeth do not collide with
// these. testids are the Compose-parity spec §5 tags (`remote.connect.error.issuerNotTrusted` / `remote.connect.issuerOob`,
// no hubId — single-hub connect flow). jsdom is CSS-blind → a readFileSync guard pins the shipped class.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { IssuerNotTrustedBlock } from './IssuerNotTrustedBlock'
import { HUB_ISSUER_TRUST_VALUES } from './issuerTrustModel'

const BLOCK_TESTID = 'remote.connect.error.issuerNotTrusted'
const OOB_TESTID = 'remote.connect.issuerOob'

afterEach(cleanup)

describe('CYP-805 — IssuerNotTrustedBlock render', () => {
  it('★ NOT_TRUSTED renders the terminal block: assertive, glyph+text, OOB text-line, and NO retry/dismiss control', () => {
    const { getByTestId, container } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    const block = getByTestId(BLOCK_TESTID)
    expect(block).toBeTruthy()
    expect(block.getAttribute('role')).toBe('alert') // assertive — the refusal announces at once
    expect(block.getAttribute('aria-live')).toBe('assertive') // spec §5: an unprompted terminal trust-stop
    expect(block.getAttribute('aria-label')?.trim()).toBeTruthy() // the clean spoken sentence (present, not exact words)
    expect(block.className).toContain('issuer-not-trusted')
    expect(block.textContent?.trim()).toBeTruthy() // the meaning WORD is present (colour never sole)
    expect(container.querySelector('.issuer-not-trusted-glyph')).toBeTruthy() // + the glyph
    expect(getByTestId(OOB_TESTID)).toBeTruthy() // the OOB recovery hint, its own line
    // ★ TERMINAL: no retry/dismiss — RED if any interactive control creeps in (a re-try is never a client action here).
    expect(container.querySelector('button')).toBeNull()
  })

  it('★ distinct namespace: the block is issuer-not-trusted, never in the hub-trust-* (axis a) namespace', () => {
    const { getByTestId } = render(<IssuerNotTrustedBlock issuerTrust="NOT_TRUSTED" />)
    expect(getByTestId(BLOCK_TESTID).className).not.toContain('hub-trust')
  })

  it('★ proceed states render NOTHING — absent/TRUSTED/REMOTE_NOT_CONFIGURED never show the block', () => {
    // RED if any non-NOT_TRUSTED value (incl. absent) ever fabricates the terminal block.
    for (const issuerTrust of ['TRUSTED', 'REMOTE_NOT_CONFIGURED'] as const) {
      cleanup()
      const { queryByTestId } = render(<IssuerNotTrustedBlock issuerTrust={issuerTrust} />)
      expect(queryByTestId(BLOCK_TESTID)).toBeNull()
    }
    cleanup()
    const absent = render(<IssuerNotTrustedBlock />) // absent → proceed
    expect(absent.queryByTestId(BLOCK_TESTID)).toBeNull()
  })

  it('★ exactly one frozen value renders the block (parity with the decision model — no render/model drift)', () => {
    const rendered = HUB_ISSUER_TRUST_VALUES.filter((v) => {
      cleanup()
      const { queryByTestId } = render(<IssuerNotTrustedBlock issuerTrust={v} />)
      return queryByTestId(BLOCK_TESTID) !== null
    })
    expect(rendered).toEqual(['NOT_TRUSTED'])
  })

  it('★ SHIPPED CSS carries the .issuer-not-trusted class (jsdom is CSS-blind)', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    expect(css).toContain('.issuer-not-trusted')
  })
})
