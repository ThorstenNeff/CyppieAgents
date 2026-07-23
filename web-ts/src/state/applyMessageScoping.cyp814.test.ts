// CYP-814 Batch-1 G5 — applyMessage CHANNEL-SCOPING: an arriving comm message must land in ITS OWN channel bucket
// (`delivered.message.channelId`), never leak into another channel's timeline. The existing hubReducers.test.ts proves
// dedup-by-id and append-order but only ever within ONE channel (`po-frontend`) — so a mutation that hardcodes the
// bucket key or reads the wrong field (the switch-race cross-channel leak) would survive. This drives TWO channels.
import { describe, it, expect } from 'vitest'
import { applyMessage, emptyHubState } from './hubReducers'
import type { DeliveredMessage } from '../types/generated/contract'

const msg = (id: string, channelId: string): DeliveredMessage =>
  ({ message: { id, channelId, from: 'x', body: '', ts: 0 } }) as DeliveredMessage

describe('CYP-814 G5 — applyMessage scopes each message to its own channel (no cross-channel leak)', () => {
  it('★ interleaved messages for channel A + B land in their OWN buckets (a hardcoded/wrong bucket-key mutation REDs)', () => {
    let s = applyMessage(emptyHubState, msg('a1', 'chanA'))
    s = applyMessage(s, msg('b1', 'chanB'))
    s = applyMessage(s, msg('a2', 'chanA'))
    expect((s.messagesByChannel.get('chanA') ?? []).map((d) => d.message.id)).toEqual(['a1', 'a2'])
    expect((s.messagesByChannel.get('chanB') ?? []).map((d) => d.message.id)).toEqual(['b1'])
    // non-vacuity: A's message never appears in B's bucket, and vice-versa
    expect((s.messagesByChannel.get('chanB') ?? []).some((d) => d.message.id.startsWith('a'))).toBe(false)
    expect((s.messagesByChannel.get('chanA') ?? []).some((d) => d.message.id.startsWith('b'))).toBe(false)
  })
})
