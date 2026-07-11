import { describe, it, expect } from 'vitest'
import { BidiFeed } from './bidiFeed'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

interface Srv {
  type: 'msg'
  n: number
}
interface Cli {
  type: 'subscribe'
}

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })

describe('BidiFeed', () => {
  it('carries token in the URL, delivers typed events, sends client frames, and reconnects', () => {
    const hub = new FakeSocketHub()
    const got: Srv[] = []
    const feed = new BidiFeed<Srv, Cli>({
      baseUrl: 'ws://host',
      path: '/ws/comm',
      token: 't',
      onEvent: (e) => got.push(e),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    feed.start()
    expect(hub.last().url).toContain('/ws/comm?')
    expect(hub.last().url).toContain('token=t')

    hub.last().emitOpen()
    expect(feed.send({ type: 'subscribe' })).toBe(true)
    expect(hub.last().sent).toEqual(['{"type":"subscribe"}'])

    hub.last().emitMessage(JSON.stringify({ type: 'msg', n: 1 } satisfies Srv))
    hub.last().emitClose() // reconnect
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify({ type: 'msg', n: 2 } satisfies Srv))
    expect(got).toEqual([
      { type: 'msg', n: 1 },
      { type: 'msg', n: 2 },
    ])
  })

  it('runs inbound frames through the validate boundary — a throwing validator rejects the frame', () => {
    const hub = new FakeSocketHub()
    const got: Srv[] = []
    const feed = new BidiFeed<Srv, Cli>({
      baseUrl: 'ws://host',
      path: '/ws/comm',
      token: 't',
      onEvent: (e) => got.push(e),
      validate: (raw) => {
        const o = raw as Srv
        if (typeof o.n !== 'number') throw new Error('untrusted frame rejected')
        return o
      },
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    feed.start()
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify({ type: 'msg', n: 5 }))
    expect(() => hub.last().emitMessage(JSON.stringify({ type: 'msg' }))).toThrow(/rejected/) // no n → rejected
    expect(got).toEqual([{ type: 'msg', n: 5 }]) // the invalid frame was not delivered
  })
})
