// CYP-831 (CYP-807-A5) — mutation-proven teeth for the client-side issuer advisory producer. Per-cause mapping, the
// null→advisory-neutral-proceed safety property (never a false client denial on UNKNOWN), and integration all the way
// into RemoteConnState.failed(cause) via the CYP-822 machine.
import { describe, it, expect } from 'vitest'
import { computeIssuerPreVerdict, issuerTrustToPreVerdict } from './issuerPreVerdict'
import { remoteConnReduce, failureDisposition, type RemoteConnState } from '../state/remoteConnState'
import type { HubDescriptor, HubIssuerTrust } from '../types/generated/contract'

const desc = (issuerTrust: HubIssuerTrust | null | undefined): HubDescriptor => ({
  hubId: 'h', name: 'H', online: true, defaultPort: 443, lastSeen: 0, dhPubKey: '', issuerTrust,
})

describe('CYP-831 A5 — issuerTrustToPreVerdict: flat issuerTrust → sealed advisory verdict', () => {
  it("★ NOT_TRUSTED → blocked('issuer-not-trusted')", () => {
    // MUT: change the cause/outcome → reds. The terminal issuer arm.
    expect(issuerTrustToPreVerdict('NOT_TRUSTED')).toEqual({ outcome: 'blocked', cause: 'issuer-not-trusted' })
  })

  it("★ REMOTE_NOT_CONFIGURED → blocked('remote-not-configured') [the ratified A5 actionable arm]", () => {
    // MUT: proceed on REMOTE_NOT_CONFIGURED (the pre-A5 / frozen issuerConnectDecision behavior) → reds. This pins the
    // ratified A5 divergence: the client constructs the actionable block itself (no wire, no oracle).
    expect(issuerTrustToPreVerdict('REMOTE_NOT_CONFIGURED')).toEqual({ outcome: 'blocked', cause: 'remote-not-configured' })
  })

  it('★ TRUSTED → proceed', () => {
    expect(issuerTrustToPreVerdict('TRUSTED')).toEqual({ outcome: 'proceed' })
  })

  it('★ null → proceed (advisory-neutral; the client NEVER hard-blocks on UNKNOWN — the server fail-closes)', () => {
    // MUT: block on null → reds. Safety property: an unknown issuer must not become a false client denial.
    expect(issuerTrustToPreVerdict(null)).toEqual({ outcome: 'proceed' })
  })

  it('★ undefined (field absent) → proceed (advisory-neutral)', () => {
    expect(issuerTrustToPreVerdict(undefined)).toEqual({ outcome: 'proceed' })
  })
})

describe('CYP-831 A5 — computeIssuerPreVerdict: from a HubDescriptor the client already holds (no oracle)', () => {
  it('★ delegates per descriptor.issuerTrust across all states', () => {
    expect(computeIssuerPreVerdict(desc('NOT_TRUSTED'))).toEqual({ outcome: 'blocked', cause: 'issuer-not-trusted' })
    expect(computeIssuerPreVerdict(desc('REMOTE_NOT_CONFIGURED'))).toEqual({ outcome: 'blocked', cause: 'remote-not-configured' })
    expect(computeIssuerPreVerdict(desc('TRUSTED'))).toEqual({ outcome: 'proceed' })
    expect(computeIssuerPreVerdict(desc(null))).toEqual({ outcome: 'proceed' })
    expect(computeIssuerPreVerdict(desc(undefined))).toEqual({ outcome: 'proceed' })
  })
})

describe('CYP-831 A5 — integration: descriptor → verdict → RemoteConnState.failed(cause)', () => {
  const atTrustCheck: RemoteConnState = { phase: 'trust-check' }

  it("★ NOT_TRUSTED descriptor → failed('issuer-not-trusted') (terminal disposition)", () => {
    const verdict = computeIssuerPreVerdict(desc('NOT_TRUSTED'))
    expect(remoteConnReduce(atTrustCheck, { kind: 'trustEvaluated', verdict, tierGate: 'ok' })).toEqual({
      phase: 'failed',
      cause: 'issuer-not-trusted',
    })
    expect(failureDisposition('issuer-not-trusted')).toBe('terminal')
  })

  it("★ REMOTE_NOT_CONFIGURED descriptor → failed('remote-not-configured') (actionable disposition)", () => {
    const verdict = computeIssuerPreVerdict(desc('REMOTE_NOT_CONFIGURED'))
    expect(remoteConnReduce(atTrustCheck, { kind: 'trustEvaluated', verdict, tierGate: 'ok' })).toEqual({
      phase: 'failed',
      cause: 'remote-not-configured',
    })
    expect(failureDisposition('remote-not-configured')).toBe('actionable')
  })

  it('★ TRUSTED descriptor + tier ok → connected (advisory proceed; the server is still the gate)', () => {
    const verdict = computeIssuerPreVerdict(desc('TRUSTED'))
    expect(remoteConnReduce(atTrustCheck, { kind: 'trustEvaluated', verdict, tierGate: 'ok' })).toEqual({ phase: 'connected' })
  })

  it('★ UNKNOWN (null) descriptor + tier ok → connected (never a false client denial on unknown)', () => {
    const verdict = computeIssuerPreVerdict(desc(null))
    expect(remoteConnReduce(atTrustCheck, { kind: 'trustEvaluated', verdict, tierGate: 'ok' })).toEqual({ phase: 'connected' })
  })
})
