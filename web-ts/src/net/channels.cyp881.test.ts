// CYP-881 (DARK) — the ws-ticket seam threaded through the channel constructors. Guards the DARK boundary at the seam
// that all 8 channels share: with NO ticketProvider (flag OFF / default) EVERY channel folds `?token=` byte-unchanged;
// with a ticketProvider wired (flag ON) the read feeds fold `?ticket=` and drop `?token=` (mutually exclusive). Both
// feed shapes (BidiFeed = comm/events/terminal, OneWayFeed = the muxed status) honor the seam.
import { describe, it, expect } from 'vitest'
import {
  commSocket,
  eventsSocket,
  lifecycleFeed,
  tokenUsageFeed,
  busyStateFeed,
  terminalStateFeed,
  statusFeed,
  terminalSocket,
} from './channels'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })
const flush = async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

describe('CYP-881 — ws-ticket seam through the channel constructors', () => {
  it('★ DARK by default: with NO ticketProvider, ALL 8 channels fold ?token= and NEVER ?ticket=', () => {
    // MUT: fold `?ticket=` on the default (no-provider) path → any channel gains a ticket / loses its token → this reds.
    // This is the byte-unchanged guard for the shared seam across every channel.
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: 't', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }

    commSocket(base).start()
    expect(hub.last().url).toBe('ws://host/ws/comm?token=t')
    eventsSocket(base).start()
    expect(hub.last().url).toBe('ws://host/ws/events?token=t')
    lifecycleFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/lifecycle?token=t')
    tokenUsageFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/token-usage?token=t')
    busyStateFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/busy-state?token=t')
    terminalStateFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/terminal-state?token=t')
    statusFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/status?token=t')
    terminalSocket({ ...base, agentId: 'backend' }).start()
    expect(hub.last().url).toBe('ws://host/ws/terminal?agentId=backend&token=t')

    for (const s of hub.sockets) expect(s.url).not.toContain('ticket=')
  })

  it('★ flag ON (ticketProvider wired): a BidiFeed channel folds ?ticket= and drops ?token=', async () => {
    // MUT: ignore ticketProvider in the feed url closure → the socket keeps `?token=` → this reds.
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: 't', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }
    commSocket({ ...base, ticketProvider: () => Promise.resolve('TKT') }).start()
    await flush()
    expect(hub.last().url).toBe('ws://host/ws/comm?ticket=TKT')
    expect(hub.last().url).not.toContain('token=')
  })

  it('★ flag ON (ticketProvider wired): a OneWayFeed channel folds ?ticket= and drops ?token=', async () => {
    // MUT: ignore ticketProvider in OneWayFeed → the muxed status feed keeps `?token=` → this reds.
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: 't', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }
    statusFeed({ ...base, ticketProvider: () => Promise.resolve('TKT') }).start()
    await flush()
    expect(hub.last().url).toBe('ws://host/ws/status?ticket=TKT')
    expect(hub.last().url).not.toContain('token=')
  })

  it('★ flag ON: a per-agent BidiFeed keeps its query (agentId) AND folds ?ticket=', async () => {
    // MUT: drop the query in the ticket branch → agentId would vanish → this reds (proves the fold preserves query keys).
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: 't', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }
    terminalSocket({ ...base, agentId: 'backend', ticketProvider: () => Promise.resolve('TKT') }).start()
    await flush()
    expect(hub.last().url).toBe('ws://host/ws/terminal?agentId=backend&ticket=TKT')
    expect(hub.last().url).not.toContain('token=')
  })
})
