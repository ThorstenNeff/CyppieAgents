import { describe, it, expect } from 'vitest'
import { ReconnectingSocket } from './reconnectingSocket'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })

describe('ReconnectingSocket', () => {
  it('delivers text frames and auto-reconnects on close', () => {
    const hub = new FakeSocketHub()
    const got: string[] = []
    const rs = new ReconnectingSocket({
      url: () => 'ws://host/ws/x',
      onText: (d) => got.push(d),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    expect(hub.sockets).toHaveLength(1)

    hub.last().emitOpen()
    hub.last().emitMessage('a')
    hub.last().emitClose() // → synchronous reconnect
    expect(hub.sockets).toHaveLength(2)
    hub.last().emitOpen()
    hub.last().emitMessage('b')

    expect(got).toEqual(['a', 'b'])
  })

  it('stops reconnecting after close()', () => {
    const hub = new FakeSocketHub()
    const rs = new ReconnectingSocket({
      url: () => 'ws://host/ws/x',
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    hub.last().emitOpen()
    rs.close()
    expect(hub.sockets).toHaveLength(1) // close() closed the live socket; no reconnect scheduled
  })

  it('send returns false while offline and true once open', () => {
    const hub = new FakeSocketHub()
    const rs = new ReconnectingSocket({
      url: () => 'ws://host/ws/x',
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    expect(rs.send('early')).toBe(false) // not open yet
    hub.last().emitOpen()
    expect(rs.send('now')).toBe(true)
    expect(hub.last().sent).toEqual(['now'])
  })
})
