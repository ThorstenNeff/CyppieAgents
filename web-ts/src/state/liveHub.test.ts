import { describe, it, expect, vi } from 'vitest'
import { startLiveHub } from './liveHub'
import { singleHubConfig, type HubConfig } from './hubConfig'
import { FakeSocketHub } from '../net/testing/fakeSocket'

const config: HubConfig = singleHubConfig({ endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: 'tok', operator: true })

const socketFor = (hub: FakeSocketHub, pathFragment: string) => {
  const s = hub.sockets.find((s) => s.url.includes(pathFragment))
  if (s === undefined) throw new Error(`no socket for ${pathFragment}`)
  return s
}
const maybeSocketFor = (hub: FakeSocketHub, pathFragment: string) => hub.sockets.find((s) => s.url.includes(pathFragment))

describe('startLiveHub (the live-socket VM, driven by fake sockets)', () => {
  it('opens /ws/comm and the muxed /ws/status with the token', () => {
    const hub = new FakeSocketHub()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
    expect(socketFor(hub, '/ws/comm').url).toContain('token=tok')
    expect(socketFor(hub, '/ws/status').url).toContain('token=tok')
  })

  it('folds an inbound /ws/comm event into onCommEvent (parsed, typed)', () => {
    const hub = new FakeSocketHub()
    const onCommEvent = vi.fn()
    startLiveHub(config, { onCommEvent, onTerminalControl: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
    const comm = socketFor(hub, '/ws/comm')
    comm.emitOpen()
    comm.emitMessage(JSON.stringify({ type: 'acl', entry: { channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: false } }))
    expect(onCommEvent).toHaveBeenCalledWith({ type: 'acl', entry: { channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: false } })
  })

  it('fires onCommOpen when /ws/comm (re)connects — drives the connection banner (CYP-438)', () => {
    const hub = new FakeSocketHub()
    const onCommOpen = vi.fn()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onCommOpen }, { factory: hub.factory, schedule: hub.runNow })
    socketFor(hub, '/ws/comm').emitOpen()
    expect(onCommOpen).toHaveBeenCalledTimes(1)
  })

  it('fires onCommClose(code) when /ws/comm drops unexpectedly (CYP-437 offline/revoked banner)', () => {
    const hub = new FakeSocketHub()
    const onCommClose = vi.fn()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onCommClose }, { factory: hub.factory, schedule: hub.runNow })
    const comm = socketFor(hub, '/ws/comm')
    comm.emitOpen()
    comm.emitClose(1008)
    expect(onCommClose).toHaveBeenCalledWith(1008)
  })

  it('folds an inbound /ws/events event into onEventsEvent (CYP-432)', () => {
    const hub = new FakeSocketHub()
    const onEventsEvent = vi.fn()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onEventsEvent }, { factory: hub.factory, schedule: hub.runNow })
    const feed = socketFor(hub, '/ws/events')
    feed.emitOpen()
    feed.emitMessage(JSON.stringify({ type: 'caughtup' }))
    expect(onEventsEvent).toHaveBeenCalledWith({ type: 'caughtup' })
  })

  it('stop() closes /ws/comm + /ws/status', () => {
    const hub = new FakeSocketHub()
    const handle = startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
    handle.stop()
    expect(socketFor(hub, '/ws/comm').closed).toBe(true)
    expect(socketFor(hub, '/ws/status').closed).toBe(true)
  })

  // ── CYP-844: the muxed /ws/status feed ─────────────────────────────────────────────────────────────────────────
  describe('CYP-844 — muxed /ws/status → the four per-kind reducers', () => {
    // Wire each StatusFrame variant and assert its UNWRAPPED `.event` reaches the SAME callback the legacy feed fed.
    // MUT: swap two case arms in dispatchStatus → the event lands on the wrong callback → these red.
    it('★ lifecycle frame → onRunState with the unwrapped event (CYP-431)', () => {
      const hub = new FakeSocketHub()
      const onRunState = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onRunState }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
      expect(onRunState).toHaveBeenCalledWith({ agentId: 'backend', runState: 'RUNNING' })
    })

    it('★ tokenUsage frame → onTokenUsage with the unwrapped event (CYP-641)', () => {
      const hub = new FakeSocketHub()
      const onTokenUsage = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onTokenUsage }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'tokenUsage', event: { agentId: 'backend', contextTokens: 4200 } }))
      expect(onTokenUsage).toHaveBeenCalledWith({ agentId: 'backend', contextTokens: 4200 })
    })

    it('★ busy frame → onBusyState with the unwrapped event (CYP-641)', () => {
      const hub = new FakeSocketHub()
      const onBusyState = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onBusyState }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'busy', event: { agentId: 'backend', busy: true } }))
      expect(onBusyState).toHaveBeenCalledWith({ agentId: 'backend', busy: true })
    })

    it('★ terminal frame → onTerminalControl with the unwrapped event', () => {
      const hub = new FakeSocketHub()
      const onTerminalControl = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'terminal', event: { agentId: 'backend', state: 'INTERACTIVE' } }))
      expect(onTerminalControl).toHaveBeenCalledWith({ agentId: 'backend', state: 'INTERACTIVE' })
    })

    it('★ a variant does NOT leak into another kind’s callback (discriminant is load-bearing)', () => {
      // MUT: route every frame to one callback (drop the discriminant) → a lifecycle frame would also hit onBusyState → reds.
      const hub = new FakeSocketHub()
      const onRunState = vi.fn()
      const onBusyState = vi.fn()
      const onTokenUsage = vi.fn()
      const onTerminalControl = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl, onRunState, onBusyState, onTokenUsage }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
      expect(onRunState).toHaveBeenCalledTimes(1)
      expect(onBusyState).not.toHaveBeenCalled()
      expect(onTokenUsage).not.toHaveBeenCalled()
      expect(onTerminalControl).not.toHaveBeenCalled()
    })

    it('★ preserves delivery order across a snapshot-then-deltas stream (same agentId, in order)', () => {
      // The mux must forward frames in the order received — the store folds snapshot then deltas → last write wins.
      // MUT: buffer/reorder frames → the recorded sequence changes → this reds.
      const hub = new FakeSocketHub()
      const seen: string[] = []
      const onRunState = vi.fn((e: { runState: string }) => seen.push(e.runState))
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onRunState }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
      status.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'STOPPED' } }))
      expect(seen).toEqual(['RUNNING', 'STOPPED'])
    })

    it('★ TRANSIENT skew: a schema-violating status frame is dropped, the channel stays open (no terminal, no reconnect kill)', () => {
      // This is the flagged/confirmed policy: /ws/status has NO onReject → a bad frame is a silent single-drop and the
      // NEXT valid frame still arrives (contrast /ws/comm CYP-834 terminal-skew). MUT: wire onReject/terminal-skew here
      // (narrow to comm's policy) → the socket would close after the bad frame and the follow-up would not arrive → reds.
      const hub = new FakeSocketHub()
      const onRunState = vi.fn()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onRunState }, { factory: hub.factory, schedule: hub.runNow })
      const status = socketFor(hub, '/ws/status')
      status.emitOpen()
      status.emitMessage(JSON.stringify({ type: 'nonsense', event: { agentId: 'backend' } })) // unknown discriminant → schema-invalid
      status.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
      expect(status.closed).toBe(false)
      expect(onRunState).toHaveBeenCalledWith({ agentId: 'backend', runState: 'RUNNING' })
    })

    it('★ replaces the four legacy feeds — client opens NONE of /ws/lifecycle,/ws/token-usage,/ws/busy-state,/ws/terminal-state', () => {
      // MUT: leave a legacy feed mounted alongside the mux → that path reappears → this reds (double-open regression).
      const hub = new FakeSocketHub()
      startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onRunState: vi.fn(), onBusyState: vi.fn(), onTokenUsage: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
      for (const legacy of ['/ws/lifecycle', '/ws/token-usage', '/ws/busy-state', '/ws/terminal-state']) {
        expect(maybeSocketFor(hub, legacy), `legacy feed ${legacy} must not be opened`).toBeUndefined()
      }
    })
  })
})
