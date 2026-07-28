// CYP-425 (App-Assembly) — the per-agent window body: the [Orchestrierung | Shell] toggle over the two RETAINED
// content views (ContentViewSwitch keeps both mounted, §W8.2). Orchestration = the structured /ws/agent transcript
// + the composer (send on the same socket). Shell = the operator-gated xterm (ShellGate mounts XtermView only after
// the open-warning). The toggle is non-optimistic: `active` follows the server-confirmed terminal-control state.
import { lazy, Suspense, useMemo } from 'react'
import { ModeToggle } from './agentview/ModeToggle'
import { ContentViewSwitch } from './agentview/ContentViewSwitch'
import { AgentTranscript } from './agentview/AgentTranscript'
import { Composer } from './agentview/Composer'
import { terminalModeSelection, type TerminalControlState, type SelectedView } from './agentview/terminalModeSelection'
import { HandoffBanner } from './agentview/HandoffBanner'
import type { AgentTerminalControlEvent } from './types/generated/contract'
import { useAgentTranscript } from './agentview/useAgentTranscript'
import { loadHistorySize, browserStore } from './agentview/historySizePreference'
import { LifecycleHeader } from './agentview/LifecycleHeader'
import type { LifecycleState } from './agentview/lifecycleStatus'
import { FidelityBadge } from './connector/FidelityBadge'
import { useHubStore } from './state/hubStore'
import { ShellGate } from './terminal/ShellGate'
// CYP-665 (F3 bundle-size hardening): xterm (@xterm/*, ~88 KB gz ≈ 47 % of the initial bundle) is pulled ONLY by
// XtermView, which renders ONLY inside ShellGate's `open` phase (operator-only, opt-in, two-step warning). Lazy-load
// it so the whole xterm chunk leaves the initial paint and is fetched on the first (deliberate) shell-open. NO
// behaviour/access change: the server /ws/terminal gate stays the source of truth and ShellGate still gates the mount;
// the async import fires only when a window is actually mounted (open phase), so most sessions never download xterm.
// XtermView is a named export; adapted to React.lazy's default-export contract here so the module stays untouched.
const XtermView = lazy(() => import('./terminal/XtermView').then((m) => ({ default: m.XtermView })))
import type { SocketDeps } from './state/hubConfig'
import type { LifecycleAction, AgentErrorCode } from './state/hubReducers'

/** The localized agent-ready line (parity with the Kotlin default; web-ts has no i18n yet). */
const READY_NOTICE = 'Agent bereit'

export interface AgentWindowProps {
  /** CYP-735 §3.1: an unconfigured hub blocks agent start (present-but-disabled, with the reason stated). */
  setupBlocked?: boolean
  agentId: string
  wsBase: string
  token: string
  operator: boolean
  terminalState: TerminalControlState
  /** CYP-644: the full latest terminal-control event (state + heldBy + since) for the handoff/context-lost banner. */
  terminalControl?: AgentTerminalControlEvent
  onRequestMode: (agentId: string, mode: SelectedView) => void
  lifecycleState: LifecycleState
  lifecyclePending: LifecycleAction | undefined
  lifecycleError: string | null
  lifecycleErrorCode: AgentErrorCode | undefined
  onLifecycle: (agentId: string, action: LifecycleAction) => void
  socketDeps?: SocketDeps
}

export function AgentWindow({ setupBlocked = false,
  agentId,
  wsBase,
  token,
  operator,
  terminalState,
  terminalControl,
  onRequestMode,
  lifecycleState,
  lifecyclePending,
  lifecycleError,
  lifecycleErrorCode,
  onLifecycle,
  socketDeps,
}: AgentWindowProps) {
  const { rows, send, draft, setDraft } = useAgentTranscript({
    baseUrl: wsBase,
    agentId,
    // CYP-454: /ws/agent uses the same-origin cookie, not a token. `token` stays for XtermView (/ws/terminal, CYP-286).
    readyNoticeText: READY_NOTICE,
    factory: socketDeps?.factory,
    schedule: socketDeps?.schedule,
  })
  const active = terminalModeSelection(terminalState).selected
  const historySize = useMemo(() => () => loadHistorySize(browserStore()), [])
  // CYP-488: the OBSERVED fidelity comes from the roster Agent.capabilities (store) — read here so no App.tsx prop
  // threading is needed. The badge is fail-closed by absence (present only when degraded / not-yet-reported).
  const agent = useHubStore((s) => s.roster.find((a) => a.id === agentId))

  return (
    <div className="agent-window" data-testid={`agent-window.${agentId}`}>
      <LifecycleHeader
        setupBlocked={setupBlocked}
        agentId={agentId}
        state={lifecycleState}
        pending={lifecyclePending}
        operator={operator}
        error={lifecycleError}
        errorCode={lifecycleErrorCode}
        onStart={(id) => onLifecycle(id, 'start')}
        onStop={(id) => onLifecycle(id, 'stop')}
        onRestart={(id) => onLifecycle(id, 'restart')}
      />
      {/* CYP-488: observed fidelity badge (own axis, beside the lifecycle status) — present only when degraded/unknown. */}
      <FidelityBadge agentId={agentId} capabilities={agent?.capabilities} connectorKind={agent?.connectorKind} />
      <ModeToggle state={terminalState} operator={operator} onRequestMode={(mode) => onRequestMode(agentId, mode)} />
      {/* CYP-644: handoff / context-lost landmark banner (WARN-amber, persistent; null → nothing, fail-closed). */}
      <HandoffBanner agentId={agentId} control={terminalControl} />
      <ContentViewSwitch
        active={active}
        orchestration={
          <div className="agent-orchestration">
            <AgentTranscript rows={rows} />
            {/* CYP-890: draft is the shared VM's (survives a nav-destination switch). A PRODUCT_LEAD is a read-only
                reviewer — the composer is disabled as the honest CLIENT hint of the server truth (canWrite=false;
                postAsAgent 403s their send). The server remains the authority; this is render-mirrors-authority. */}
            <Composer
              onSend={send}
              historySize={historySize}
              draft={draft}
              onDraftChange={setDraft}
              disabled={agent?.role === 'PRODUCT_LEAD'}
              disabledReason={agent?.role === 'PRODUCT_LEAD' ? 'Product-Lead ist Read-only-Reviewer — Senden deaktiviert.' : undefined}
            />
          </div>
        }
        shell={
          <ShellGate operator={operator}>
            {/* CYP-665: the async xterm chunk loads on the first shell-open; Suspense shows a brief loading line
                only during that one-time fetch (a deliberate operator action), never on the initial paint. */}
            <Suspense fallback={<p className="xterm-loading" data-testid="xterm-loading">Terminal wird geladen …</p>}>
              <XtermView baseUrl={wsBase} agentId={agentId} token={token} />
            </Suspense>
          </ShellGate>
        }
      />
    </div>
  )
}
