import { describe, it, expect } from 'vitest'
import { channelUnread, isReadStateKnown } from '../comm/unreadModel'
import {
  emptyHubState,
  deriveAgents,
  applyChannels,
  applyAcl,
  applyAclEntry,
  setAclPending,
  clearAclPending,
  applyMessage,
  ingestMessages,
  MESSAGE_TIMELINE_CAP,
  applyCommEvent,
  applyTerminalControl,
  applyRunState,
  setLifecyclePending,
  clearLifecyclePending,
  applyRoster,
  rosterPoAgentId,
  applyBusyState,
  applyTokenUsage,
  type HubState,
} from './hubReducers'
import { pendingKey, enforcedValue } from '../comm/aclModel'
import type { AclEntry, Agent, Channel, DeliveredMessage } from '../types/generated/contract'

const agent = (id: string, role: Agent['role']): Agent => ({ id, name: id, role, worktree: id })
const ch = (id: string, members: string[]): Channel => ({ id, name: id, kind: 'DIRECT', members })
const acl = (channelId: string, agentId: string, canRead: boolean, canWrite: boolean): AclEntry => ({ channelId, agentId, canRead, canWrite })
// CYP-744: the store holds DeliveredMessage envelopes. Dedup/ordering key on the STORED message (`.message.id`).
const msg = (id: string, channelId: string, body: string): DeliveredMessage => ({ message: { id, channelId, from: 'x', body, ts: 0 } })

describe('deriveAgents (interim roster from channel members — never a po-<worker> guess)', () => {
  it('is the sorted, deduped union of every channel member', () => {
    expect(deriveAgents([ch('po-frontend', ['po', 'frontend']), ch('po-backend', ['po', 'backend'])])).toEqual([
      'backend',
      'frontend',
      'po',
    ])
  })
  it('is empty for no channels', () => {
    expect(deriveAgents([])).toEqual([])
  })
})

describe('applyChannels', () => {
  it('replaces the channel set and re-derives the agent roster', () => {
    const s = applyChannels(emptyHubState, [ch('po-frontend', ['po', 'frontend'])])
    expect(s.channels).toHaveLength(1)
    expect(s.agents).toEqual(['frontend', 'po'])
  })
})

describe('roster swap (CYP-444 — typed roster is the real source of agents + PO identity)', () => {
  it('applyRoster stores the roster and folds its ids into the agent set (∪ channel members)', () => {
    let s = applyChannels(emptyHubState, [ch('po-qa', ['po', 'qa'])]) // qa is only in a channel, not the roster
    s = applyRoster(s, [agent('po', 'PO'), agent('frontend', 'WORKER'), agent('backend', 'WORKER')])
    expect(s.roster).toHaveLength(3)
    // union: roster ids {po,frontend,backend} ∪ channel member {qa} → a runtime-added agent still surfaces
    expect(s.agents).toEqual(['backend', 'frontend', 'po', 'qa'])
  })

  it('rosterPoAgentId is the role==PO id (real identity), null when no PO / empty roster', () => {
    expect(rosterPoAgentId([agent('frontend', 'WORKER'), agent('po', 'PO')])).toBe('po')
    expect(rosterPoAgentId([agent('frontend', 'WORKER')])).toBeNull()
    expect(rosterPoAgentId([])).toBeNull()
  })
})

describe('ACL — non-optimistic (Spec §W9.2)', () => {
  const base: HubState = applyAcl(applyChannels(emptyHubState, [ch('po-frontend', ['po', 'frontend'])]), [
    acl('po-frontend', 'frontend', true, true),
  ])

  it('setAclPending marks a cell busy WITHOUT changing the enforced value (checked stays put)', () => {
    const s = setAclPending(base, 'po-frontend', 'frontend', 'write', false)
    expect(s.pendingAcl.has(pendingKey('po-frontend', 'frontend', 'write'))).toBe(true)
    // enforced value is unchanged — the switch must not flip on the click
    expect(enforcedValue(s.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: true })
  })

  it('the AclEvent echo flips the enforced value AND clears that cell pending', () => {
    const pending = setAclPending(base, 'po-frontend', 'frontend', 'write', false)
    const echoed = applyAclEntry(pending, acl('po-frontend', 'frontend', true, false))
    expect(enforcedValue(echoed.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: false })
    expect(echoed.pendingAcl.has(pendingKey('po-frontend', 'frontend', 'write'))).toBe(false)
  })

  it('clearAclPending removes a pending flag with no echo — the reject path (CYP-435)', () => {
    const pending = setAclPending(base, 'po-frontend', 'frontend', 'write', false)
    expect(pending.pendingAcl.has(pendingKey('po-frontend', 'frontend', 'write'))).toBe(true)
    const cleared = clearAclPending(pending, 'po-frontend', 'frontend', 'write')
    expect(cleared.pendingAcl.has(pendingKey('po-frontend', 'frontend', 'write'))).toBe(false)
    // enforced value untouched — a reject changes nothing, it just stops the spinner
    expect(enforcedValue(cleared.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: true })
  })

  it('clearAclPending is a no-op (same reference) when nothing is pending', () => {
    expect(clearAclPending(base, 'po-frontend', 'frontend', 'read')).toBe(base)
  })

  it('upsert replaces the cell rather than accumulating duplicates', () => {
    const once = applyAclEntry(base, acl('po-frontend', 'frontend', false, false))
    const twice = applyAclEntry(once, acl('po-frontend', 'frontend', true, false))
    expect(twice.aclEntries.filter((e) => e.channelId === 'po-frontend' && e.agentId === 'frontend')).toHaveLength(1)
    expect(enforcedValue(twice.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: false })
  })
})

describe('comm messages — id-dedup idempotency (reconnect replay adds no duplicates)', () => {
  it('appends distinct ids, drops a repeated id', () => {
    let s = applyMessage(emptyHubState, msg('m1', 'po-frontend', 'a'))
    s = applyMessage(s, msg('m2', 'po-frontend', 'b'))
    const before = s.messagesByChannel.get('po-frontend')
    s = applyMessage(s, msg('m1', 'po-frontend', 'a')) // replay of m1
    const after = s.messagesByChannel.get('po-frontend')
    expect(after).toHaveLength(2)
    expect(after).toBe(before) // unchanged reference — a true no-op on duplicate
    expect(after?.map((d) => d.message.id)).toEqual(['m1', 'm2'])
  })
})

describe('ingestMessages — fold fetched history, deduped against live (CYP-438)', () => {
  it('adds new ids and drops ones already present', () => {
    let s = applyMessage(emptyHubState, msg('live1', 'po-frontend', 'live'))
    s = ingestMessages(s, [msg('hist1', 'po-frontend', 'h1'), msg('live1', 'po-frontend', 'live'), msg('hist2', 'po-frontend', 'h2')])
    expect(s.messagesByChannel.get('po-frontend')?.map((d) => d.message.id)).toEqual(['live1', 'hist1', 'hist2'])
  })
})

describe('CYP-816 — windowed timeline cap (trim-oldest, per-channel)', () => {
  const N = MESSAGE_TIMELINE_CAP
  it('★ holds at N and trims the OLDEST when the N+1th arrives (bounded memory/DOM — G6)', () => {
    // MUT: remove the tail-cap in applyMessage → length becomes N+1 (unbounded) → this reds.
    let s = emptyHubState
    for (let i = 0; i <= N; i++) s = applyMessage(s, msg(`m${i}`, 'po-frontend', `b${i}`)) // N+1 messages
    const ids = s.messagesByChannel.get('po-frontend')!.map((d) => d.message.id)
    expect(ids).toHaveLength(N) // capped, not N+1
    expect(ids).not.toContain('m0') // the oldest (first-arrived) was trimmed
    expect(ids[ids.length - 1]).toBe(`m${N}`) // the newest is retained
  })
  it('★ the cap is PER-CHANNEL — trimming one channel never touches another', () => {
    let s = emptyHubState
    for (let i = 0; i <= N; i++) s = applyMessage(s, msg(`a${i}`, 'chan-a', 'x')) // overflow chan-a
    s = applyMessage(s, msg('b1', 'chan-b', 'y'))
    s = applyMessage(s, msg('b2', 'chan-b', 'z'))
    expect(s.messagesByChannel.get('chan-a')).toHaveLength(N)
    expect(s.messagesByChannel.get('chan-b')?.map((d) => d.message.id)).toEqual(['b1', 'b2']) // intact, uncapped
  })
  it('★ history ingest is capped too (a full-history fold keeps only the last N — no unbounded load)', () => {
    const many = Array.from({ length: N + 5 }, (_, i) => msg(`h${i}`, 'po-frontend', 'x'))
    const s = ingestMessages(emptyHubState, many)
    const ids = s.messagesByChannel.get('po-frontend')!.map((d) => d.message.id)
    expect(ids).toHaveLength(N)
    expect(ids[ids.length - 1]).toBe(`h${N + 4}`) // newest retained
    expect(ids).not.toContain('h0') // oldest folded-out
  })
})

describe('applyCommEvent — the three server variants', () => {
  it('routes acl / channels / message to their reducers', () => {
    let s = applyCommEvent(emptyHubState, { type: 'channels', channels: [ch('po-frontend', ['po', 'frontend'])] })
    expect(s.agents).toEqual(['frontend', 'po'])
    s = applyCommEvent(s, { type: 'acl', entry: acl('po-frontend', 'frontend', true, false) })
    expect(enforcedValue(s.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: false })
    s = applyCommEvent(s, { type: 'message', delivered: msg('m1', 'po-frontend', 'hi') })
    expect(s.messagesByChannel.get('po-frontend')).toHaveLength(1)
  })
})

describe('lifecycle run-state (CYP-431 — non-optimistic)', () => {
  it('setLifecyclePending marks a request in flight WITHOUT changing the run-state', () => {
    const s = setLifecyclePending(emptyHubState, 'backend', 'start')
    expect(s.lifecyclePending.get('backend')).toBe('start')
    expect(s.runStateByAgent.has('backend')).toBe(false) // no optimistic state
  })

  it('applyRunState sets the server-confirmed state AND resolves the pending for that agent', () => {
    const pending = setLifecyclePending(emptyHubState, 'backend', 'start')
    const confirmed = applyRunState(pending, { agentId: 'backend', runState: 'RUNNING' })
    expect(confirmed.runStateByAgent.get('backend')).toBe('RUNNING')
    expect(confirmed.lifecyclePending.has('backend')).toBe(false)
  })

  it('applyRunState stores the ERROR code and clears it when the state leaves ERROR (CYP-446)', () => {
    let s = applyRunState(emptyHubState, { agentId: 'backend', runState: 'ERROR', errorCode: 'CRASHED' })
    expect(s.errorCodeByAgent.get('backend')).toBe('CRASHED')
    s = applyRunState(s, { agentId: 'backend', runState: 'RUNNING' })
    expect(s.errorCodeByAgent.has('backend')).toBe(false)
  })

  it('a fresh ERROR without a code clears any stale reason (fail-closed, no invented reason)', () => {
    let s = applyRunState(emptyHubState, { agentId: 'backend', runState: 'ERROR', errorCode: 'CRASHED' })
    s = applyRunState(s, { agentId: 'backend', runState: 'ERROR' })
    expect(s.errorCodeByAgent.has('backend')).toBe(false)
  })

  it('clearLifecyclePending drops a request that got no event (rejected); no-op ref when none pending', () => {
    const pending = setLifecyclePending(emptyHubState, 'backend', 'stop')
    expect(clearLifecyclePending(pending, 'backend').lifecyclePending.has('backend')).toBe(false)
    expect(clearLifecyclePending(emptyHubState, 'backend')).toBe(emptyHubState)
  })
})

describe('applyTerminalControl — per-agent, last write wins', () => {
  it('records the server-confirmed state under the agent id', () => {
    let s = applyTerminalControl(emptyHubState, { agentId: 'backend', state: 'HANDING_OVER' })
    s = applyTerminalControl(s, { agentId: 'frontend', state: 'INTERACTIVE' })
    s = applyTerminalControl(s, { agentId: 'backend', state: 'INTERACTIVE' })
    expect(s.terminalStateByAgent.get('backend')).toBe('INTERACTIVE')
    expect(s.terminalStateByAgent.get('frontend')).toBe('INTERACTIVE')
  })

  it('CYP-644: also keeps the FULL event (heldBy/since) in terminalControlByAgent — no drift from the enum map', () => {
    const s = applyTerminalControl(emptyHubState, { agentId: 'backend', state: 'INTERACTIVE', heldBy: 'po', since: 1000 })
    expect(s.terminalControlByAgent.get('backend')).toEqual({ agentId: 'backend', state: 'INTERACTIVE', heldBy: 'po', since: 1000 })
    expect(s.terminalStateByAgent.get('backend')).toBe('INTERACTIVE') // the enum projection stays in lockstep
  })
})

describe('CYP-641 — busy-state fold (/ws/busy-state)', () => {
  it('applyBusyState sets the live busy flag per agent (unknown ≠ busy: absent stays absent)', () => {
    const s = applyBusyState(emptyHubState, { agentId: 'backend', busy: true })
    expect(s.busyByAgent.get('backend')).toBe(true)
    expect(s.busyByAgent.has('frontend')).toBe(false) // never seen → absent (the derive reads absent as not-busy)
  })

  it('an explicit busy=false authoritatively clears a prior true (stored, not just dropped)', () => {
    const on = applyBusyState(emptyHubState, { agentId: 'backend', busy: true })
    const off = applyBusyState(on, { agentId: 'backend', busy: false })
    expect(off.busyByAgent.get('backend')).toBe(false)
  })
})

describe('CYP-641 — token-usage fold (/ws/token-usage)', () => {
  it('applyTokenUsage stores a numeric context-token count', () => {
    const s = applyTokenUsage(emptyHubState, { agentId: 'backend', contextTokens: 12345 })
    expect(s.contextTokensByAgent.get('backend')).toBe(12345)
  })

  it('a null OR omitted contextTokens is stored as null (unknown ≠ 0 → no number)', () => {
    const nullish = applyTokenUsage(emptyHubState, { agentId: 'backend', contextTokens: null })
    expect(nullish.contextTokensByAgent.get('backend')).toBeNull()
    const omitted = applyTokenUsage(emptyHubState, { agentId: 'frontend' })
    expect(omitted.contextTokensByAgent.get('frontend')).toBeNull()
  })
})

// ── CYP-705 — the self-only read-state echo is what clears the badge (non-optimistic) ─────────────────────────
describe('CYP-705 — applyCommEvent folds ReadStateEvent', () => {
  it('★ a readState event sets the channel’s server-confirmed cursor + count', () => {
    const s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'po-frontend', lastReadSeq: 12, unreadCount: 3, hasUnreadMention: false })
    expect(channelUnread(s.unreadView, 'po-frontend')).toEqual({ kind: 'unread', count: 3 })
  })

  it('★ before any event the view is UNAVAILABLE — unknown, not a fabricated zero', () => {
    expect(isReadStateKnown(emptyHubState.unreadView)).toBe(false)
    expect(channelUnread(emptyHubState.unreadView, 'po-frontend')).toEqual({ kind: 'unknown' })
  })

  it('★ an echo for one channel never invents state for another', () => {
    const s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 4, unreadCount: 1, hasUnreadMention: false })
    expect(channelUnread(s.unreadView, 'a')).toEqual({ kind: 'unread', count: 1 })
    expect(channelUnread(s.unreadView, 'b')).toEqual({ kind: 'unknown' }) // untouched stays unknown
  })

  it('a later echo replaces the earlier one for the same channel (server is the single count source)', () => {
    let s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 4, unreadCount: 5, hasUnreadMention: false })
    s = applyCommEvent(s, { type: 'readState', channelId: 'a', lastReadSeq: 9, unreadCount: 0, hasUnreadMention: false })
    expect(channelUnread(s.unreadView, 'a')).toEqual({ kind: 'read' }) // cleared ONLY because the server said so
  })

  it('folding a readState echo leaves the other reducers’ state intact', () => {
    let s = applyCommEvent(emptyHubState, { type: 'channels', channels: [ch('po-frontend', ['po', 'frontend'])] })
    s = applyCommEvent(s, { type: 'readState', channelId: 'po-frontend', lastReadSeq: 1, unreadCount: 2, hasUnreadMention: false })
    expect(s.channels.map((c) => c.id)).toEqual(['po-frontend'])
  })
})

// ── CYP-799 — applyReadState threads the SERVER-computed hasUnreadMention through, never a client-fabricated const ─
describe('CYP-799 — the read-state producer carries hasUnreadMention from the event', () => {
  // The two directions together kill BOTH constant mutations in the producer: hardcode `false` → the true case reds;
  // hardcode `true` → the false case reds. So the slot's bit provably tracks the event, not a swallowed default —
  // the exact non-vacuity the fix requires (the server owns "a mention is unread", the client only relays it).
  it('★ a server hasUnreadMention:true lands as true in the store slot', () => {
    const s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 5, unreadCount: 2, hasUnreadMention: true })
    if (s.unreadView.kind !== 'available') throw new Error('expected an available read-state view')
    expect(s.unreadView.channels['a'].hasUnreadMention).toBe(true)
  })

  it('★ a server hasUnreadMention:false lands as false — the bit tracks the event, not a constant', () => {
    const s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 5, unreadCount: 2, hasUnreadMention: false })
    if (s.unreadView.kind !== 'available') throw new Error('expected an available read-state view')
    expect(s.unreadView.channels['a'].hasUnreadMention).toBe(false)
  })
})

// ── CYP-705 ⑥ — transition honesty: no flash, idempotent echoes ───────────────────────────────────────────────
describe('CYP-705 ⑥ — read-state transitions', () => {
  it('★ unavailable → available never passes through a fabricated "read" for an unlisted channel', () => {
    // The transition must not momentarily claim all-clear: before the answer every channel is unknown, and after
    // it only the LISTED ones become known. An unlisted channel stays unknown across the whole transition.
    expect(channelUnread(emptyHubState.unreadView, 'b')).toEqual({ kind: 'unknown' })
    const s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 3, unreadCount: 1, hasUnreadMention: false })
    expect(channelUnread(s.unreadView, 'a')).toEqual({ kind: 'unread', count: 1 })
    expect(channelUnread(s.unreadView, 'b')).toEqual({ kind: 'unknown' }) // no flash to read/zero
  })

  it('★ a duplicated ReadStateEvent is idempotent — reconnect replay cannot double-count', () => {
    // The server may resend after a reconnect; the count is server-computed, so applying it twice must equal
    // applying it once. (Client-side arithmetic is exactly what the contract forbids.)
    const ev = { type: 'readState', channelId: 'a', lastReadSeq: 7, unreadCount: 4, hasUnreadMention: false } as const
    const once = applyCommEvent(emptyHubState, ev)
    const twice = applyCommEvent(once, ev)
    expect(channelUnread(twice.unreadView, 'a')).toEqual(channelUnread(once.unreadView, 'a'))
    expect(channelUnread(twice.unreadView, 'a')).toEqual({ kind: 'unread', count: 4 })
  })

  it('★ an out-of-order echo does not resurrect a cleared badge by arithmetic — last server word wins', () => {
    let s = applyCommEvent(emptyHubState, { type: 'readState', channelId: 'a', lastReadSeq: 9, unreadCount: 0, hasUnreadMention: false })
    s = applyCommEvent(s, { type: 'readState', channelId: 'a', lastReadSeq: 9, unreadCount: 0, hasUnreadMention: false })
    expect(channelUnread(s.unreadView, 'a')).toEqual({ kind: 'read' })
  })
})
