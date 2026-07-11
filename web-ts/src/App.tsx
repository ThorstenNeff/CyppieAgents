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
import { readHubConfig, type HubConfig, type SocketDeps } from './state/hubConfig'
import { RestHubRepo, type HubRepo } from './state/restRepo'
import { commitAclChange } from './state/aclCommit'
import { startLiveHub } from './state/liveHub'
import { AgentWindow } from './AgentWindow'
import { AclPanel } from './comm/AclPanel'
import { CommPanel } from './comm/CommPanel'
import { loadHistorySize, browserStore } from './agentview/historySizePreference'
import type { AclDimension } from './comm/aclModel'
import type { SelectedView } from './agentview/terminalModeSelection'
import type { AclEntry, Message1 } from './types/generated/contract'

const AGENT_PREFIX = 'agent:'
const ACL_WINDOW_ID = 'acl'
const COMM_WINDOW_ID = 'comm'

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

  const setChannels = useHubStore((s) => s.setChannels)
  const setAcl = useHubStore((s) => s.setAcl)
  const onCommEvent = useHubStore((s) => s.onCommEvent)
  const onTerminalControl = useHubStore((s) => s.onTerminalControl)
  const markAclPending = useHubStore((s) => s.markAclPending)
  const clearAclPending = useHubStore((s) => s.clearAclPending)
  const ingestMessages = useHubStore((s) => s.ingestMessages)
  const setCommConnection = useHubStore((s) => s.setCommConnection)
  const [aclError, setAclError] = useState<string | null>(null)
  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null)
  const [commSendError, setCommSendError] = useState<string | null>(null)

  const channels = useHubStore((s) => s.channels)
  const agents = useHubStore((s) => s.agents)
  const aclEntries = useHubStore((s) => s.aclEntries)
  const pendingAcl = useHubStore((s) => s.pendingAcl)
  const terminalStateByAgent = useHubStore((s) => s.terminalStateByAgent)
  const messagesByChannel = useHubStore((s) => s.messagesByChannel)
  const commConnection = useHubStore((s) => s.commConnection)

  const historySize = useMemo(() => () => loadHistorySize(browserStore()), [])

  // Bootstrap: REST snapshot + the live-socket VM. Runs once; the VM stops on unmount.
  useEffect(() => {
    hubRepo.fetchChannels().then(setChannels).catch(() => undefined)
    hubRepo.fetchAcl().then(setAcl).catch(() => undefined)
    const live = startLiveHub(cfg, { onCommEvent, onTerminalControl, onCommOpen: () => setCommConnection('live') }, socketDeps)
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
  }, [agents])

  const onRequestMode = (agentId: string, mode: SelectedView) => {
    hubRepo.requestMode(agentId, mode === 'shell' ? 'TERMINAL' : 'ORCHESTRATION').catch(() => undefined)
  }

  const onSendComm = (text: string) => {
    if (selectedChannelId === null) return
    setCommSendError(null)
    // The posted message echoes back over /ws/comm too; ingest dedups by id, so folding the response is safe and
    // shows it immediately (server-authoritative, not optimistic client text).
    hubRepo
      .postMessage(selectedChannelId, text)
      .then((msg) => ingestMessages([msg]))
      .catch(() => setCommSendError('comm_send_failed'))
  }

  // PO identity for the reserved sender accent comes from explicit config, never a `po-<worker>` guess (CYP-426
  // will supply the typed roster's role). Everyone else falls to the hashed worker palette.
  const senderRole = (agentId: string): string | null => (cfg.poAgentId !== null && agentId === cfg.poAgentId ? 'PO' : null)

  const commitAcl = (entry: AclEntry, dims: readonly AclDimension[]) => {
    // CYP-435: on success the AclEvent echo flips + clears pending; on reject (409 lockout / any 4xx) there is no
    // echo, so commitAclChange clears the pending itself (else the switch spins forever) and surfaces the reason.
    void commitAclChange(hubRepo, { markPending: markAclPending, clearPending: clearAclPending, setError: setAclError }, entry, dims)
  }

  const renderContent = (win: WindowState) => {
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
          poAgentId={cfg.poAgentId}
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
