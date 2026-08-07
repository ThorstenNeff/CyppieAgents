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
import { setSetupSkipped } from './firstrun/skipPreference'
import { singleHubConfig, type HubConfig } from './state/hubConfig'
import type { HubRepo } from './state/restRepo'
import type { Agent, Channel } from './types/generated/contract'

const config: HubConfig = singleHubConfig({ endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: 'tok', operator: true })

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
  fetchReadState: vi.fn().mockResolvedValue([]),
  // realistic echo: the server answers about the channel it was asked about, with the cursor it advanced to.
  markRead: vi.fn((channelId: string, upToSeq: number) => Promise.resolve({ channelId, lastReadSeq: upToSeq, unreadCount: 0, hasUnreadMention: false })),
  putAcl: vi.fn().mockResolvedValue({ channelId: '', agentId: '', canRead: false, canWrite: false }),
  requestMode: vi.fn().mockResolvedValue(undefined),
  getMessages: vi.fn().mockResolvedValue([]),
  postMessage: vi.fn().mockResolvedValue({ message: { id: 'x', channelId: '', from: '', body: '', ts: 0 } }),
  editMessage: vi.fn().mockResolvedValue({ message: { id: 'x', channelId: '', from: '', body: '', ts: 0 } }),
  setLifecycle: vi.fn().mockResolvedValue({ agentId: 'backend', runState: 'RUNNING' }),
  getApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***k999' }),
  putApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***new4' }),
  fetchAgentDetail: vi.fn().mockResolvedValue({ id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend', launch: 'bash', persona: null }),
  createAgent: vi.fn().mockResolvedValue(undefined),
  updateAgent: vi.fn().mockResolvedValue(undefined),
  removeAgent: vi.fn().mockResolvedValue(undefined),
  // CYP-735 §3.3: a fixture that claims a FULLY set-up hub must now also say the clone succeeded — since CYP-736
  // exposed `cloneStatus`, "configured" alone means the URL was accepted, not that the repo is usable. Without
  // this the wizard correctly holds, which is the honest behaviour, not a test problem.
  getRepoConfig: vi.fn().mockResolvedValue({ configured: true, url: 'git@github.com:org/repo.git', branch: 'main', reprovisionPending: false, cloneStatus: 'CLONED_OK' }),
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
  setAvatarPreset: vi.fn().mockResolvedValue({ id: 'a', name: 'A', role: 'WORKER', worktree: 'a' }),
  uploadAvatar: vi.fn().mockResolvedValue({ id: 'a', name: 'A', role: 'WORKER', worktree: 'a', launch: 'bash' }),
  removeAvatar: vi.fn().mockResolvedValue(undefined),
  getChannelShare: vi.fn().mockResolvedValue({ shared: false }),
  shareChannel: vi.fn().mockResolvedValue({ shared: true }),
  unshareChannel: vi.fn().mockResolvedValue({ shared: false }),
  createChannel: vi.fn().mockResolvedValue({ id: 'c', name: 'C', kind: 'GROUP', members: [] }),
  renameChannel: vi.fn().mockResolvedValue({ id: 'c', name: 'C', kind: 'GROUP', members: [] }),
  archiveChannel: vi.fn().mockResolvedValue(undefined),
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

  it('★ CYP-891: selecting the Settings nav destination renders the SettingsPanel pane and leaves the canvas', async () => {
    // MUT: break the settings branch of renderDestinationPane (fall through to canvas) → the agent windows stay + the
    // settings pane never fills → this reds. Confirms the Settings destination end-to-end (rail item → pane).
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(getByTestId('agent-window.po')).toBeTruthy() // canvas is the default destination
    fireEvent.click(getByTestId('navRail.dest.settings'))
    await flush()
    expect(await findByTestId('nav-pane-settings')).toBeTruthy() // the settings destination fills the pane
    expect(getByTestId('settings.panel')).toBeTruthy()
    expect(queryByTestId('agent-window.po')).toBeNull() // the canvas (floating agent windows) is no longer mounted
  })

  it('CYP-662: `operator` (injected as a channel member) gets NO agent window — an ACL identity, not a spawnable agent', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    // the server injects `operator` into every spoke channel's members UNCONDITIONALLY (an auth/ACL participant); it
    // must NOT become an agent window (a dead phantom — /ws/agent?agentId=operator is rejected fail-closed).
    repo.fetchChannels = vi.fn().mockResolvedValue([{ id: 'po-frontend', name: 'PO ↔ FE', kind: 'DIRECT', members: ['po', 'frontend', 'operator'] }])
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    // real agents still get windows...
    expect(await findByTestId('agent-window.po')).toBeTruthy()
    expect(queryByTestId('agent-window.frontend')).toBeTruthy()
    // ...but the operator does NOT (mutation = remove the filter → an agent-window.operator appears → RED).
    expect(queryByTestId('agent-window.operator')).toBeNull()
  })

  it('renders the Comm window with channels, folds fetched history, and shows a live message (CYP-438)', async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.getMessages = vi.fn().mockResolvedValue([{ message: { id: 'hist1', channelId: 'po-frontend', from: 'po', body: 'history line', ts: 1 } }])
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
      comm.emitMessage(JSON.stringify({ type: 'message', delivered: { message: { id: 'live1', channelId: 'po-frontend', from: 'frontend', body: 'live line', ts: 2 } } }))
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

  it('★ CYP-845: a schema-skew frame delivered AFTER a 1008-revoke does NOT downgrade the banner (security event not masked)', async () => {
    // The race: an invalid /ws/comm frame buffered in transport, delivered AFTER auth was revoked. onCommSkew must
    // respect the first-terminal-cause latch — otherwise 'revoked'→'skew' masks the security revoke as a protocol
    // skew (honesty bug). MUT: revert onCommSkew to setCommConnection('skew') → the banner downgrades to skew → reds.
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen() // → live
      comm.emitClose(1008) // auth revoked → terminal 'revoked' (security)
      comm.emitMessage(JSON.stringify({ type: '__unknown_skew__' })) // valid JSON, unknown discriminant → schema-skew
    })
    expect(getByTestId('comm-status').className).toContain('comm-status-revoked') // STAYS revoked, not downgraded
    expect(await findByTestId('comm-revoked-lock')).toBeTruthy()
    expect(queryByTestId('comm-skew-lock')).toBeNull() // never masked as a mere protocol skew
  })

  it('each agent window carries a lifecycle header driven by the muxed /ws/status feed (CYP-431/CYP-844)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    // before any lifecycle event → UNKNOWN
    expect((await findByTestId('lifecycle.status.backend')).textContent).toContain('Unbekannt')
    const feed = hub.sockets.find((s) => s.url.includes('/ws/status'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
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
    const feed = hub.sockets.find((s) => s.url.includes('/ws/status'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'ERROR', errorCode: 'CRASHED' } }))
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
    const feed = hub.sockets.find((s) => s.url.includes('/ws/status'))!
    await act(async () => {
      feed.emitOpen()
      feed.emitMessage(JSON.stringify({ type: 'lifecycle', event: { agentId: 'backend', runState: 'RUNNING' } }))
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

// ── CYP-705 ⑥ (UIUX2 §9b) — read-state must survive a reconnect honestly ──────────────────────────────────────
describe('CYP-705 ⑥ — reconnect re-fetches the read-state', () => {
  it('★ a /ws/comm (re)open re-fetches read-state — stale counts must not be shown as current', () => {
    // While the socket was down the cursor may have advanced on another device. Keeping the counts we happen to
    // hold and presenting them as current is the same lie as a fabricated zero, only aged. The client re-ASKS
    // rather than trusting the server to resend after a gap the server cannot see.
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    const initial = (repo.fetchReadState as ReturnType<typeof vi.fn>).mock.calls.length
    expect(initial).toBeGreaterThan(0) // boot fetch happened at all (non-vacuous)
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    act(() => {
      comm.emitOpen()
    })
    expect((repo.fetchReadState as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(initial)
  })

  it('★ every subsequent reopen re-fetches too — not just the first reconnect', () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    act(() => {
      comm.emitOpen()
    })
    const afterFirst = (repo.fetchReadState as ReturnType<typeof vi.fn>).mock.calls.length
    act(() => {
      comm.emitOpen()
    })
    expect((repo.fetchReadState as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(afterFirst)
  })
})

// ── CYP-732 — the mark-read FOCUS GATE ───────────────────────────────────────────────────────────────────────
// NON-VACUITY NOTE (measured, not assumed): in vitest's jsdom `document.hasFocus()` is FALSE and
// `visibilityState` is 'visible'. So without explicitly simulating focus, the gate blocks by default and every
// "does not advance" assertion would pass while proving nothing. Each test below therefore establishes focus
// first, and the FIRST test is the positive control: it proves mark-read CAN fire in this harness at all.
describe('CYP-732 — the read cursor advances only when the conversation is in front of someone', () => {
  const setFocus = (opts: { visible: boolean; focused: boolean }) => {
    Object.defineProperty(document, 'visibilityState', { value: opts.visible ? 'visible' : 'hidden', configurable: true })
    document.hasFocus = () => opts.focused
  }

  /** Render with the comm window focused (the in-app half of the gate) and a message carrying a real seq. */
  const renderFocusedComm = async () => {
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.getMessages = vi.fn().mockResolvedValue([{ message: { id: 'm1', channelId: 'po-frontend', from: 'po', body: 'hi', ts: 1, seq: 5 } }])
    const view = render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    await flush()
    await act(async () => {
      useWindowStore.getState().focus('comm') // comm is the focused app window
      await Promise.resolve()
    })
    return { repo, view }
  }

  it('★ CONTROL: with tab visible, window focused and comm focused, mark-read DOES fire', async () => {
    setFocus({ visible: true, focused: true })
    const { repo } = await renderFocusedComm()
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0)
  })

  it('★ a HIDDEN tab never advances the cursor — the defect: read while you were away', async () => {
    setFocus({ visible: false, focused: true })
    const { repo } = await renderFocusedComm()
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0)
  })

  it('★ a visible tab whose WINDOW is not focused never advances — visible is not "being read"', async () => {
    setFocus({ visible: true, focused: false })
    const { repo } = await renderFocusedComm()
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0)
  })

  it('★ comm window not focused inside the app never advances, even with the browser focused', async () => {
    setFocus({ visible: true, focused: true })
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.getMessages = vi.fn().mockResolvedValue([{ message: { id: 'm1', channelId: 'po-frontend', from: 'po', body: 'hi', ts: 1, seq: 5 } }])
    render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    await flush()
    await act(async () => {
      useWindowStore.getState().focus('acl') // looking at a different window
      await Promise.resolve()
    })
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0)
  })

  it('★ a DISAGREEING server cannot cause an unbounded /read loop (found by a test that hung)', async () => {
    // The original guard only compared against the ECHO: if the server answers about a different channel — or
    // clamps the cursor below what we asked for — the guard never trips, and since the effect re-runs on every
    // unreadView change the client would hammer /read forever. The local ledger makes the request happen once per
    // (channel, upToSeq) regardless of the answer: we do not re-ask because we dislike the reply.
    setFocus({ visible: true, focused: true })
    const hub = new FakeSocketHub()
    const repo = fakeRepo()
    repo.getMessages = vi.fn().mockResolvedValue([{ message: { id: 'm1', channelId: 'po-frontend', from: 'po', body: 'hi', ts: 1, seq: 5 } }])
    // The disagreeing server: always answers about someone else's channel. The counter+cutoff matters — without
    // the ledger this loops unboundedly, and an unbounded loop WEDGES the runner instead of failing. A tooth that
    // hangs is a bad tooth (it looks like an infra flake, not a defect), so the double stops answering after a few
    // calls and the assertion below fails fast and legibly instead.
    let calls = 0
    repo.markRead = vi.fn(() => {
      calls += 1
      if (calls > 4) return Promise.reject(new Error('runaway /read loop — the request ledger is missing'))
      return Promise.resolve({ channelId: 'other', lastReadSeq: 99, unreadCount: 0, hasUnreadMention: false })
    })
    render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    await flush()
    await act(async () => {
      useWindowStore.getState().focus('comm')
      await Promise.resolve()
    })
    await flush()
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1) // asked once, not repeatedly
  })

  it('★ returning to the window advances what is now visible — the gate defers, it does not discard', async () => {
    setFocus({ visible: false, focused: false })
    const { repo } = await renderFocusedComm()
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0) // nothing while away
    await act(async () => {
      setFocus({ visible: true, focused: true })
      window.dispatchEvent(new Event('focus')) // the return
      await Promise.resolve()
    })
    await flush()
    expect((repo.markRead as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0)
  })
})

// ── CYP-733 — the CYP-676 tier disclosure, wired into the app chrome ─────────────────────────────────────────
describe('CYP-733 — the connection-security tier is always visible and never overstates', () => {
  it('★ the badge is present BEFORE any connection — fail-closed UNKNOWN, never absent, never native', async () => {
    // "Always visible" is the load-bearing property: an honesty indicator that only appears once things are fine
    // is not an honesty indicator. Absent would read as "nothing to disclose".
    const hub = new FakeSocketHub()
    const { getByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(getByTestId('remote.security.tierBadge')).toBeTruthy()
    expect(getByTestId('remote.security.tier.unknown')).toBeTruthy()
    expect(queryByTestId('remote.security.tier.native')).toBeNull() // a browser can never be native
  })

  it('★ a LIVE connection discloses BROWSER_GATEWAY openly — pill AND the always-visible disclosure line', async () => {
    const hub = new FakeSocketHub()
    const { getByTestId, queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen()
      await Promise.resolve()
    })
    expect(getByTestId('remote.security.tier.browserGateway')).toBeTruthy()
    expect(getByTestId('remote.security.tierDisclosure')).toBeTruthy() // never tap-to-reveal
    expect(queryByTestId('remote.security.tier.native')).toBeNull()
  })

  it('★ a dropped connection falls BACK to unknown — it never keeps claiming the old tier', async () => {
    // A tier describes a running connection. Holding the last-known tier after the socket dropped would describe
    // something that is no longer there — the same stale-as-current class as CYP-705 ⑥.
    const hub = new FakeSocketHub()
    const { getByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    const comm = hub.sockets.find((s) => s.url.includes('/ws/comm'))!
    await act(async () => {
      comm.emitOpen()
      await Promise.resolve()
    })
    expect(getByTestId('remote.security.tier.browserGateway')).toBeTruthy()
    await act(async () => {
      comm.emitClose(1006) // unexpected drop → offline
      await Promise.resolve()
    })
    expect(getByTestId('remote.security.tier.unknown')).toBeTruthy()
  })

  it('★ the disclosure appears ONLY for the gateway tier — unknown carries no gateway claim', async () => {
    const hub = new FakeSocketHub()
    const { queryByTestId } = render(
      <App config={config} repo={fakeRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(queryByTestId('remote.security.tierDisclosure')).toBeNull() // nothing to disclose about no connection
  })
})

// ── CYP-735 §3.1 — the unconfigured banner + agent-start gating ──────────────────────────────────────────────
describe('CYP-735 — an unset-up hub says so, and never guesses it from a failed load', () => {
  // The skip is a REMEMBERED preference (localStorage), so it leaks across tests in a shared jsdom unless cleared.
  // Found the hard way: a skip clicked in one test silently suppressed the gate in the next.
  beforeEach(() => {
    try {
      localStorage.clear()
    } catch {
      /* storage unavailable — nothing to clear */
    }
  })

  /** An unconfigured hub meets the WIZARD first; the banner belongs to the degraded (skipped) workspace. */
  const skipWizard = async (findByTestId: (id: string) => Promise<HTMLElement>) => {
    await act(async () => {
      ;(await findByTestId('firstrun.skip')).click()
      await Promise.resolve()
    })
  }

  const repo = (over: { configured?: boolean; reject?: boolean } = {}) => {
    const r = fakeRepo()
    r.getRepoConfig = vi.fn(() =>
      over.reject
        ? Promise.reject(new RestError(500, 'GET', '/api/config/repo', ''))
        : // a "configured" hub in these tests means fully set up, which since CYP-736 includes a successful clone
          Promise.resolve({ configured: over.configured ?? true, cloneStatus: (over.configured ?? true) ? ('CLONED_OK' as const) : ('NOT_CONFIGURED' as const) }),
    )
    return r
  }

  it('★ configured:false → banner + chip + the start button is DISABLED with a stated reason', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, getByTestId } = render(
      <App config={config} repo={repo({ configured: false })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    // Skipping the guidance must NEVER suppress the banner or the gating (UIUX2 condition (a)): guidance is
    // dismissable, the truth is not. Driving the real flow proves that rather than asserting it.
    await skipWizard(findByTestId)
    expect(await findByTestId('workspace.unconfiguredBanner')).toBeTruthy()
    expect(getByTestId('workspace.unconfiguredChip')).toBeTruthy()
    const start = getByTestId('lifecycle.start.po') as HTMLButtonElement
    expect(start.disabled).toBe(true) // present-but-disabled, never hidden
    expect(getByTestId('lifecycle.setupBlocked.po').textContent).toContain('nicht eingerichtet') // the reason travels
  })

  it('★ a FAILED config load shows NO setup prompt — it would tell a configured operator to configure', async () => {
    // The collapse this ticket exists to prevent: `config === null` after an error must not read as "not set up".
    const hub = new FakeSocketHub()
    const { queryByTestId, findByTestId } = render(
      <App config={config} repo={repo({ reject: true })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    // The gate shows its LOAD surface (error+retry) — never the steps, never "set up your hub".
    expect((await findByTestId('firstrun.gate')).dataset.mode).toBe('loading')
    expect(queryByTestId('firstrun.step.apikey')).toBeNull()
    expect(queryByTestId('workspace.unconfiguredBanner')).toBeNull()
  })

  it('★ configured but NOT yet cloned keeps the wizard up — an accepted URL is not a usable repo', async () => {
    // The §3.2/§3.3 seam: `configured:true` means the URL was ACCEPTED. Only CLONED_OK completes the repo step,
    // so the gate must stay active and show the live clone state instead of releasing the workspace.
    const hub = new FakeSocketHub()
    const r = fakeRepo()
    r.getRepoConfig = vi.fn().mockResolvedValue({ configured: true, url: 'u', branch: 'main', cloneStatus: 'CLONING' })
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={r} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect((await findByTestId('firstrun.gate')).dataset.mode).toBe('active') // not released
    expect(await findByTestId('firstrun.repo.cloneStatus.cloning')).toBeTruthy()
    expect(queryByTestId('firstrun.step.repo.status')?.dataset.state).not.toBe('done')
  })

  it('★ a FAILED clone also keeps the wizard up, with the reason and a retry', async () => {
    const hub = new FakeSocketHub()
    const r = fakeRepo()
    r.getRepoConfig = vi
      .fn()
      .mockResolvedValue({ configured: true, url: 'u', branch: 'main', cloneStatus: 'CLONE_FAILED', cloneFailReason: 'AUTH' })
    const { findByTestId } = render(<App config={config} repo={r} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
    await flush()
    expect((await findByTestId('firstrun.gate')).dataset.mode).toBe('active')
    expect(await findByTestId('firstrun.repo.cloneFailReason.auth')).toBeTruthy()
    expect(await findByTestId('firstrun.repo.cloneRetry')).toBeTruthy()
  })

  it('configured:true renders no banner and leaves start ungated (non-vacuous contrast)', async () => {
    const hub = new FakeSocketHub()
    const { queryByTestId, getByTestId } = render(
      <App config={config} repo={repo({ configured: true })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(queryByTestId('workspace.unconfiguredBanner')).toBeNull()
    expect((getByTestId('lifecycle.start.po') as HTMLButtonElement).disabled).toBe(false)
  })

  it('★ the banner is NOT dismissable — "not set up" is a standing condition, not a passing event', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, container } = render(
      <App config={config} repo={repo({ configured: false })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    await skipWizard(findByTestId)
    const banner = await findByTestId('workspace.unconfiguredBanner')
    expect(banner).toBeTruthy()
    expect(container.querySelector('[data-testid="workspace.unconfiguredBanner.dismiss"]')).toBeNull()
  })

  // CYP-758 — a config-load ERROR must not be swallowed by a prior skip (unknown/error→all-clear collapse). The
  // error gate has no skip button, so error∧skip is reached via a REMEMBERED skip (localStorage) from an earlier
  // session, then a failing config load — pre-set the preference to reproduce that exact state.
  it('★ error ∧ skipped → a standing config-error+retry cue is present (skip must NOT hide the error)', async () => {
    setSetupSkipped()
    const hub = new FakeSocketHub()
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo({ reject: true })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect(await findByTestId('workspace.setupError')).toBeTruthy() // the surface the skip used to swallow
    expect(await findByTestId('workspace.setupError.retry')).toBeTruthy() // …and it is actionable
    // DISTINCT signals, never conflated: this is "status not loadable", NOT the "hub not set up" banner (that one
    // is server-stated unconfigured, which a load error is precisely not).
    expect(queryByTestId('workspace.unconfiguredBanner')).toBeNull()
    // and it is NOT the blocking gate re-raised (that would re-nag the user who skipped).
    expect(queryByTestId('firstrun.gate')).toBeNull()
  })

  it('error ∧ NOT skipped → the gate carries the error; the workspace cue does not fire (no double-surface)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo({ reject: true })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    expect((await findByTestId('firstrun.gate')).dataset.mode).toBe('loading') // gate shows the load surface
    expect(queryByTestId('workspace.setupError')).toBeNull() // the degraded cue stays out of the way
  })

  it('★ unconfigured ∧ skipped → the error cue does NOT over-fire (the setup prompt stays its own signal)', async () => {
    const hub = new FakeSocketHub()
    const { findByTestId, queryByTestId } = render(
      <App config={config} repo={repo({ configured: false })} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    await skipWizard(findByTestId)
    expect(await findByTestId('workspace.unconfiguredBanner')).toBeTruthy() // the unconfigured signal
    expect(queryByTestId('workspace.setupError')).toBeNull() // …not the error one
  })
})

describe('CYP-759 — a real capacity reject is not swallowed by a stale (pre-reject) headroom snapshot', () => {
  const capacityRejectRepo = () => {
    const r = fakeRepo()
    // capacity snapshot has HEADROOM, fetched on mount BEFORE any reject — the stale estimate that used to swallow.
    r.getCapacity = vi.fn().mockResolvedValue({ current: 2, estimatedMax: 6 })
    // the server authoritatively rejects the spawn as over capacity (503 capacity_exceeded).
    r.setLifecycle = vi.fn().mockRejectedValue(
      new RestError(503, 'POST', '/api/agents/backend/start', JSON.stringify({ error: { code: 'capacity_exceeded' } })),
    )
    return r
  }

  it('★ headroom snapshot + capacity_exceeded reject → the overload banner SHOWS (stale estimate must not clear it)', async () => {
    // Case A: without the fix, overloadVisible self-cleared on the room-showing snapshot (fetched before the reject),
    // so the authoritative 503 vanished. The banner must stand until evidence NEWER than the reject shows headroom.
    const hub = new FakeSocketHub()
    const { findByTestId } = render(
      <App config={config} repo={capacityRejectRepo()} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />,
    )
    await flush()
    fireEvent.click(await findByTestId('lifecycle.start.backend'))
    await flush()
    expect(await findByTestId('overload-banner')).toBeTruthy()
  })
})
