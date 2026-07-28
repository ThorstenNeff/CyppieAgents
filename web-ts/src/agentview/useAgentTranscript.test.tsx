// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { useAgentTranscript } from './useAgentTranscript'
import { resetAgentVms } from './agentVmStore'
import { FakeSocketHub } from '../net/testing/fakeSocket'

afterEach(() => {
  cleanup()
  resetAgentVms() // CYP-890: the VM is now keep-alive in a module store — reset it so each test starts clean
})

describe('useAgentTranscript.send (CYP-425 — composer posts on the same /ws/agent socket)', () => {
  it('opens /ws/agent for the agent (cookie-auth, no token), and send posts a UserTurn', () => {
    const hub = new FakeSocketHub()
    const { result } = renderHook(() =>
      useAgentTranscript({ baseUrl: 'ws://x', agentId: 'backend', readyNoticeText: 'ready', factory: hub.factory, schedule: hub.runNow }),
    )
    const sock = hub.sockets.find((s) => s.url.includes('/ws/agent'))!
    expect(sock.url).toContain('agentId=backend')
    expect(sock.url).not.toContain('token=') // CYP-454: same-origin cookie authenticates /ws/agent, no query token
    sock.emitOpen()
    act(() => {
      result.current.send('hallo agent')
    })
    expect(sock.sent).toContain(JSON.stringify({ text: 'hallo agent' }))
  })
})
