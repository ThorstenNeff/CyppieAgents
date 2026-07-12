import { describe, it, expect } from 'vitest'
import { AgentSocket } from './agentSocket'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'
import type { StoredAgentEvent } from '../types/generated/contract'

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })

const evt = (seq: number): string => {
  const e: StoredAgentEvent = {
    seq,
    agentId: 'po',
    projectId: 'p',
    tsMs: 0,
    event: { type: 'system', session_id: 's', model: null },
  }
  return JSON.stringify(e)
}

describe('AgentSocket', () => {
  it('replays from ?since on reconnect and drops duplicates by seq (idempotent, CYP-400 AC)', () => {
    const hub = new FakeSocketHub()
    const seqs: number[] = []
    const as = new AgentSocket({
      baseUrl: 'ws://host',
      agentId: 'po',
      onEvent: (e) => seqs.push(e.seq),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })

    as.start()
    const first = hub.last()
    expect(first.url).toContain('agentId=po')
    expect(first.url).not.toContain('token=') // CYP-454: same-origin cookie authenticates, no token in the query
    expect(first.url).not.toContain('since=') // first connect = full replay

    first.emitOpen()
    first.emitMessage(evt(1))
    first.emitMessage(evt(2))
    expect(seqs).toEqual([1, 2])
    expect(as.cursor()).toBe(2)

    first.emitClose() // → reconnect
    const second = hub.last()
    expect(second.url).toContain('since=2') // resume from the cursor

    second.emitOpen()
    second.emitMessage(evt(2)) // replayed cursor event — must be dropped
    second.emitMessage(evt(3))
    expect(seqs).toEqual([1, 2, 3]) // no duplicate 2
    expect(as.cursor()).toBe(3)
  })

  it('sends a UserTurn as one JSON frame when open', () => {
    const hub = new FakeSocketHub()
    const as = new AgentSocket({
      baseUrl: 'ws://host',
      agentId: 'po',
      onEvent: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    as.start()
    hub.last().emitOpen()
    expect(as.send({ text: 'hi' })).toBe(true)
    expect(hub.last().sent).toEqual(['{"text":"hi"}'])
  })
})
