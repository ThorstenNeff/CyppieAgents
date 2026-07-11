// CYP-425 (App-Assembly) — the running app: assembles the finished web-ts modules into a live desktop. On mount it
// loads the channel + ACL snapshot (REST), opens the live-socket VM (/ws/comm + /ws/terminal-state → hub store),
// and opens one window per agent plus the ACL window. Each agent window owns its own /ws/agent (+ optional
// /ws/terminal) socket; the hub store holds the shared comm/ACL/terminal-control state.
//
// The Comm window (CYP-438) renders CommPanel over the VM: live messages arrive via /ws/comm (deduped by id),
// channel history is fetched on select and folded in (dedup makes the overlap safe), and a send posts + folds the
// server message. Connection banner: connecting→live on the /ws/comm open (offline/revoked banner tones = CYP-437).
//
// INTERIM (flagged to coordinator): the agent roster + ACL columns are derived from channel membership and the PO
// identity comes from the explicit CYPPIE_PO_AGENT_ID config (never a `po-<worker>` guess) — both swap to the real
// typed roster when CYP-426 lands. Comm `canWrite` is left unknown (server enforces on POST; the revoked-composer
// lock is CYP-437).
import { useEffect, useMemo, useState } from 'react'
import { WindowHost } from './windowmgr/WindowHost'
import { WindowFrame } from './windowmgr/WindowFrame'
import { useWindowStore } from './windowmgr/windowStore'
import type { WindowState } from './windowmgr/windowState'
import { useHubStore } from './state/hubStore'
import { rosterPoAgentId } from './state/hubReducers'
import { readHubConfig, type HubConfig, type SocketDeps } from './state/hubConfig'
import { RestHubRepo, type HubRepo } from './state/restRepo'
import { RestError } from './net/rest'
import { commitAclChange } from './state/aclCommit'
import { startLiveHub } from './state/liveHub'
import { AgentWindow } from './AgentWindow'
import { AclPanel } from './comm/AclPanel'
import { CommPanel } from './comm/CommPanel'
import { EventLogView } from './eventlog/EventLogView'
import { useEventLogStore } from './eventlog/eventLogStore'
import { ApiKeyPanel } from './settings/ApiKeyPanel'
import { loadHistorySize, browserStore } from './agentview/historySizePreference'
import type { AclDimension } from './comm/aclModel'
import type { SelectedView } from './agentview/terminalModeSelection'
import { lifecycleRejectMessage } from './agentview/lifecycleStatus'
import type { LifecycleAction } from './state/hubReducers'
import type { AclEntry, ApiKeyView, Message1 } from './types/generated/contract'

const AGENT_PREFIX = 'agent:'
const ACL_WINDOW_ID = 'acl'
const COMM_WINDOW_ID = 'comm'
const EVENT_WINDOW_ID = 'events'
const SETTINGS_WINDOW_ID = 'settings'

const byTs = (a: Message1, b: Message1): number => a.ts - b.ts

/** Cascade layout for a freshly opened window (content floor: 320×303). */
function tiledWindow(id: string, title: string, index: number): WindowState {
  const col = index % 3
  const row = Math.floor(index / 3)
  return { id, title, x: 24 + col * 384, y: 24 + row * 344, width: 360, height: 320 }
}

export interface AppProps {
  config?: HubConfig
  repo?: HubRepo
  socketDeps?: SocketDeps
}

export function App({ config, repo, socketDeps }: AppProps = {}) {
  const cfg = config ?? readHubConfig()
  const hubRepo = repo ?? new RestHubRepo(cfg.apiBase)

  const setRoster = useHubStore((s) => s.setRoster)
  const setChannels = useHubStore((s) => s.setChannels)
  const setAcl = useHubStore((s) => s.setAcl)
  const onCommEvent = useHubStore((s) => s.onCommEvent)
  const onTerminalControl = useHubStore((s) => s.onTerminalControl)
  const markAclPending = useHubStore((s) => s.markAclPending)
  const clearAclPending = useHubStore((s) => s.clearAclPending)
  const ingestMessages = useHubStore((s) => s.ingestMessages)
  const setCommConnection = useHubStore((s) => s.setCommConnection)
  const onRunState = useHubStore((s) => s.onRunState)
  const markLifecyclePending = useHubStore((s) => s.markLifecyclePending)
  const clearLifecyclePending = useHubStore((s) => s.clearLifecyclePending)
  const [aclError, setAclError] = useState<string | null>(null)
  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null)
  const [commSendError, setCommSendError] = useState<string | null>(null)
  // CYP-433: the API-key MASKED view (never the plaintext — the server only ever sends {set, masked:"***last4"}).
  const [apiKeyView, setApiKeyView] = useState<ApiKeyView | null>(null)
  // CYP-445: per-agent transient lifecycle-action reject notice (separate from the agent's ERROR run-state).
  const [lifecycleError, setLifecycleError] = useState<ReadonlyMap<string, string>>(new Map())
  const setAgentLifecycleError = (agentId: string, message: string | null) =>
    setLifecycleError((prev) => {
      const next = new Map(prev)
      if (message === null) next.delete(agentId)
      else next.set(agentId, message)
      return next
    })

  const channels = useHubStore((s) => s.channels)
  const roster = useHubStore((s) => s.roster)
  const agents = useHubStore((s) => s.agents)
  const aclEntries = useHubStore((s) => s.aclEntries)
  const pendingAcl = useHubStore((s) => s.pendingAcl)
  const terminalStateByAgent = useHubStore((s) => s.terminalStateByAgent)
  const messagesByChannel = useHubStore((s) => s.messagesByChannel)
  const commConnection = useHubStore((s) => s.commConnection)
  const runStateByAgent = useHubStore((s) => s.runStateByAgent)
  const lifecyclePending = useHubStore((s) => s.lifecyclePending)

  // CYP-432: the event log is its own store (separate from the hub state). OPERATOR-ONLY: it carries message
  // bodies, so the whole surface (window + socket + data) is gated on cfg.operator — defence-in-depth, not just
  // the server tier (mirrors ShellGate/AclPanel; a W10 backstop if the proxy ever leaks the operator token).
  const onEventsEvent = useEventLogStore((s) => s.onEventsEvent)
  const onEventsClose = useEventLogStore((s) => s.onEventsClose)
  const eventLog = useEventLogStore((s) => s.events)
  const eventsCaughtUp = useEventLogStore((s) => s.caughtUp)
  const eventsAccessRevoked = useEventLogStore((s) => s.accessRevoked)

  const historySize = useMemo(() => () => loadHistorySize(browserStore()), [])
  // CYP-444: the PO identity is the roster's role==PO, not a config guess. Null until the roster loads (the W9
  // lockout advisory simply won't fire until we truly know who the PO is).
  const poAgentId = rosterPoAgentId(roster)

  // Bootstrap: REST snapshot + the live-socket VM. Runs once; the VM stops on unmount.
  useEffect(() => {
    hubRepo.fetchAgents().then(setRoster).catch(() => undefined)
    hubRepo.fetchChannels().then(setChannels).catch(() => undefined)
    hubRepo.fetchAcl().then(setAcl).catch(() => undefined)
    hubRepo.getApiKey().then(setApiKeyView).catch(() => undefined) // masked view; plaintext never comes back
    const live = startLiveHub(
      cfg,
      {
        onCommEvent,
        onTerminalControl,
        onCommOpen: () => setCommConnection('live'),
        // CYP-437(b): an unexpected drop flips the banner off 'live'; a 1008 (auth revoked) is terminal → 'revoked'.
        onCommClose: (code) => setCommConnection(code === 1008 ? 'revoked' : 'offline'),
        onRunState,
        // CYP-432 fail-closed: wire the /ws/events handlers ONLY for an operator → a non-operator never opens the
        // bodies-carrying socket (liveHub skips it when onEventsEvent is absent).
        onEventsEvent: cfg.operator ? onEventsEvent : undefined,
        onEventsClose: cfg.operator ? onEventsClose : undefined,
      },
      socketDeps,
    )
    return () => live.stop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Default the comm selection to the first channel once channels arrive.
  useEffect(() => {
    if (selectedChannelId === null && channels.length > 0) setSelectedChannelId(channels[0].id)
  }, [channels, selectedChannelId])

  // On channel select, load its ACL-filtered history and fold it in (deduped by id → safe to overlap with live).
  useEffect(() => {
    if (selectedChannelId === null) return
    setCommSendError(null)
    hubRepo.getMessages(selectedChannelId).then(ingestMessages).catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedChannelId])

  // Open one window per agent + the ACL window (idempotent: only adds windows not already present, so a live
  // channels update that reveals a new agent adds its window without disturbing the existing layout).
  useEffect(() => {
    const wm = useWindowStore.getState()
    const present = new Set(wm.windows.map((w) => w.id))
    let index = wm.windows.length
    for (const agentId of agents) {
      const id = `${AGENT_PREFIX}${agentId}`
      if (!present.has(id)) wm.add(tiledWindow(id, agentId, index++), true)
    }
    if (agents.length > 0 && !present.has(COMM_WINDOW_ID)) wm.add(tiledWindow(COMM_WINDOW_ID, 'Kommunikation', index++), true)
    if (agents.length > 0 && !present.has(ACL_WINDOW_ID)) wm.add(tiledWindow(ACL_WINDOW_ID, 'Zugriffsrechte (ACL)', index++), false)
    // CYP-432: the event log is OPERATOR-ONLY — a non-operator gets no event window at all (no bodies surface).
    if (agents.length > 0 && cfg.operator && !present.has(EVENT_WINDOW_ID)) wm.add(tiledWindow(EVENT_WINDOW_ID, 'Ereignis-Protokoll', index++), true)
    // CYP-433: the settings/API-key window is present for EVERYONE (present-but-disabled) — the masked status leaks
    // nothing; the panel gates editing on operator internally.
    if (agents.length > 0 && !present.has(SETTINGS_WINDOW_ID)) wm.add(tiledWindow(SETTINGS_WINDOW_ID, 'Einstellungen', index++), false)
  }, [agents, cfg.operator])

  const onRequestMode = (agentId: string, mode: SelectedView) => {
    hubRepo.requestMode(agentId, mode === 'shell' ? 'TERMINAL' : 'ORCHESTRATION').catch(() => undefined)
  }

  // CYP-431: non-optimistic lifecycle. The click marks a transient pending; the run-state flips only on the
  // server's AgentRunStateEvent (the POST response, mirrored by /ws/lifecycle) — both resolve the pending. A
  // rejected request clears the pending (no event will come) so the transient label can't stick.
  const onLifecycle = (agentId: string, action: LifecycleAction) => {
    setAgentLifecycleError(agentId, null) // clear any prior reject notice for this agent
    markLifecyclePending(agentId, action)
    hubRepo
      .setLifecycle(agentId, action)
      .then(onRunState)
      // CYP-445 §6: a rejected action clears the pending (no feed event will come) AND surfaces why (409/503/…).
      .catch((err) => {
        clearLifecyclePending(agentId)
        setAgentLifecycleError(agentId, lifecycleRejectMessage(err))
      })
  }

  const onSendComm = (text: string) => {
    if (selectedChannelId === null) return
    setCommSendError(null)
    // The posted message echoes back over /ws/comm too; ingest dedups by id, so folding the response is safe and
    // shows it immediately (server-authoritative, not optimistic client text).
    hubRepo
      .postMessage(selectedChannelId, text)
      .then((msg) => ingestMessages([msg]))
      // CYP-437(a): a 403 is an ACL denial → the distinct "denied" disclosure (like CYP-435's 409 for ACL PUT),
      // not the generic failure. Anything else stays the generic retryable failure.
      .catch((err) => setCommSendError(err instanceof RestError && err.status === 403 ? 'comm_send_denied' : 'comm_send_failed'))
  }

  // PO identity for the reserved sender accent comes from explicit config, never a `po-<worker>` guess (CYP-426
  // will supply the typed roster's role). Everyone else falls to the hashed worker palette.
  const senderRole = (agentId: string): string | null => (poAgentId !== null && agentId === poAgentId ? 'PO' : null)

  const commitAcl = (entry: AclEntry, dims: readonly AclDimension[]) => {
    // CYP-435: on success the AclEvent echo flips + clears pending; on reject (409 lockout / any 4xx) there is no
    // echo, so commitAclChange clears the pending itself (else the switch spins forever) and surfaces the reason.
    void commitAclChange(hubRepo, { markPending: markAclPending, clearPending: clearAclPending, setError: setAclError }, entry, dims)
  }

  // CYP-433 write-only save: send the plaintext up, keep only the MASKED view the server returns. The plaintext
  // lives only in this call's argument (the panel's transient input) — never stored, never logged. Rejects surface
  // in the panel as a GENERIC message (never the value).
  const onSaveApiKey = (apiKey: string): Promise<void> => hubRepo.putApiKey(apiKey).then((v) => setApiKeyView(v))

  const renderContent = (win: WindowState) => {
    if (win.id === SETTINGS_WINDOW_ID) {
      // present-but-disabled (NOT omitted, unlike the event log): the masked status leaks nothing, so the screen is
      // shown to everyone; ApiKeyPanel disables the inputs for a non-operator and shows the gate hint.
      return <ApiKeyPanel view={apiKeyView} operator={cfg.operator} onSave={onSaveApiKey} />
    }
    if (win.id === EVENT_WINDOW_ID) {
      // CYP-432 defence-in-depth: never render bodies for a non-operator (even if a window somehow exists), and
      // fail closed to a locked placeholder when access was revoked (WS 1008) — never leave stale bodies showing.
      if (!cfg.operator) {
        return (
          <p className="event-log-operator-only" data-testid="event-log-operator-only">
            Das Ereignis-Protokoll ist nur für Operatoren verfügbar.
          </p>
        )
      }
      if (eventsAccessRevoked) {
        return (
          <p className="event-log-revoked" role="alert" data-testid="event-log-revoked">
            Zugriff entzogen — das Ereignis-Protokoll ist gesperrt.
          </p>
        )
      }
      return <EventLogView events={eventLog} caughtUp={eventsCaughtUp} />
    }
    if (win.id === COMM_WINDOW_ID) {
      const messages = [...(messagesByChannel.get(selectedChannelId ?? '') ?? [])].sort(byTs)
      return (
        <CommPanel
          channels={channels}
          selectedChannelId={selectedChannelId}
          onSelectChannel={setSelectedChannelId}
          messages={messages}
          senderRole={senderRole}
          connection={commConnection}
          canWrite={null}
          sendError={commSendError}
          onSend={onSendComm}
          historySize={historySize}
        />
      )
    }
    if (win.id === ACL_WINDOW_ID) {
      return (
        <AclPanel
          channels={channels}
          agents={agents}
          entries={aclEntries}
          pending={pendingAcl}
          poAgentId={poAgentId}
          operator={cfg.operator}
          onCommit={commitAcl}
          error={aclError}
        />
      )
    }
    if (win.id.startsWith(AGENT_PREFIX)) {
      const agentId = win.id.slice(AGENT_PREFIX.length)
      return (
        <AgentWindow
          agentId={agentId}
          wsBase={cfg.wsBase}
          token={cfg.token}
          operator={cfg.operator}
          terminalState={terminalStateByAgent.get(agentId) ?? 'MEDIATED'}
          onRequestMode={onRequestMode}
          lifecycleState={runStateByAgent.get(agentId) ?? 'UNKNOWN'}
          lifecyclePending={lifecyclePending.get(agentId)}
          lifecycleError={lifecycleError.get(agentId) ?? null}
          onLifecycle={onLifecycle}
          socketDeps={socketDeps}
        />
      )
    }
    return null
  }

  return (
    <div className="app-root" data-testid="app-root">
      <WindowHost>{(win) => <WindowFrame window={win}>{renderContent(win)}</WindowFrame>}</WindowHost>
    </div>
  )
}
