// @vitest-environment jsdom
// CYP-801 (N3) — render teeth for HubTrustBadge. F2-safe: the states under test come from the wire-parity-bound
// HUB_TRUST_STATES (a-po's connector/ vocabulary), not hand-typed lowercase literals — so a tooth witnesses the REAL
// wire→render path, not a renderer talking to itself. jsdom never applies index.css → a separate readFileSync guard
// pins the shipped classes ([[jsdom-tests-are-css-blind]]).
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { HubTrustBadge } from './HubTrustBadge'
import { HUB_TRUST_STATES } from '../connector/hubTrustModel'

afterEach(cleanup)

describe('CYP-801 — HubTrustBadge render', () => {
  it('★ a VALID descriptor renders the wire trust state as its pill, no ⚠ (positive path from the wire type)', () => {
    const { getByTestId, queryByTestId } = render(<HubTrustBadge hubId="h1" trust="TRUSTED" validity="VALID" />)
    const pill = getByTestId('hub.trust.h1.trusted')
    expect(pill).toBeTruthy()
    expect(pill.className).toContain('hub-trust-trusted')
    expect(pill.getAttribute('aria-label')).toBeTruthy() // the meaning WORD, not glyph/colour alone
    expect(queryByTestId('hub.trust.h1.upstreamError')).toBeNull() // no upstream error on VALID
  })

  it('★ fail-closed: no trust signal → the UNKNOWN pill, never TRUSTED (F4/never-green-by-default)', () => {
    // RED if an absent wire value ever renders as trusted.
    const { getByTestId, queryByTestId } = render(<HubTrustBadge hubId="h1" />)
    expect(getByTestId('hub.trust.h1.unknown')).toBeTruthy()
    expect(queryByTestId('hub.trust.h1.trusted')).toBeNull()
  })

  it('★ MALFORMED = TWO SIGNALS: pill collapses to UNKNOWN (never trusted) AND a SEPARATE ⚠ in its own namespace', () => {
    // Adversarial: a TRUSTED trust value WITH a malformed descriptor must NOT render trusted — the pill is UNKNOWN
    // (fail-closed) and the distinct hub-descriptor-invalid ⚠ carries the diagnosis. RED if malformed renders trusted
    // (masquerade), or as a hub-trust-* state, or drops the ⚠.
    const { getByTestId, queryByTestId } = render(<HubTrustBadge hubId="h1" trust="TRUSTED" validity="MALFORMED" />)
    expect(getByTestId('hub.trust.h1.unknown')).toBeTruthy() // badge UNKNOWN, not trusted
    expect(queryByTestId('hub.trust.h1.trusted')).toBeNull()
    const upstream = getByTestId('hub.trust.h1.upstreamError')
    expect(upstream).toBeTruthy()
    expect(upstream.className).toContain('hub-descriptor-invalid') // distinct namespace, NOT hub-trust-*
    expect(upstream.className).not.toContain('hub-trust') // the two never share the trust namespace
    expect(within(upstream).getByText(/Ungültiger Hub-Descriptor/)).toBeTruthy()
  })

  it('★ every wire state renders a distinct present-iff-state testid (no state collapses to another)', () => {
    for (const state of HUB_TRUST_STATES) {
      cleanup()
      const slug = state.toLowerCase()
      const { getByTestId } = render(<HubTrustBadge hubId="h9" trust={state} validity="VALID" />)
      expect(getByTestId(`hub.trust.h9.${slug}`)).toBeTruthy()
    }
  })

  it('★ SHIPPED CSS carries every hub-trust-{state} class + the separate hub-descriptor-invalid (jsdom is CSS-blind)', () => {
    // The render teeth never apply index.css, so a dropped rule would stay green — pin the shipped stylesheet directly.
    // Class names derived from the wire vocabulary so a new/renamed state must ship its rule too.
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    for (const state of HUB_TRUST_STATES) {
      expect(css).toContain(`.hub-trust-${state.toLowerCase()}`)
    }
    expect(css).toContain('.hub-descriptor-invalid')
  })
})
