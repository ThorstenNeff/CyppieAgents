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
  getMessages: vi.fn().mockResolvedValue([]),
  postMessage: vi.fn().mockResolvedValue({ id: 'x', channelId: '', from: '', body: '', ts: 0 }),
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

  it('renders the Comm window with channels, folds fetched history, and shows a live message (CYP-438)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.getMessages = vi.fn().mockResolvedValue([{ id: 'hist1', channelId: 'po-frontend', from: 'po', body: 'history line', ts: 1 }])
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(getByTestId('comm-panel')).toBeTruthy()
    expect(getByTestId('comm.channel.po-frontend')).toBeTruthy()
    // history for the default-selected channel (po-frontend) is fetched + folded in
    expect(await findByTestId('comm.message.hist1')).toBeTruthy()
    // a live /ws/comm message on the selected channel appears too
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen()
      comm.emitMessage(JSON.stringify({ type: 'message', message: { id: 'live1', channelId: 'po-frontend', from: 'frontend', body: 'live line', ts: 2 } }))
    })
    expect(await findByTestId('comm.message.live1')).toBeTruthy()
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
