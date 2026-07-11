// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent, within } from '@testing-library/react'
import { App } from './App'
import { useWindowStore } from './windowmgr/windowStore'
import { useHubStore } from './state/hubStore'
import { emptyHubState } from './state/hubReducers'
import { FakeSocketHub } from './net/testing/fakeSocket'
import { RestError } from './net/rest'
import type { HubConfig } from './state/hubConfig'
import type { HubRepo } from './state/restRepo'
import type { Agent, Channel } from './types/generated/contract'

const config: HubConfig = { apiBase: 'http://x', wsBase: 'ws://x', token: 'tok', operator: true }

const channels: Channel[] = [
  { id: 'po-frontend', name: 'PO ↔ FE', kind: 'DIRECT', members: ['po', 'frontend'] },
  { id: 'po-backend', name: 'PO ↔ BE', kind: 'DIRECT', members: ['po', 'backend'] },
]

// CYP-444: the roster (with roles) is the real source of the PO identity + agent list.
const roster: Agent[] = [
  { id: 'po', name: 'PO', role: 'PO', worktree: 'po' },
  { id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend' },
  { id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend' },
]

const fakeRepo = (): HubRepo => ({
  fetchAgents: vi.fn().mockResolvedValue(roster),
  fetchChannels: vi.fn().mockResolvedValue(channels),
  fetchAcl: vi.fn().mockResolvedValue([]),
  putAcl: vi.fn().mockResolvedValue({ channelId: '', agentId: '', canRead: false, canWrite: false }),
  requestMode: vi.fn().mockResolvedValue(undefined),
  getMessages: vi.fn().mockResolvedValue([]),
  postMessage: vi.fn().mockResolvedValue({ id: 'x', channelId: '', from: '', body: '', ts: 0 }),
  setLifecycle: vi.fn().mockResolvedValue({ agentId: 'backend', runState: 'RUNNING' }),
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

  it('a 403 on send shows the distinct "denied" disclosure, not the generic failure (CYP-437a)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.postMessage = vi.fn().mockRejectedValue(new RestError(403, 'POST', '/api/channels/po-frontend/messages', 'acl'))
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    // the agent windows also have composers → scope to the comm panel's input
    const input = within(await findByTestId('comm-panel')).getByTestId('composer-input')
    fireEvent.change(input, { target: { value: 'hi' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(await findByTestId('comm-send-denied')).toBeTruthy()
    expect(queryByTestId('comm-send-failed')).toBeNull()
  })

  it('an unexpected /ws/comm drop flips the banner off live: 1008 → revoked (+ composer lock), else offline (CYP-437b)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen() // → live
      comm.emitClose(1008) // policy violation → revoked (terminal)
    })
    expect(getByTestId('comm-status').className).toContain('comm-status-revoked')
    expect(await findByTestId('comm-revoked-lock')).toBeTruthy() // composer locked on revoke
  })

  it('each agent window carries a lifecycle header driven by the /ws/lifecycle feed (CYP-431)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    // before any lifecycle event → UNKNOWN
    expect((await findByTestId('lifecycle.status.backend')).textContent).toContain('Unbekannt')
    const feed = hub.sockets.find((s) => s.url.includes('/ws/lifecycle'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ agentId: 'backend', runState: 'RUNNING' }))
    })
    expect(getByTestId('lifecycle.status.backend').textContent).toContain('Aktiv') // feed drives state (non-optimistic)
  })

  it('an operator start posts the lifecycle request to the repo (CYP-431)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    const { findByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    fireEvent.click(await findByTestId('lifecycle.start.backend'))
    expect(repo.setLifecycle).toHaveBeenCalledWith('backend', 'start')
  })
})
