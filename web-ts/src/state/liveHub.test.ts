import { describe, it, expect, vi } from 'vitest'
import { startLiveHub } from './liveHub'
import type { HubConfig } from './hubConfig'
import { FakeSocketHub } from '../net/testing/fakeSocket'

const config: HubConfig = {
  apiBase: 'http://x',
  wsBase: 'ws://x',
  token: 'tok',
  operator: true,
}

const socketFor = (hub: FakeSocketHub, pathFragment: string) => {
  const s = hub.sockets.find((s) => s.url.includes(pathFragment))
  if (s === undefined) throw new Error(`no socket for ${pathFragment}`)
  return s
}

describe('startLiveHub (the live-socket VM, driven by fake sockets)', () => {
  it('opens /ws/comm and /ws/terminal-state with the token', () => {
    const hub = new FakeSocketHub()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
    expect(socketFor(hub, '/ws/comm').url).toContain('token=tok')
    expect(socketFor(hub, '/ws/terminal-state').url).toContain('token=tok')
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

  it('folds an inbound /ws/terminal-state event into onTerminalControl', () => {
    const hub = new FakeSocketHub()
    const onTerminalControl = vi.fn()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl }, { factory: hub.factory, schedule: hub.runNow })
    const term = socketFor(hub, '/ws/terminal-state')
    term.emitOpen()
    term.emitMessage(JSON.stringify({ agentId: 'backend', state: 'INTERACTIVE' }))
    expect(onTerminalControl).toHaveBeenCalledWith({ agentId: 'backend', state: 'INTERACTIVE' })
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

  it('folds an inbound /ws/lifecycle event into onRunState (CYP-431)', () => {
    const hub = new FakeSocketHub()
    const onRunState = vi.fn()
    startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn(), onRunState }, { factory: hub.factory, schedule: hub.runNow })
    const feed = socketFor(hub, '/ws/lifecycle')
    feed.emitOpen()
    feed.emitMessage(JSON.stringify({ agentId: 'backend', runState: 'RUNNING' }))
    expect(onRunState).toHaveBeenCalledWith({ agentId: 'backend', runState: 'RUNNING' })
  })

  it('stop() closes both sockets', () => {
    const hub = new FakeSocketHub()
    const handle = startLiveHub(config, { onCommEvent: vi.fn(), onTerminalControl: vi.fn() }, { factory: hub.factory, schedule: hub.runNow })
    handle.stop()
    expect(socketFor(hub, '/ws/comm').closed).toBe(true)
    expect(socketFor(hub, '/ws/terminal-state').closed).toBe(true)
  })
})
