// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { useAgentTranscript } from './useAgentTranscript'
import { FakeSocketHub } from '../net/testing/fakeSocket'

afterEach(cleanup)

describe('useAgentTranscript.send (CYP-425 — composer posts on the same /ws/agent socket)', () => {
  it('opens /ws/agent for the agent with the token, and send posts a UserTurn', () => {
    const hub = new FakeSocketHub()
    const { result } = renderHook(() =>
      useAgentTranscript({ baseUrl: 'ws://x', agentId: 'backend', token: 'tok', readyNoticeText: 'ready', factory: hub.factory, schedule: hub.runNow }),
    )
    const sock = hub.sockets.find((s) => s.url.includes('/ws/agent'))!
    expect(sock.url).toContain('agentId=backend')
    expect(sock.url).toContain('token=tok')
    sock.emitOpen()
    act(() => {
      result.current.send('hallo agent')
    })
    expect(sock.sent).toContain(JSON.stringify({ text: 'hallo agent' }))
  })
})
