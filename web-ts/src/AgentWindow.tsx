// CYP-425 (App-Assembly) — the per-agent window body: the [Orchestrierung | Shell] toggle over the two RETAINED
// content views (ContentViewSwitch keeps both mounted, §W8.2). Orchestration = the structured /ws/agent transcript
// + the composer (send on the same socket). Shell = the operator-gated xterm (ShellGate mounts XtermView only after
// the open-warning). The toggle is non-optimistic: `active` follows the server-confirmed terminal-control state.
import { useMemo } from 'react'
import { ModeToggle } from './agentview/ModeToggle'
import { ContentViewSwitch } from './agentview/ContentViewSwitch'
import { AgentTranscript } from './agentview/AgentTranscript'
import { Composer } from './agentview/Composer'
import { terminalModeSelection, type TerminalControlState, type SelectedView } from './agentview/terminalModeSelection'
import { useAgentTranscript } from './agentview/useAgentTranscript'
import { loadHistorySize, browserStore } from './agentview/historySizePreference'
import { LifecycleHeader } from './agentview/LifecycleHeader'
import type { LifecycleState } from './agentview/lifecycleStatus'
import { ShellGate } from './terminal/ShellGate'
import { XtermView } from './terminal/XtermView'
import type { SocketDeps } from './state/hubConfig'
import type { LifecycleAction } from './state/hubReducers'

/** The localized agent-ready line (parity with the Kotlin default; web-ts has no i18n yet). */
const READY_NOTICE = 'Agent bereit'

export interface AgentWindowProps {
  agentId: string
  wsBase: string
  token: string
  operator: boolean
  terminalState: TerminalControlState
  onRequestMode: (agentId: string, mode: SelectedView) => void
  lifecycleState: LifecycleState
  lifecyclePending: LifecycleAction | undefined
  onLifecycle: (agentId: string, action: LifecycleAction) => void
  socketDeps?: SocketDeps
}

export function AgentWindow({
  agentId,
  wsBase,
  token,
  operator,
  terminalState,
  onRequestMode,
  lifecycleState,
  lifecyclePending,
  onLifecycle,
  socketDeps,
}: AgentWindowProps) {
  const { rows, send } = useAgentTranscript({
    baseUrl: wsBase,
    agentId,
    token,
    readyNoticeText: READY_NOTICE,
    factory: socketDeps?.factory,
    schedule: socketDeps?.schedule,
  })
  const active = terminalModeSelection(terminalState).selected
  const historySize = useMemo(() => () => loadHistorySize(browserStore()), [])

  return (
    <div className="agent-window" data-testid={`agent-window.${agentId}`}>
      <LifecycleHeader
        agentId={agentId}
        state={lifecycleState}
        pending={lifecyclePending}
        operator={operator}
        onStart={(id) => onLifecycle(id, 'start')}
        onStop={(id) => onLifecycle(id, 'stop')}
        onRestart={(id) => onLifecycle(id, 'restart')}
      />
      <ModeToggle state={terminalState} operator={operator} onRequestMode={(mode) => onRequestMode(agentId, mode)} />
      <ContentViewSwitch
        active={active}
        orchestration={
          <div className="agent-orchestration">
            <AgentTranscript rows={rows} />
            <Composer onSend={send} historySize={historySize} />
          </div>
        }
        shell={
          <ShellGate operator={operator}>
            <XtermView baseUrl={wsBase} agentId={agentId} token={token} />
          </ShellGate>
        }
      />
    </div>
  )
}
