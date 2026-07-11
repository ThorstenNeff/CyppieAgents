import { describe, it, expect } from 'vitest'
import {
  commSocket,
  eventsSocket,
  lifecycleFeed,
  tokenUsageFeed,
  busyStateFeed,
  terminalStateFeed,
  terminalSocket,
} from './channels'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

describe('channel clients — endpoints (CYP-400 W2-rest)', () => {
  it('wire each of the 7 channels to its path with ?token=; /ws/terminal is per-agent', () => {
    const hub = new FakeSocketHub()
    const base = {
      baseUrl: 'ws://host',
      token: 't',
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: new Backoff({ initialMs: 0, maxMs: 0, factor: 1 }),
      onEvent: () => {},
    }

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

    terminalSocket({ ...base, agentId: 'backend' }).start()
    expect(hub.last().url).toContain('/ws/terminal?')
    expect(hub.last().url).toContain('agentId=backend')
    expect(hub.last().url).toContain('token=t')
  })
})
