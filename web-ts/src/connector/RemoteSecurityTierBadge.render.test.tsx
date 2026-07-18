// @vitest-environment jsdom
// CYP-676 — the standalone connection-security-tier badge (spec §9 teeth, render side). Discriminating: each test
// excludes a wrong impl. Assertions bind to remoteSecurityTierView (the copy seam) so finalizing the ⚠PROVISIONAL §4
// fact copy flows through without editing the tests.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { RemoteSecurityTierBadge } from './RemoteSecurityTierBadge'
import { remoteSecurityTierView, REMOTE_SECURITY_TIER_TAGS as T } from './remoteSecurityTierModel'

afterEach(cleanup)

describe('RemoteSecurityTierBadge (CYP-676 standalone)', () => {
  it('T1 native: badge + tier.native present; gateway/unknown anchors absent; ● glyph; label; no disclosure', () => {
    const { getByTestId, queryByTestId } = render(<RemoteSecurityTierBadge tier="native" />)
    expect(getByTestId(T.badge)).toBeTruthy()
    expect(getByTestId(T.tier('native'))).toBeTruthy()
    expect(queryByTestId(T.tier('browserGateway'))).toBeNull()
    expect(queryByTestId(T.tier('unknown'))).toBeNull()
    const pill = getByTestId(T.tier('native'))
    expect(pill.querySelector('.rst-glyph')?.textContent).toBe('●')
    expect(pill.querySelector('.rst-label')?.textContent).toBe(remoteSecurityTierView('native').label)
    expect(pill.getAttribute('aria-label')).toBe(remoteSecurityTierView('native').a11yLabel)
    expect(queryByTestId(T.disclosure)).toBeNull() // native carries no gateway disclosure
  })

  it('T2 gateway: tier.browserGateway present; disclosure ALWAYS-VISIBLE (no interaction) with the honest text', () => {
    const { getByTestId, queryByTestId } = render(<RemoteSecurityTierBadge tier="browser-gateway" />)
    expect(getByTestId(T.tier('browserGateway'))).toBeTruthy()
    expect(queryByTestId(T.tier('native'))).toBeNull()
    // present immediately after render, no fireEvent → it is not tap-to-reveal (§3 always-visible)
    const disc = getByTestId(T.disclosure)
    expect(disc.hidden).toBe(false)
    // the SPECIFIC honest disclosure copy is shown, not a generic string (spec §9 T2) — bound to the seam
    expect(disc.querySelector('.rst-disclosure-text')?.textContent).toBe(remoteSecurityTierView('browser-gateway').disclosure)
  })

  it('T3 gateway is NEUTRAL, not an alarm: advisory register, ◐ glyph (not ▲/WARN), no error class', () => {
    const { getByTestId } = render(<RemoteSecurityTierBadge tier="browser-gateway" />)
    const pill = getByTestId(T.tier('browserGateway'))
    expect(pill.getAttribute('data-register')).toBe('advisory')
    expect(pill.querySelector('.rst-glyph')?.textContent).toBe('◐') // ◐ half, deliberately NOT ▲ (WARN)
    expect(pill.className.includes('error')).toBe(false) // no error/alarm coding (colour verified in the browser pass)
  })

  it('T4 (sharpest): NO tier prop → resolves UNKNOWN, never native (no optimistic-green default)', () => {
    const { getByTestId, queryByTestId } = render(<RemoteSecurityTierBadge />)
    expect(getByTestId(T.tier('unknown'))).toBeTruthy()
    expect(getByTestId(T.tier('unknown')).getAttribute('data-tier')).toBe('unknown')
    expect(queryByTestId(T.tier('native'))).toBeNull() // mutation: default → native → RED
  })

  it('T5 glyph distinctness across the three states (non-colour signal carries the tier)', () => {
    const glyphOf = (tier: 'native' | 'browser-gateway' | 'unknown', id: string) => {
      const { getByTestId } = render(<RemoteSecurityTierBadge tier={tier} />)
      const g = getByTestId(T.tier(id)).querySelector('.rst-glyph')?.textContent
      cleanup()
      return g
    }
    const glyphs = [glyphOf('native', 'native'), glyphOf('browser-gateway', 'browserGateway'), glyphOf('unknown', 'unknown')]
    expect(new Set(glyphs).size).toBe(3)
  })
})
