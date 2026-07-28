// @vitest-environment jsdom
// CYP-890 (NR-3) — the LOAD-BEARING shared-VM tooth. The whole slice exists so a nav-destination switch (Canvas floating
// window ↔ fullscreen destination of the SAME agent) does NOT tear down + reconnect the socket or lose the ephemeral VM
// state. Here we drive that at the store/hook level: mounting the same agent twice shares ONE socket; unmounting does NOT
// close it; a re-mount reuses it (no reconnect) with the draft preserved. MUT (revert the hoist to a per-mount socket)
// → unmount closes + re-mount opens socket #2 + draft resets → every ★ below reds.
import { describe, it, expect, afterEach } from 'vitest'
import { renderHook, cleanup, act } from '@testing-library/react'
import { useAgentTranscript } from './useAgentTranscript'
import { resetAgentVms } from './agentVmStore'
import { FakeSocketHub } from '../net/testing/fakeSocket'

afterEach(() => {
  cleanup()
  resetAgentVms()
})

const opts = (hub: FakeSocketHub) => ({ baseUrl: 'ws://x', agentId: 'backend', readyNoticeText: 'ready', factory: hub.factory, schedule: hub.runNow })
const agentSock = (hub: FakeSocketHub) => hub.sockets.filter((s) => s.url.includes('/ws/agent'))

describe('CYP-890 — shared agent VM survives the destination switch', () => {
  it('★ unmount does NOT close the socket, and a re-mount reuses it (no reconnect) with the draft preserved', () => {
    const hub = new FakeSocketHub()
    const a = renderHook(() => useAgentTranscript(opts(hub)))
    expect(agentSock(hub).length).toBe(1) // one socket opened
    const sock = agentSock(hub)[0]
    act(() => a.result.current.setDraft('half-typed message'))
    expect(a.result.current.draft).toBe('half-typed message')

    // switch away → the floating window unmounts
    a.unmount()
    expect(sock.closed).toBe(false) // ★ keep-alive: NOT closed on unmount
    expect(agentSock(hub).length).toBe(1) // ★ no extra socket

    // switch back → the fullscreen destination mounts
    const b = renderHook(() => useAgentTranscript(opts(hub)))
    expect(agentSock(hub).length).toBe(1) // ★ STILL one socket — reused, no reconnect
    expect(b.result.current.draft).toBe('half-typed message') // ★ draft preserved across the switch
    expect(agentSock(hub)[0]).toBe(sock) // the very same socket instance
  })

  it('★ two concurrent render sites of the same agent share ONE socket (the shared VM)', () => {
    const hub = new FakeSocketHub()
    const a = renderHook(() => useAgentTranscript(opts(hub)))
    const b = renderHook(() => useAgentTranscript(opts(hub)))
    expect(agentSock(hub).length).toBe(1) // ★ one socket for both mounts, not two
    // a draft set through one site is visible to the other (same VM)
    act(() => a.result.current.setDraft('shared'))
    expect(b.result.current.draft).toBe('shared')
  })

  it('★ send after a re-mount posts on the SAME shared socket', () => {
    const hub = new FakeSocketHub()
    const a = renderHook(() => useAgentTranscript(opts(hub)))
    const sock = agentSock(hub)[0]
    sock.emitOpen()
    a.unmount()
    const b = renderHook(() => useAgentTranscript(opts(hub)))
    act(() => {
      b.result.current.send('after switch')
    })
    expect(sock.sent).toContain(JSON.stringify({ text: 'after switch' }))
  })
})
