// CYP-881 (DARK) — the flag-gated wiring of the ws-ticket seam into the live read feeds. startLiveHub decides, from the
// CYPPIE_WS_TICKET global, whether the READ feeds (comm/status/events) mint a per-connect ticket against THIS hub's HTTP
// apiBase (not the wsBase) and fold `?ticket=`. Flag OFF (default) → byte-unchanged `?token=`, the mint is never called.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { startLiveHub } from './liveHub'
import { singleHubConfig, type HubConfig } from './hubConfig'
import { FakeSocketHub } from '../net/testing/fakeSocket'

const config: HubConfig = singleHubConfig({ endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: 'tok', operator: true })

const socketFor = (hub: FakeSocketHub, pathFragment: string) => {
  const s = hub.sockets.find((s) => s.url.includes(pathFragment))
  if (s === undefined) throw new Error(`no socket for ${pathFragment}`)
  return s
}
const flush = async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}
const setFlag = (on: boolean) => {
  ;(globalThis as { CYPPIE_WS_TICKET?: unknown }).CYPPIE_WS_TICKET = on
}

afterEach(() => {
  delete (globalThis as { CYPPIE_WS_TICKET?: unknown }).CYPPIE_WS_TICKET
})

describe('CYP-881 — startLiveHub ws-ticket flag gating', () => {
  it('★ flag OFF (default): the mint is NEVER called and every read feed folds ?token= (byte-unchanged)', () => {
    // MUT: drop the wsTicketEnabled() guard (always provide a ticketProvider) → the mint fires + `?ticket=` appears → reds.
    const hub = new FakeSocketHub()
    const mintTicket = vi.fn(() => Promise.resolve('TKT'))
    startLiveHub(
      config,
      { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onEventsEvent: vi.fn() },
      { factory: hub.factory, schedule: hub.runNow, mintTicket },
    )
    expect(mintTicket).not.toHaveBeenCalled()
    for (const p of ['/ws/comm', '/ws/status', '/ws/events']) {
      expect(socketFor(hub, p).url).toContain('token=tok')
      expect(socketFor(hub, p).url).not.toContain('ticket=')
    }
  })

  it('★ flag ON: the read feeds mint against the hub HTTP apiBase and fold ?ticket= (dropping ?token=)', async () => {
    // MUT: bind the mint to config.wsBase instead of apiBase → mint called with "ws://x" → the apiBase assertion reds.
    // MUT: ignore the flag → `?token=` survives → the ticket assertions red.
    setFlag(true)
    const hub = new FakeSocketHub()
    const mintTicket = vi.fn(() => Promise.resolve('TKT'))
    startLiveHub(
      config,
      { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onEventsEvent: vi.fn() },
      { factory: hub.factory, schedule: hub.runNow, mintTicket },
    )
    await flush()
    expect(mintTicket).toHaveBeenCalledWith('http://x') // the HTTP apiBase, NOT the wsBase
    for (const p of ['/ws/comm', '/ws/status', '/ws/events']) {
      expect(socketFor(hub, p).url).toContain('ticket=TKT')
      expect(socketFor(hub, p).url).not.toContain('token=')
    }
  })
})
