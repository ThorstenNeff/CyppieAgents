import { describe, it, expect } from 'vitest'
import {
  HUB_TRUST_STATES,
  HUB_TRUST_STATE_DEFAULT,
  TRUST_REJECT_REASONS,
  HUB_DESCRIPTOR_VALIDITIES,
} from './hubTrustModel'

describe('hubTrustModel (CYP-798 — axis-a trust vocabulary)', () => {
  it('HubTrustState is the 5-state set, default UNKNOWN (fail-closed, never green-by-default)', () => {
    expect([...HUB_TRUST_STATES].sort()).toEqual(['PENDING', 'REJECTED', 'STALE', 'TRUSTED', 'UNKNOWN'])
    // Mutation: default -> 'TRUSTED' => RED (the fail-closed default must never be a trusted/green value).
    expect(HUB_TRUST_STATE_DEFAULT).toBe('UNKNOWN')
  })

  it('TrustRejectReason is the CLOSED, trust-eval-only set (N4: no net/revoke/malformed here)', () => {
    expect([...TRUST_REJECT_REASONS].sort()).toEqual(['KEY_CHANGED', 'OOB_REJECTED'])
    // N4 exclusions live in OTHER axes — a reject reason must never carry them.
    expect(TRUST_REJECT_REASONS as readonly string[]).not.toContain('MALFORMED')
    expect(TRUST_REJECT_REASONS as readonly string[]).not.toContain('NETWORK')
    expect(TRUST_REJECT_REASONS as readonly string[]).not.toContain('REVOKED')
  })

  it('HubDescriptorValidity is the separate malformed/upstream axis (N4 4th)', () => {
    expect([...HUB_DESCRIPTOR_VALIDITIES].sort()).toEqual(['MALFORMED', 'VALID'])
  })
})
