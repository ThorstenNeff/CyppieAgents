// CYP-814 Batch-2 G2 — the backoff→scheduler WIRING in ReconnectingSocket, previously structurally untestable (the fake
// scheduler discarded the `ms` arg). With the new delay-recording scheduler + emitError driver (fakeSocket.ts) we can now
// prove the reconnect delay COMES FROM backoff.next() (not a 0ms hot-loop against a dead server), that onopen RESETS the
// backoff (a flapping channel doesn't stay pinned at max), and that an error-then-close reconnects exactly once.
import { describe, it, expect } from 'vitest'
import { ReconnectingSocket } from './reconnectingSocket'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

const mkRs = (hub: FakeSocketHub, backoff: Backoff) =>
  new ReconnectingSocket({ url: () => 'ws://x', onText: () => {}, factory: hub.factory, schedule: hub.recordingSchedule, backoff })
const backoff = () => new Backoff({ initialMs: 100, maxMs: 1000, factor: 2 })

describe('CYP-814 G2 — the reconnect delay comes from backoff.next() (no hot-loop), and onopen resets the backoff', () => {
  it('★ an unexpected drop schedules the reconnect with backoff.next() — NOT a 0ms hot-loop (mutation next()→0 REDs)', () => {
    const hub = new FakeSocketHub()
    mkRs(hub, backoff()).connect()
    hub.sockets[0].emitClose(1006) // a transient drop
    expect(hub.delays).toEqual([100]) // scheduled with the backoff's first delay, not 0
    expect(hub.sockets.length).toBe(2) // and it did reconnect
  })

  it('★ successive drops (no success between) BACK OFF — the schedule keeps consulting next() (100 → 200)', () => {
    const hub = new FakeSocketHub()
    const rs = mkRs(hub, backoff())
    rs.connect()
    hub.sockets[0].emitClose(1006)
    hub.sockets[1].emitClose(1006)
    expect(hub.delays).toEqual([100, 200]) // growth proves the delay is backoff.next(), not a constant
  })

  it('★ onopen RESETS the backoff — a successful reconnect returns the NEXT drop to the initial delay (mutation: drop reset() REDs)', () => {
    const hub = new FakeSocketHub()
    const rs = mkRs(hub, backoff())
    rs.connect()
    hub.sockets[0].emitClose(1006) // delay 100 (backoff advances to 200)
    hub.sockets[1].emitOpen() // SUCCESS → backoff.reset() → back to initial
    hub.sockets[1].emitClose(1006) // delay 100 again, because it was reset
    expect(hub.delays).toEqual([100, 100]) // without reset() this is [100, 200]
  })

  it('★ error-then-close reconnects EXACTLY ONCE — an onerror must not itself schedule a (double) reconnect', () => {
    const hub = new FakeSocketHub()
    mkRs(hub, backoff()).connect()
    hub.sockets[0].emitError() // some WS impls fire error before close — error alone must NOT reconnect
    expect(hub.sockets.length).toBe(1)
    expect(hub.delays).toEqual([]) // nothing scheduled yet
    hub.sockets[0].emitClose(1006) // the close drives the single reconnect
    expect(hub.sockets.length).toBe(2)
    expect(hub.delays).toEqual([100]) // exactly one scheduled reconnect, not two
  })

  it('★ a 1008 (revoke) is TERMINAL — no reconnect, nothing scheduled (verified-good anchor, keeps G2 honest)', () => {
    const hub = new FakeSocketHub()
    mkRs(hub, backoff()).connect()
    hub.sockets[0].emitClose(1008)
    expect(hub.delays).toEqual([]) // terminal → never scheduled
    expect(hub.sockets.length).toBe(1) // never reconnected
  })
})
