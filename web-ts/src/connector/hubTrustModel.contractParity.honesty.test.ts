import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { HUB_TRUST_STATES, TRUST_REJECT_REASONS, HUB_DESCRIPTOR_VALIDITIES } from './hubTrustModel'

// CYP-798 parity oracle — the hand-mirrored web-ts trust-vocabulary unions vs the AUTHORITATIVE committed contract
// export (web-ts/contract/openapi.json, drift-guarded against :core by ContractExportDriftTest). The unions in
// hubTrustModel.ts are a SEPARATE TS type: :core could add/rename/drop an enum member and the union would keep
// compiling while web-ts silently diverged. This ties the mirror to the authority — each named standalone-exported
// enum (CYP-798 part ②: ContractGenerator.STANDALONE_ENUM_EXPORTS → SchemaWalker.registerNamedEnum) in the committed
// openapi.json must equal the hand union EXACTLY. Reads the committed artifact, not a hand-copy, so it measures the
// real contract the server speaks. Mutation (run): drop a member from any *_ARRAY → RED; add a phantom → RED.

function contractEnum(name: string): string[] {
  const doc = JSON.parse(readFileSync(resolve(process.cwd(), 'contract/openapi.json'), 'utf8'))
  const schema = doc?.components?.schemas?.[name] as { type?: string; enum?: unknown[] } | undefined
  if (!schema || !Array.isArray(schema.enum)) {
    throw new Error(
      `openapi.json has no named enum schema '${name}' — the CYP-798 ② standalone export is missing (the browser team can't consume it)`,
    )
  }
  expect(schema.type, `'${name}' must be a plain string enum`).toBe('string')
  return schema.enum.map(String)
}

const sorted = (xs: readonly string[]): string[] => [...xs].sort()

describe('hub-trust vocabulary ↔ contract parity (CYP-798)', () => {
  it('non-vacuity: the contract carries the 3 named standalone enums (there IS an authority to compare against)', () => {
    expect(contractEnum('HubTrustState').length).toBeGreaterThan(0)
    expect(contractEnum('TrustRejectReason').length).toBeGreaterThan(0)
    expect(contractEnum('HubDescriptorValidity').length).toBeGreaterThan(0)
  })

  it('★ HUB_TRUST_STATES equals the exported HubTrustState exactly — none the client omits, none it invents', () => {
    expect(sorted(HUB_TRUST_STATES)).toEqual(sorted(contractEnum('HubTrustState')))
  })

  it('★ TRUST_REJECT_REASONS equals the exported TrustRejectReason exactly (the CLOSED set stays closed)', () => {
    expect(sorted(TRUST_REJECT_REASONS)).toEqual(sorted(contractEnum('TrustRejectReason')))
  })

  it('★ HUB_DESCRIPTOR_VALIDITIES equals the exported HubDescriptorValidity exactly', () => {
    expect(sorted(HUB_DESCRIPTOR_VALIDITIES)).toEqual(sorted(contractEnum('HubDescriptorValidity')))
  })
})
