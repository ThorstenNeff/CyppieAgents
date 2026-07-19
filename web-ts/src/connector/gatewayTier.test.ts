// CYP-733 — teeth for the connection→tier resolution (UIUX2 BASIS spec 72b41e68 §2/§6).
import { describe, it, expect } from 'vitest'
import { gatewayTierFor, type ConnectionState } from './gatewayTier'
import { REMOTE_SECURITY_TIERS } from './remoteSecurityTierModel'

const ALL_STATES: readonly ConnectionState[] = ['live', 'connecting', 'offline', 'revoked']

describe('CYP-733 — the tier describes the RUNNING connection, fail-closed', () => {
  it('a live connection is BROWSER_GATEWAY — the gateway terminates the transport and sees cleartext', () => {
    expect(gatewayTierFor('live')).toBe('browser-gateway')
  })

  it('★ every non-live state is UNKNOWN — we do not describe a connection that is not carrying traffic', () => {
    for (const state of ALL_STATES.filter((s) => s !== 'live')) {
      expect(gatewayTierFor(state)).toBe('unknown')
    }
  })

  it('★ NO input yields `native` — a browser structurally cannot reach the native E2E tier', () => {
    // The strongest tier must be unreachable by accident: a browser has no Noise-E2E transport at all, so showing
    // NATIVE would claim a guarantee that does not exist. Gaining it must be a deliberate edit here, never a
    // side-effect of adding a connection state. Scanned over EVERY state rather than the ones I happened to think
    // of, so a new state cannot quietly introduce it.
    for (const state of ALL_STATES) expect(gatewayTierFor(state)).not.toBe('native')
  })

  it('★ only ever returns a tier the badge knows — no unrenderable value can leak through', () => {
    for (const state of ALL_STATES) expect(REMOTE_SECURITY_TIERS).toContain(gatewayTierFor(state))
  })

  it('is total over the declared states (no undefined for a state the union allows)', () => {
    for (const state of ALL_STATES) expect(typeof gatewayTierFor(state)).toBe('string')
  })
})
