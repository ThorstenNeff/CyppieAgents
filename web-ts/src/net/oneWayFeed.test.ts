import { describe, it, expect } from 'vitest'
import { OneWayFeed } from './oneWayFeed'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

interface Ping {
  agentId: string
  n: number
}

describe('OneWayFeed', () => {
  it('parses typed events, carries query + token in the URL, and reconnects', () => {
    const hub = new FakeSocketHub()
    const got: Ping[] = []
    const feed = new OneWayFeed<Ping>({
      // CYP-420: fail-CLOSED default — a transport-level test opts in to "no schema" explicitly (see bidiFeed.test).
      validate: (raw) => raw as Ping,
      baseUrl: 'ws://host',
      path: '/ws/busy-state',
      token: 't',
      query: { agentId: 'po' },
      onEvent: (e) => got.push(e),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: new Backoff({ initialMs: 0, maxMs: 0, factor: 1 }),
    })
    feed.start()
    expect(hub.last().url).toContain('/ws/busy-state?')
    expect(hub.last().url).toContain('agentId=po')
    expect(hub.last().url).toContain('token=t')

    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify({ agentId: 'po', n: 1 } satisfies Ping))
    hub.last().emitClose() // reconnect
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify({ agentId: 'po', n: 2 } satisfies Ping))

    expect(got).toEqual([
      { agentId: 'po', n: 1 },
      { agentId: 'po', n: 2 },
    ])
  })
})
