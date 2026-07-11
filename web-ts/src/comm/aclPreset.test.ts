import { describe, it, expect } from 'vitest'
import { hubAndSpokeTarget } from './aclPreset'
import { presetDiff } from './aclModel'
import type { AclEntry, Channel } from '../types/generated/contract'

const channels: Channel[] = [
  { id: 'po-frontend', name: 'a', kind: 'DIRECT', members: ['po', 'frontend'] },
  { id: 'po-backend', name: 'b', kind: 'DIRECT', members: ['po', 'backend'] },
]

describe('hubAndSpokeTarget (Spec §6.2 default topology)', () => {
  it('is read+write for every (channel, member) cell', () => {
    const t = hubAndSpokeTarget(channels)
    expect(t).toHaveLength(4)
    expect(t.every((c) => c.canRead && c.canWrite)).toBe(true)
    expect(t).toContainEqual({ channelId: 'po-backend', agentId: 'backend', canRead: true, canWrite: true })
  })

  it('presetDiff against a fully-open ACL is empty (nothing to restore)', () => {
    const open: AclEntry[] = hubAndSpokeTarget(channels).map((c) => ({ ...c }))
    expect(presetDiff(open, hubAndSpokeTarget(channels))).toEqual([])
  })

  it('presetDiff surfaces exactly the cells that differ', () => {
    const entries: AclEntry[] = [{ channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: false }]
    const diff = presetDiff(entries, hubAndSpokeTarget(channels))
    // frontend.write flips + the three cells with no entry (default deny) all differ from the all-open target
    expect(diff).toContainEqual({ channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: true })
    expect(diff.length).toBe(4)
  })
})
