// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act } from '@testing-library/react'
import { App } from './App'
import { useWindowStore } from './windowmgr/windowStore'
import { useHubStore } from './state/hubStore'
import { emptyHubState } from './state/hubReducers'
import { FakeSocketHub } from './net/testing/fakeSocket'
import type { HubConfig } from './state/hubConfig'
import type { HubRepo } from './state/restRepo'
import type { Channel } from './types/generated/contract'

const config: HubConfig = { apiBase: 'http://x', wsBase: 'ws://x', token: 'tok', operator: true, poAgentId: 'po' }

const channels: Channel[] = [
  { id: 'po-frontend', name: 'PO ↔ FE', kind: 'DIRECT', members: ['po', 'frontend'] },
  { id: 'po-backend', name: 'PO ↔ BE', kind: 'DIRECT', members: ['po', 'backend'] },
]

const fakeRepo = (): HubRepo => ({
  fetchChannels: vi.fn().mockResolvedValue(channels),
  fetchAcl: vi.fn().mockResolvedValue([]),
  putAcl: vi.fn().mockResolvedValue({ channelId: '', agentId: '', canRead: false, canWrite: false }),
  requestMode: vi.fn().mockResolvedValue(undefined),
})

beforeEach(() => {
  // module-singleton stores persist across tests → reset the data (actions are kept by the merge).
  useWindowStore.setState({ windows: [], contentIds: new Set(), host: { width: 0, height: 0 } })
  useHubStore.setState({ ...emptyHubState })
})
afterEach(cleanup)

const flush = async () => {
  // let the fetch promises resolve and the windows effect run
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe('App assembly (CYP-425)', () => {
  it('opens one window per derived agent plus the ACL window', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    // agents = union of channel members = {po, frontend, backend}
    expect(await findByTestId('agent-window.po')).toBeTruthy()
    expect(getByTestId('agent-window.frontend')).toBeTruthy()
    expect(getByTestId('agent-window.backend')).toBeTruthy()
    expect(getByTestId('acl-panel')).toBeTruthy()
  })

  it('a live /ws/comm channels event that reveals a new agent opens a new window (VM → store → windows)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(queryByTestId('agent-window.qa')).toBeNull()
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen()
      comm.emitMessage(
        JSON.stringify({ type: 'channels', channels: [...channels, { id: 'po-qa', name: 'PO ↔ QA', kind: 'DIRECT', members: ['po', 'qa'] }] }),
      )
    })
    expect(await findByTestId('agent-window.qa')).toBeTruthy()
  })
})
