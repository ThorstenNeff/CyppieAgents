// CYP-881 (DARK) — the ws-ticket seam on the SHARED reconnecting socket. Two invariants matter because this socket
// backs all 8 channels: (1) DARK by default — with NO ticketProvider the connect path is byte-unchanged (synchronous
// open, url() called with no ticket → the caller folds `?token=`); (2) flag-ON — connect() mints a FRESH single-use
// ticket per (re)connect (single-use-safe: a reused ticket on reconnect would 401), and a mint failure schedules a
// retry instead of killing the loop.
import { describe, it, expect } from 'vitest'
import { ReconnectingSocket } from './reconnectingSocket'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })
// flush a short chain of microtasks — the ticket path opens the socket inside provider().then(...)
const flush = async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

describe('CYP-881 — ReconnectingSocket ws-ticket seam', () => {
  it('★ DARK default (no ticketProvider): opens SYNCHRONOUSLY and calls url() with NO ticket', () => {
    // MUT: route the default path through the async provider branch (premature-flip) → the socket is created on a
    // microtask, so it would NOT exist synchronously here, and url() would receive a ticket arg → this reds.
    const hub = new FakeSocketHub()
    const seenTickets: (string | undefined)[] = []
    const rs = new ReconnectingSocket({
      url: (ticket) => {
        seenTickets.push(ticket)
        return `ws://host/ws/x?token=t${ticket !== undefined ? `&ticket=${ticket}` : ''}`
      },
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    expect(hub.sockets).toHaveLength(1) // synchronous — no await
    expect(seenTickets).toEqual([undefined]) // url() got NO ticket
    expect(hub.last().url).toBe('ws://host/ws/x?token=t') // byte-unchanged token path
  })

  it('★ flag ON: mints a FRESH ticket per (re)connect — the reconnect does NOT reuse the first ticket', async () => {
    // MUT: mint once and cache (remove the per-connect mint) → the 2nd socket carries ticket t1 again → this reds,
    // proving the mint runs on EVERY connect (single-use-safe).
    const hub = new FakeSocketHub()
    let n = 0
    const rs = new ReconnectingSocket({
      url: (ticket) => `ws://host/ws/comm?ticket=${ticket}`,
      ticketProvider: () => Promise.resolve(`t${++n}`),
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    await flush()
    expect(hub.sockets).toHaveLength(1)
    expect(hub.last().url).toBe('ws://host/ws/comm?ticket=t1')

    hub.last().emitOpen()
    hub.last().emitClose() // unexpected drop → reconnect → re-mint
    await flush()
    expect(hub.sockets).toHaveLength(2)
    expect(hub.last().url).toBe('ws://host/ws/comm?ticket=t2') // FRESH ticket, not the reused t1
  })

  it('★ flag ON: a mint FAILURE schedules a retry (the connect loop does not die silently)', async () => {
    // MUT: drop the rejection handler (only wire the fulfilled branch) → a mint failure would neither open a socket
    // NOR schedule a retry → the second attempt never happens → this reds.
    const hub = new FakeSocketHub()
    let attempts = 0
    const rs = new ReconnectingSocket({
      url: (ticket) => `ws://host/ws/comm?ticket=${ticket}`,
      ticketProvider: () => {
        attempts++
        return attempts === 1 ? Promise.reject(new Error('mint 500')) : Promise.resolve('ok')
      },
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    await flush()
    expect(attempts).toBeGreaterThanOrEqual(2) // first mint failed → a retry re-minted
    expect(hub.sockets).toHaveLength(1) // the retry succeeded and opened
    expect(hub.last().url).toBe('ws://host/ws/comm?ticket=ok')
  })

  it('★ flag ON: close() before the mint resolves does NOT open a stale socket', async () => {
    // MUT: drop the `!this.closed` guard in the fulfilled branch → a socket opens after teardown → this reds.
    const hub = new FakeSocketHub()
    const rs = new ReconnectingSocket({
      url: (ticket) => `ws://host/ws/comm?ticket=${ticket}`,
      ticketProvider: () => Promise.resolve('late'),
      onText: () => {},
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    rs.connect()
    rs.close() // tear down while the mint promise is still pending
    await flush()
    expect(hub.sockets).toHaveLength(0)
  })
})
