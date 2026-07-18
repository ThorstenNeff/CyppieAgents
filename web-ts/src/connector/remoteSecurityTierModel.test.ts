// CYP-676 — the pure connection-security-tier core (spec §9 teeth, model side). Each test excludes a WRONG impl, not
// just "does it render". Assertions bind to stable tier/register/tag structure, never the ⚠PROVISIONAL §4 fact
// wording, so finalizing that copy vs cyp638 won't churn them.
import { describe, it, expect } from 'vitest'
import {
  remoteSecurityTierView,
  REMOTE_SECURITY_TIERS,
  REMOTE_SECURITY_TIER_TAGS,
} from './remoteSecurityTierModel'

describe('remoteSecurityTierModel (CYP-676)', () => {
  it('T4 (sharpest): the default/unresolved tier is UNKNOWN, fail-closed — NEVER native', () => {
    const v = remoteSecurityTierView() // no arg = unresolved
    expect(v.tier).toBe('unknown')
    expect(v.register).toBe('fail-closed')
    expect(v.tier).not.toBe('native') // mutation: default → 'native' (optimistic green) → RED
    expect(v.testId).toBe('remote.security.tier.unknown')
    expect(v.disclosure).toBeNull()
  })

  it('native = guarantee register, ● glyph; gateway = advisory, ◐; unknown = fail-closed, ·', () => {
    expect(remoteSecurityTierView('native')).toMatchObject({ register: 'guarantee', glyph: '●' })
    expect(remoteSecurityTierView('browser-gateway')).toMatchObject({ register: 'advisory', glyph: '◐' })
    expect(remoteSecurityTierView('unknown')).toMatchObject({ register: 'fail-closed', glyph: '·' })
    // guarantee vs advisory are distinct registers — never conflated (§5). mutation: same register → RED
    expect(remoteSecurityTierView('native').register).not.toBe(remoteSecurityTierView('browser-gateway').register)
  })

  it('the INFO disclosure is present ONLY for browser-gateway (the downgrade is disclosed, nothing else is)', () => {
    expect(remoteSecurityTierView('browser-gateway').disclosure).not.toBeNull()
    expect(remoteSecurityTierView('browser-gateway').disclosure?.length).toBeGreaterThan(0)
    expect(remoteSecurityTierView('native').disclosure).toBeNull() // mutation: native gets a disclosure → RED
    expect(remoteSecurityTierView('unknown').disclosure).toBeNull()
  })

  it('the three tier glyphs are distinct + present — colour is never the sole signal (WCAG 1.4.1)', () => {
    const glyphs = REMOTE_SECURITY_TIERS.map((t) => remoteSecurityTierView(t).glyph)
    expect(new Set(glyphs).size).toBe(REMOTE_SECURITY_TIERS.length)
    for (const g of glyphs) expect(g.length).toBeGreaterThan(0)
  })

  it('every tier has a distinct present-iff testId, non-empty label + a11y that spells the tier out', () => {
    const seen = new Set<string>()
    for (const t of REMOTE_SECURITY_TIERS) {
      const v = remoteSecurityTierView(t)
      expect(v.label.length).toBeGreaterThan(0)
      expect(v.a11yLabel.length).toBeGreaterThan(0) // a11y spells the stage, not just the glyph (§8)
      expect(seen.has(v.testId)).toBe(false)
      seen.add(v.testId)
    }
    expect(seen.size).toBe(REMOTE_SECURITY_TIERS.length)
  })

  it('tags live in the remote.security.tier* domain, NOT the pinning trust* family (§1)', () => {
    const T = REMOTE_SECURITY_TIER_TAGS
    for (const tag of [T.badge, T.disclosure, T.tier('native'), T.tier('browserGateway'), T.tier('unknown')]) {
      expect(tag.startsWith('remote.security.tier')).toBe(true)
      expect(tag.includes('trust')).toBe(false)
    }
    expect(T.tier('browserGateway')).toBe('remote.security.tier.browserGateway')
  })
})
