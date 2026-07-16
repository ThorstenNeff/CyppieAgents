// CYP-659 — teeth for the Cross-Project Channel-Share honesty core: grantee candidates exclude the owner, the
// grantee UX guard is fail-closed (unknown/self dropped, never sent), share-state is read from the server view
// (non-optimistic), empty-set = revoke, and the server codes map first-class.
import { describe, it, expect } from 'vitest'
import { RestError } from '../net/rest'
import {
  granteeCandidates,
  sanitizeGrantees,
  currentGranteeIds,
  isShared,
  isRevokingSet,
  shareMutationMessage,
  SHARE_OPERATOR_REQUIRED,
  SHARE_CHANNEL_NOT_FOUND,
} from './channelShareModel'
import type { ChannelShareView, Project } from '../types/generated/contract'

const P = (id: string): Project => ({ id, name: id.toUpperCase() })
const PROJECTS = [P('team-1'), P('team-2'), P('team-3')]

const err = (status: number, code: string) =>
  new RestError(status, 'PUT', '/api/channels/c/share', JSON.stringify({ error: { code, message: code } }))

describe('granteeCandidates', () => {
  it('is the known projects MINUS the channel owner (never offers self)', () => {
    expect(granteeCandidates(PROJECTS, 'team-1').map((p) => p.id)).toEqual(['team-2', 'team-3'])
    // RED if the owner is ever offered as a grantee (a channel cannot be shared with itself).
    expect(granteeCandidates(PROJECTS, 'team-1').some((p) => p.id === 'team-1')).toBe(false)
    // unknown/absent owner → all projects are candidates
    expect(granteeCandidates(PROJECTS, null).map((p) => p.id)).toEqual(['team-1', 'team-2', 'team-3'])
  })
})

describe('sanitizeGrantees (fail-closed UX guard)', () => {
  const candidates = new Set(['team-2', 'team-3'])
  it('keeps only known candidates, de-duplicated', () => {
    expect(sanitizeGrantees(['team-2', 'team-3', 'team-2'], candidates)).toEqual(['team-2', 'team-3'])
  })
  it('DROPS an unknown or self id — never sent (RED if the filter is removed)', () => {
    expect(sanitizeGrantees(['team-2', 'ghost-project'], candidates)).toEqual(['team-2'])
    expect(sanitizeGrantees(['team-1'], candidates)).toEqual([]) // owner not a candidate → dropped
    expect(sanitizeGrantees(['ghost'], candidates)).toEqual([])
  })
})

describe('currentGranteeIds (from the server view, non-optimistic)', () => {
  it('is the unique home-projects of the reachable scope', () => {
    const view: ChannelShareView = {
      shared: true,
      reachableScope: [
        { agentId: 'a', projectId: 'team-2', access: 'READ' },
        { agentId: 'b', projectId: 'team-2', access: 'WRITE' },
        { agentId: 'c', projectId: 'team-3', access: 'READ' },
      ],
    }
    expect(currentGranteeIds(view).sort()).toEqual(['team-2', 'team-3'])
    expect(currentGranteeIds(null)).toEqual([])
    expect(currentGranteeIds({ shared: false })).toEqual([])
  })
})

describe('isShared / isRevokingSet', () => {
  it('shared is read from the view (never inferred)', () => {
    expect(isShared({ shared: true })).toBe(true)
    expect(isShared({ shared: false })).toBe(false)
    expect(isShared(null)).toBe(false)
  })
  it('an empty sanitized set is a revoke (save-with-nothing = revoke, confirmed like an explicit revoke)', () => {
    expect(isRevokingSet([])).toBe(true)
    expect(isRevokingSet(['team-2'])).toBe(false)
  })
})

describe('shareMutationMessage (server codes first-class)', () => {
  it('maps operator_required + channel_not_found distinctly; else generic', () => {
    // RED if operator_required collapses to the generic message — the authz reason must be first-class.
    expect(shareMutationMessage(err(403, SHARE_OPERATOR_REQUIRED))).toContain('Operator')
    expect(shareMutationMessage(err(404, SHARE_CHANNEL_NOT_FOUND))).toContain('nicht gefunden')
    expect(shareMutationMessage(err(500, 'boom'))).toBe('Freigabe fehlgeschlagen.')
    expect(shareMutationMessage(new Error('network'))).toBe('Freigabe fehlgeschlagen.')
    expect(shareMutationMessage(null)).toBe('Freigabe fehlgeschlagen.')
  })
})
