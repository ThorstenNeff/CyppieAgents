// CYP-425 (App-Assembly) — the running app: assembles the finished web-ts modules into a live desktop. On mount it
// loads the channel + ACL snapshot (REST), opens the live-socket VM (/ws/comm + /ws/terminal-state → hub store),
// and opens one window per agent plus the ACL window. Each agent window owns its own /ws/agent (+ optional
// /ws/terminal) socket; the hub store holds the shared comm/ACL/terminal-control state.
//
// INTERIM (flagged to coordinator): the agent roster + ACL columns are derived from channel membership and the PO
// identity comes from the explicit CYPPIE_PO_AGENT_ID config (never a `po-<worker>` guess) — both swap to the real
// typed roster when CYP-426 lands. The Comm timeline (CommPanel) integrates when CYP-424 merges; the VM already
// keeps + dedups messages, so that is a render, not a re-plumb.
import { useEffect } from 'react'
import { WindowHost } from './windowmgr/WindowHost'
import { WindowFrame } from './windowmgr/WindowFrame'
import { useWindowStore } from './windowmgr/windowStore'
import type { WindowState } from './windowmgr/windowState'
import { useHubStore } from './state/hubStore'
import { readHubConfig, type HubConfig, type SocketDeps } from './state/hubConfig'
import { RestHubRepo, type HubRepo } from './state/restRepo'
import { startLiveHub } from './state/liveHub'
import { AgentWindow } from './AgentWindow'
import { AclPanel } from './comm/AclPanel'
import type { AclDimension } from './comm/aclModel'
import type { SelectedView } from './agentview/terminalModeSelection'
import type { AclEntry } from './types/generated/contract'

const AGENT_PREFIX = 'agent:'
const ACL_WINDOW_ID = 'acl'

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

  const channels = useHubStore((s) => s.channels)
  const agents = useHubStore((s) => s.agents)
  const aclEntries = useHubStore((s) => s.aclEntries)
  const pendingAcl = useHubStore((s) => s.pendingAcl)
  const terminalStateByAgent = useHubStore((s) => s.terminalStateByAgent)

  // Bootstrap: REST snapshot + the live-socket VM. Runs once; the VM stops on unmount.
  useEffect(() => {
    hubRepo.fetchChannels().then(setChannels).catch(() => undefined)
    hubRepo.fetchAcl().then(setAcl).catch(() => undefined)
    const live = startLiveHub(cfg, { onCommEvent, onTerminalControl }, socketDeps)
    return () => live.stop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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
    if (agents.length > 0 && !present.has(ACL_WINDOW_ID)) wm.add(tiledWindow(ACL_WINDOW_ID, 'Zugriffsrechte (ACL)', index++), false)
  }, [agents])

  const onRequestMode = (agentId: string, mode: SelectedView) => {
    hubRepo.requestMode(agentId, mode === 'shell' ? 'TERMINAL' : 'ORCHESTRATION').catch(() => undefined)
  }

  const commitAcl = (entry: AclEntry, dims: readonly AclDimension[]) => {
    for (const dim of dims) markAclPending(entry.channelId, entry.agentId, dim, dim === 'read' ? entry.canRead : entry.canWrite)
    hubRepo.putAcl(entry).catch(() => undefined) // the enforced flip arrives as an AclEvent echo (non-optimistic)
  }

  const renderContent = (win: WindowState) => {
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
