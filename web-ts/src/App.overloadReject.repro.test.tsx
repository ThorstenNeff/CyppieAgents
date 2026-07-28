// @vitest-environment jsdom
// CYP-759 — repro for the overload-banner swallow (finding #4). A REAL server 503 `capacity_exceeded` is dropped
// on the floor when the last capacity snapshot still shows headroom, because `overloadVisible` self-clears on that
// stale snapshot (capacityModel.ts:39) and the reject path never refreshes it (App.tsx: refreshCapacity runs only on
// SUCCESS, :550/:599, never in a catch). Since estimatedMax is an ESTIMATE not an SLA, the server can reject below
// the estimate — exactly when the snapshot shows room — so a client-side estimate silently overrides a
// server-authoritative reject. The operator sees NOTHING.
//
// Case A is EXPECTED-RED against current develop (the swallow) and flips GREEN once the fix makes a server-confirmed
// active reject NOT self-clear on a snapshot older than the reject. Case B (full snapshot) is the positive control:
// it is GREEN today, proving the banner wiring works and the test can SEE it — so Case A's red is the swallow, not a
// broken fixture.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, act, fireEvent } from '@testing-library/react'
import { App } from './App'
import { useWindowStore } from './windowmgr/windowStore'
import { useHubStore } from './state/hubStore'
import { emptyHubState } from './state/hubReducers'
import { useEventLogStore } from './eventlog/eventLogStore'
import { emptyEventLog } from './eventlog/eventLog'
import { FakeSocketHub } from './net/testing/fakeSocket'
import { RestError } from './net/rest'
import { singleHubConfig, type HubConfig } from './state/hubConfig'
import type { HubRepo } from './state/restRepo'
import type { Agent, Channel, Capacity } from './types/generated/contract'

const config: HubConfig = singleHubConfig({ endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: 'tok', operator: true })
const channels: Channel[] = [{ id: 'po-backend', name: 'PO ↔ BE', kind: 'DIRECT', members: ['po', 'backend'] }]
const roster: Agent[] = [
  { id: 'po', name: 'PO', role: 'PO', worktree: 'po' },
  { id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend' },
]

// a real capacity_exceeded 503: restErrorCode() reads JSON.parse(body).error.code
const capacityReject = () =>
  new RestError(503, 'POST', '/api/agents/backend/start', JSON.stringify({ error: { code: 'capacity_exceeded' } }))

const fakeRepo = (): HubRepo => ({
  fetchAgents: vi.fn().mockResolvedValue(roster),
  fetchChannels: vi.fn().mockResolvedValue(channels),
  fetchAcl: vi.fn().mockResolvedValue([]),
  fetchReadState: vi.fn().mockResolvedValue([]),
  markRead: vi.fn((channelId: string, upToSeq: number) => Promise.resolve({ channelId, lastReadSeq: upToSeq, unreadCount: 0, hasUnreadMention: false })),
  putAcl: vi.fn().mockResolvedValue({ channelId: '', agentId: '', canRead: false, canWrite: false }),
  requestMode: vi.fn().mockResolvedValue(undefined),
  getMessages: vi.fn().mockResolvedValue([]),
  postMessage: vi.fn().mockResolvedValue({ message: { id: 'x', channelId: '', from: '', body: '', ts: 0 } }),
  editMessage: vi.fn().mockResolvedValue({ message: { id: 'x', channelId: '', from: '', body: '', ts: 0 } }),
  setLifecycle: vi.fn(), // set per case
  getApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***k999' }),
  putApiKey: vi.fn().mockResolvedValue({ set: true, masked: '***new4' }),
  fetchAgentDetail: vi.fn().mockResolvedValue({ id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend', launch: 'bash', persona: null }),
  createAgent: vi.fn().mockResolvedValue(undefined),
  updateAgent: vi.fn().mockResolvedValue(undefined),
  removeAgent: vi.fn().mockResolvedValue(undefined),
  getRepoConfig: vi.fn().mockResolvedValue({ configured: true, url: 'g', branch: 'main', reprovisionPending: false, cloneStatus: 'CLONED_OK' }),
  putRepoConfig: vi.fn().mockResolvedValue({ configured: true, url: 'g', branch: 'main', reprovisionPending: false }),
  getReprovisionPreview: vi.fn().mockResolvedValue({ reprovisionPending: false, atRisk: [] }),
  getEvents: vi.fn().mockResolvedValue({ events: [], hasMore: false }),
  getConnectors: vi.fn().mockResolvedValue({ connectors: [], default: 'stream_json' }),
  setConnector: vi.fn().mockResolvedValue(undefined),
  fetchReports: vi.fn().mockResolvedValue([]),
  generateReport: vi.fn().mockResolvedValue({ id: 'r1', type: 'status', generatedAt: 0, projectId: 'p', sources: [], window: {}, sections: [] }),
  fetchAuthMe: vi.fn().mockResolvedValue({ authenticated: true, role: 'OPERATOR', verified: true }),
  getProjects: vi.fn().mockResolvedValue({ activeProjectId: 'team-1', projects: [] }),
  getCapacity: vi.fn(), // set per case
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
  useWindowStore.setState({ windows: [], contentIds: new Set(), host: { width: 0, height: 0 } })
  useHubStore.setState({ ...emptyHubState })
  useEventLogStore.setState({ ...emptyEventLog, paused: false, pausedAtSeq: null })
})
afterEach(cleanup)

const flush = async () => {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

/** Mount App with a given capacity SNAPSHOT and a start that 503s capacity_exceeded, click start, settle. */
async function rejectAStartWith(snapshot: Capacity) {
  const hub = new FakeSocketHub()
  const repo = fakeRepo()
  repo.getCapacity = vi.fn().mockResolvedValue(snapshot)
  repo.setLifecycle = vi.fn().mockRejectedValue(capacityReject())
  const utils = render(<App config={config} repo={repo} socketDeps={{ factory: hub.factory, schedule: hub.runNow }} />)
  await flush() // roster + the capacity snapshot load
  await act(async () => {
    fireEvent.click(utils.getByTestId('lifecycle.start.backend'))
  })
  await flush() // the reject settles → noteCapacityReject → re-render
  return utils
}

describe('CYP-759 — a real capacity_exceeded 503 must raise the overload banner', () => {
  it('★ HEADROOM snapshot {2/6} + a 503 reject: the banner MUST show (RED now = the swallow)', async () => {
    const utils = await rejectAStartWith({ current: 2, estimatedMax: 6 })
    // the finding: overloadVisible self-clears on the stale headroom snapshot → banner absent. The fix makes the
    // server-confirmed reject outrank the client estimate.
    expect(utils.queryByTestId('overload-banner')).toBeTruthy()
  })

  it('positive control: FULL snapshot {6/6} + the same 503 → banner shows (GREEN now — wiring + visibility proven)', async () => {
    const utils = await rejectAStartWith({ current: 6, estimatedMax: 6 })
    // isFull → overloadVisible does NOT self-clear → the banner renders today. Proves Case A's red is the swallow,
    // not a broken fixture or an invisible marker.
    expect(utils.queryByTestId('overload-banner')).toBeTruthy()
  })
})
