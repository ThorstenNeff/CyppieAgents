import { describe, it, expect } from 'vitest'
import { CREATABLE_KINDS, isArchivable, memberGrant, isCreateValid } from './channelMgmtModel'
import type { Channel } from '../types/generated/contract'

const ch = (kind: Channel['kind']): Channel => ({ id: 'c', name: 'C', kind, members: [] })

describe('CYP-875 — channelMgmtModel (client hints; server stays authoritative)', () => {
  it('★ CREATABLE_KINDS = DIRECT/GROUP only — HUB is never user-creatable', () => {
    expect([...CREATABLE_KINDS]).toEqual(['DIRECT', 'GROUP'])
    expect(CREATABLE_KINDS).not.toContain('HUB')
  })

  it('★ isArchivable: a HUB channel is protected (false); DIRECT/GROUP are archivable (true)', () => {
    // MUT: return true for HUB → the UI would offer to archive a protected hub-and-spoke channel → reds.
    expect(isArchivable(ch('HUB'))).toBe(false)
    expect(isArchivable(ch('DIRECT'))).toBe(true)
    expect(isArchivable(ch('GROUP'))).toBe(true)
  })

  it('★ memberGrant: membership IS the ACL — a granted member reads AND writes', () => {
    expect(memberGrant('frontend')).toEqual({ agentId: 'frontend', canRead: true, canWrite: true })
  })

  it('★ isCreateValid: needs id + name + a CREATABLE kind + ≥1 member (fail-closed)', () => {
    const ok = { id: 'x', name: 'X', kind: 'GROUP' as const, members: [memberGrant('a')] }
    expect(isCreateValid(ok)).toBe(true)
    expect(isCreateValid({ ...ok, id: '   ' })).toBe(false) // blank id
    expect(isCreateValid({ ...ok, name: '' })).toBe(false) // blank name
    expect(isCreateValid({ ...ok, members: [] })).toBe(false) // no members
    expect(isCreateValid({ ...ok, kind: 'HUB' as never })).toBe(false) // HUB is not a creatable kind
    expect(isCreateValid({})).toBe(false)
  })
})
