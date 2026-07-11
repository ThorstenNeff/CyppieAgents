import { describe, it, expect } from 'vitest'
import { selectContractInput, contractRequireReal } from '../../scripts/contractInput.mjs'

describe('CONTRACT_REQUIRE_REAL flip (CYP-400)', () => {
  it('uses the real export whenever it is present', () => {
    expect(selectContractInput(true, false)).toBe('real')
    expect(selectContractInput(true, true)).toBe('real')
  })

  it('falls back to the provisional fixture only when the flag is unset', () => {
    expect(selectContractInput(false, false)).toBe('provisional')
  })

  it('fails closed: flag set + real export absent → throws (no silent fixture fallback in CI)', () => {
    expect(() => selectContractInput(false, true)).toThrow(/CONTRACT_REQUIRE_REAL/)
  })

  it('parses the env flag (1/true/yes/on truthy; everything else false)', () => {
    for (const v of ['1', 'true', 'TRUE', 'yes', 'on', 'On']) expect(contractRequireReal(v)).toBe(true)
    for (const v of ['', '0', 'false', 'nope', undefined]) expect(contractRequireReal(v)).toBe(false)
  })
})
