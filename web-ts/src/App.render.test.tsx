// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent, within } from '@testing-library/react'
import { App } from './App'
import { useWindowStore } from './windowmgr/windowStore'
import { useHubStore } from './state/hubStore'
import { emptyHubState } from './state/hubReducers'
import { useEventLogStore } from './eventlog/eventLogStore'
import { emptyEventLog } from './eventlog/eventLog'
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
  getApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***k999' }),
  putApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***new4' }),
  fetchAgentDetail: vi.fn().mockResolvedValue({ id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend', launch: 'bash', persona: null }),
  createAgent: vi.fn().mockResolvedValue(undefined),
  updateAgent: vi.fn().mockResolvedValue(undefined),
  removeAgent: vi.fn().mockResolvedValue(undefined),
  getRepoConfig: vi.fn().mockResolvedValue({ configured: true, url: 'git@github.com:org/repo.git', branch: 'main', reprovisionPending: false }),
  putRepoConfig: vi.fn().mockResolvedValue({ configured: true, url: 'git@github.com:org/repo.git', branch: 'main', reprovisionPending: false }),
  getReprovisionPreview: vi.fn().mockResolvedValue({ reprovisionPending: false, atRisk: [] }),
  getEvents: vi.fn().mockResolvedValue({ events: [], hasMore: false }),
  getConnectors: vi.fn().mockResolvedValue({ connectors: [{ kind: 'stream_json', capabilities: { structuredUsage: 'available', toolGranularity: 'available', reliableResult: 'available', rateLimitSignal: 'available', coordination: 'available', kind: 'stream_json' } }, { kind: 'mcp', capabilities: { structuredUsage: 'limited', toolGranularity: 'limited', reliableResult: 'limited', rateLimitSignal: 'unavailable', coordination: 'limited', kind: 'mcp' } }], default: 'stream_json' }),
  setConnector: vi.fn().mockResolvedValue(undefined),
  fetchReports: vi.fn().mockResolvedValue([]),
  generateReport: vi.fn().mockResolvedValue({ id: 'r1', type: 'status', generatedAt: 0, projectId: 'p', sources: ['events'], window: {}, sections: [] }),
  fetchAuthMe: vi.fn().mockResolvedValue({ authenticated: true, role: 'OPERATOR', verified: true }),
  getProjects: vi.fn().mockResolvedValue({ activeProjectId: 'team-1', projects: [] }),
  getCapacity: vi.fn().mockResolvedValue({ current: 2, estimatedMax: 6 }),
  getCompactStatus: vi.fn().mockResolvedValue({ allowed: false, thresholdTokens: 500000, armed: false, running: false }),
  setCompactConfig: vi.fn().mockResolvedValue(undefined),
  getWorkspaceMembers: vi.fn().mockResolvedValue([]),
  getOperatorAudit: vi.fn().mockResolvedValue([]),
  getClaudeMd: vi.fn().mockResolvedValue({ agentId: 'a', content: '', exists: false }),
  updateClaudeMd: vi.fn().mockResolvedValue({ agentId: 'a', content: '', exists: true, version: 'v1' }),
  createProject: vi.fn().mockResolvedValue({ id: 'p2', name: 'P2' }),
  switchProject: vi.fn().mockResolvedValue({ activeProjectId: 'p2', projects: [] }),
  renameProject: vi.fn().mockResolvedValue({ id: 'p1', name: 'renamed' }),
  deleteProject: vi.fn().mockResolvedValue({ projectId: 'p1', configRemoved: true, eventsRemoved: 0, worktreesRemoved: 0 }),
})

beforeEach(() => {
  // module-singleton stores persist across tests → reset the data (actions are kept by the merge).
  useWindowStore.setState({ windows: [], contentIds: new Set(), host: { width: 0, height: 0 } })
  useHubStore.setState({ ...emptyHubState })
  useEventLogStore.setState({ ...emptyEventLog, paused: false, pausedAtSeq: null })
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

  it('a rejected lifecycle action shows the lifecycle.error line + clears the spinner (CYP-445 §6)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.setLifecycle = vi.fn().mockRejectedValue(new RestError(409, 'POST', '/api/agents/backend/restart', 'transition'))
    const { findByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    fireEvent.click(await findByTestId('lifecycle.restart.backend')) // restart is operator-enabled in any state
    const err = await findByTestId('lifecycle.error.backend')
    expect(err.textContent).toContain('Übergang')
  })

  it('the Event window renders the /ws/events feed with a live marker (CYP-432)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(getByTestId('event-log')).toBeTruthy()
    const feed = hub.sockets.find((s) => s.url.includes('/ws/events'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ type: 'event', event: { id: 'e1', seq: 1, ts: 0, agentId: 'backend', projectId: 'p', type: 'agent.activity', severity: 'info' } }))
      feed.emitMessage(JSON.stringify({ type: 'caughtup' }))
    })
    expect(await findByTestId('event.row.e1')).toBeTruthy()
    expect(getByTestId('event-log-status').textContent).toContain('Live')
  })

  it('CYP-448: pausing the tail freezes it (pausedIndicator, no liveIndicator) + buffers the newer events', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const feed = hub.sockets.find((s) => s.url.includes('/ws/events'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ type: 'event', event: { id: 'p1', seq: 1, ts: 0, agentId: 'backend', projectId: 'p', type: 'tool.call', severity: 'info' } }))
      feed.emitMessage(JSON.stringify({ type: 'caughtup' }))
    })
    expect(await findByTestId('event-log-live')).toBeTruthy()
    // pause, then a new event arrives while frozen
    fireEvent.click(getByTestId('event-log-pause'))
    await act(async () => {
      feed.emitMessage(JSON.stringify({ type: 'event', event: { id: 'p2', seq: 2, ts: 1, agentId: 'backend', projectId: 'p', type: 'tool.result', severity: 'info' } }))
    })
    expect(getByTestId('event-log-paused')).toBeTruthy()
    expect(queryByTestId('event-log-live')).toBeNull() // frozen view is NEVER shown as live (spec §5.6)
    expect(queryByTestId('event.row.p2')).toBeNull() // the newer event is frozen out of view…
    expect(getByTestId('event-log-buffered').textContent).toContain('1 neue') // …and disclosed as buffered
    // resume → live again, the buffered event shows
    fireEvent.click(getByTestId('event-log-pause'))
    expect(await findByTestId('event.row.p2')).toBeTruthy()
    expect(getByTestId('event-log-live')).toBeTruthy()
  })

  it('CYP-432 fail-closed: a NON-operator gets NO event window and NEVER opens the /ws/events (bodies) socket', async () => {
    const hub = new FakeSocketHub()
    const { queryByTestId } = render(
      <App config={{ ...config, operator: false }} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(hub.sockets.find((s) => s.url.includes('/ws/events'))).toBeUndefined() // the bodies socket is never opened
    expect(queryByTestId('event-log')).toBeNull() // no event-log data surface for a non-operator
  })

  it('CYP-470: operatorOverride (from whoami) drives the operator gate, overriding cfg.operator', async () => {
    const hub = new FakeSocketHub()
    // config says operator:true, but whoami resolved MEMBER → the override wins → no operator-only event window.
    const { queryByTestId } = render(
      <App config={config} operatorOverride={false} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(hub.sockets.find((s) => s.url.includes('/ws/events'))).toBeUndefined()
    expect(queryByTestId('event-log')).toBeNull() // whoami=MEMBER → operator-gated surfaces closed despite cfg.operator=true
  })

  it('CYP-432 fail-closed: a 1008 on /ws/events locks the log (revoked placeholder) and does NOT reconnect', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const evSockets = () => hub.sockets.filter((s) => s.url.includes('/ws/events'))
    const before = evSockets().length
    await act(async () => {
      evSockets()[before - 1].emitOpen()
      evSockets()[before - 1].emitClose(1008) // access revoked
    })
    expect(await findByTestId('event-log-revoked')).toBeTruthy()
    expect(evSockets().length).toBe(before) // terminal — no reconnect after revoke
  })

  it('the Settings window renders the API-key panel with the fetched masked view (CYP-433)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(getByTestId('settings.section.apiKey')).toBeTruthy()
    expect((await findByTestId('settings.apiKey.masked')).textContent).toContain('***k999') // from getApiKey
  })

  it('an ERROR run-state shows the curated reason in its OWN node (CYP-446), fail-closed for none', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const feed = hub.sockets.find((s) => s.url.includes('/ws/lifecycle'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ agentId: 'backend', runState: 'ERROR', errorCode: 'CRASHED' }))
    })
    expect((await findByTestId('lifecycle.errorReason.backend')).textContent).toContain('abgestürzt')
  })

  it('a run-state feed event clears the transient lifecycle action-error (CYP-445-QA / CYP-446)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.setLifecycle = vi.fn().mockRejectedValue(new RestError(409, 'POST', '/api/agents/backend/restart', 'x'))
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    fireEvent.click(await findByTestId('lifecycle.restart.backend'))
    expect(await findByTestId('lifecycle.error.backend')).toBeTruthy() // reject notice shown
    const feed = hub.sockets.find((s) => s.url.includes('/ws/lifecycle'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ agentId: 'backend', runState: 'RUNNING' }))
    })
    expect(queryByTestId('lifecycle.error.backend')).toBeNull() // a confirmed state clears the stale reject
  })

  it('the Agent-Management window renders the roster with per-agent edit/remove (CYP-450)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(await findByTestId('agentMgmt.panel')).toBeTruthy()
    expect(getByTestId('agentMgmt.item.backend')).toBeTruthy()
    expect(getByTestId('agentMgmt.item.backend.remove')).toBeTruthy()
  })

  it('the Product-Lead window renders; an operator triggers the report list fetch (CYP-464)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(await findByTestId('productLead.panel')).toBeTruthy()
    expect(getByTestId('productLead.trigger')).toBeTruthy() // operator → trigger present
    expect(repo.fetchReports).toHaveBeenCalled()
  })

  it('the Settings window frames the repo section beside the API-key section (CYP-453)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(await findByTestId('settings.panel')).toBeTruthy()
    expect(getByTestId('settings.section.repo')).toBeTruthy() // CYP-453 repo section
    expect(getByTestId('settings.section.apiKey')).toBeTruthy() // framed CYP-433 section
    expect(getByTestId('settings.repo.url.input')).toBeTruthy()
  })

  it('CYP-452: an operator gets the Event-Browse window; the panel queries /api/events', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    const { findByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(await findByTestId('eventBrowse')).toBeTruthy()
    expect(repo.getEvents).toHaveBeenCalled() // the Browse panel ran its first-page query
  })

  it('CYP-452 leak-mount-gate: a NON-operator gets NO Event-Browse window and NEVER queries /api/events', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    const { queryByTestId } = render(
      <App config={{ ...config, operator: false }} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(queryByTestId('eventBrowse')).toBeNull() // omission — no bodies surface for a non-operator
    expect(repo.getEvents).not.toHaveBeenCalled() // and no /api/events query at all
  })
})
