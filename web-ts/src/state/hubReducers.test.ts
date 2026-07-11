import { describe, it, expect } from 'vitest'
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
  applyCommEvent,
  applyTerminalControl,
  applyRunState,
  setLifecyclePending,
  clearLifecyclePending,
  type HubState,
} from './hubReducers'
import { pendingKey, enforcedValue } from '../comm/aclModel'
import type { AclEntry, Channel, Message1 } from '../types/generated/contract'

const ch = (id: string, members: string[]): Channel => ({ id, name: id, kind: 'DIRECT', members })
const acl = (channelId: string, agentId: string, canRead: boolean, canWrite: boolean): AclEntry => ({ channelId, agentId, canRead, canWrite })
const msg = (id: string, channelId: string, body: string): Message1 => ({ id, channelId, from: 'x', body, ts: 0 })

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
    expect(after?.map((m) => m.id)).toEqual(['m1', 'm2'])
  })
})

describe('ingestMessages — fold fetched history, deduped against live (CYP-438)', () => {
  it('adds new ids and drops ones already present', () => {
    let s = applyMessage(emptyHubState, msg('live1', 'po-frontend', 'live'))
    s = ingestMessages(s, [msg('hist1', 'po-frontend', 'h1'), msg('live1', 'po-frontend', 'live'), msg('hist2', 'po-frontend', 'h2')])
    expect(s.messagesByChannel.get('po-frontend')?.map((m) => m.id)).toEqual(['live1', 'hist1', 'hist2'])
  })
})

describe('applyCommEvent — the three server variants', () => {
  it('routes acl / channels / message to their reducers', () => {
    let s = applyCommEvent(emptyHubState, { type: 'channels', channels: [ch('po-frontend', ['po', 'frontend'])] })
    expect(s.agents).toEqual(['frontend', 'po'])
    s = applyCommEvent(s, { type: 'acl', entry: acl('po-frontend', 'frontend', true, false) })
    expect(enforcedValue(s.aclEntries, 'po-frontend', 'frontend')).toEqual({ canRead: true, canWrite: false })
    s = applyCommEvent(s, { type: 'message', message: msg('m1', 'po-frontend', 'hi') })
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
})
