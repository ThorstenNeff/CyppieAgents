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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { WindowHost } from './windowmgr/WindowHost'
import { WindowFrame } from './windowmgr/WindowFrame'
import { useWindowStore } from './windowmgr/windowStore'
import type { WindowState } from './windowmgr/windowState'
import { deriveWindowActivity } from './windowmgr/activityBadge'
import { WindowActivityBadge } from './windowmgr/WindowActivityBadge'
import { WindowBadge } from './windowmgr/WindowBadge'
import { commCountBadge, eventSeverityBadge, maxTailSeverity } from './windowmgr/windowBadgeModel'
import { useHubStore } from './state/hubStore'
import { rosterPoAgentId } from './state/hubReducers'
import { readHubConfig, type HubConfig, type SocketDeps } from './state/hubConfig'
import { RestHubRepo, type HubRepo } from './state/restRepo'
import { RestError, restErrorCode } from './net/rest'
import { commitAclChange } from './state/aclCommit'
import { startLiveHub } from './state/liveHub'
import { AgentWindow } from './AgentWindow'
import { AclPanel } from './comm/AclPanel'
import { CommPanel } from './comm/CommPanel'
import { firstUnreadIndex, hasAuthoritativeSeq, markReadUpTo, unreadViewFrom, type ChannelReadState } from './comm/unreadModel'
import { canAdvanceReadCursor } from './comm/markReadGate'
import { useFocusState } from './comm/useFocusState'
import { EventLogView } from './eventlog/EventLogView'
import { EventBrowsePanel } from './eventlog/EventBrowsePanel'
import { useEventLogStore } from './eventlog/eventLogStore'
import { tailView } from './eventlog/eventLog'
import { AgentManagementPanel } from './agentmgmt/AgentManagementPanel'
import { AgentSettingsPanel } from './agentsettings/AgentSettingsPanel'
import type { ConnectorKind } from './connector/connectorModel'
import { ProductLeadPanel } from './report/ProductLeadPanel'
import type { ReportType } from './report/productLeadModel'
// CYP-453: App renders SettingsPanel (which frames the CYP-433 ApiKeyPanel internally) — no direct ApiKeyPanel here.
import { SettingsPanel, RepoSection } from './settings/SettingsPanel'
import { ApiKeyPanel } from './settings/ApiKeyPanel'
import { LoadErrorRetry } from './ui/LoadErrorRetry'
import { loadHistorySize, saveHistorySize, browserStore } from './agentview/historySizePreference'
import { ComposerHistoryStepper } from './agentview/ComposerHistoryStepper'
import type { AclDimension } from './comm/aclModel'
import type { SelectedView } from './agentview/terminalModeSelection'
import { lifecycleRejectMessage } from './agentview/lifecycleStatus'
import type { LifecycleAction } from './state/hubReducers'
import type { AclEntry, ApiKeyView, DeliveredMessage, RepoConfigView, RepoConfigRequest, ProjectsView, Capacity, CompactStatus, CompactConfig, WorkspaceMember, OperatorAudit } from './types/generated/contract'
import { WorkspaceRosterPanel } from './workspace/WorkspaceRosterPanel'
import { CapacityPill } from './workspace/CapacityPill'
import { CompactPanel } from './compact/CompactPanel'
import { ProjectManagementPanel } from './project/ProjectManagementPanel'
import { ChannelSharePanel } from './comm/ChannelSharePanel'
import { ProjectSwitcher } from './project/ProjectSwitcher'
import { OverloadBanner } from './workspace/OverloadBanner'
import { UnconfiguredBanner } from './workspace/UnconfiguredBanner'
import { setupStatusOf, mayPromptSetup } from './workspace/setupStatus'
import { FirstRunGate } from './firstrun/FirstRunGate'
import { firstRunGateMode, setupErrorCueVisible } from './firstrun/firstRunModel'
import { isSetupSkipped, setSetupSkipped, clearSetupSkipped } from './firstrun/skipPreference'
import { cloneView, clonePollMs, isCloneDone } from './firstrun/cloneStatusModel'
import { CloneStatusRow } from './firstrun/CloneStatusRow'
import { overloadVisible } from './workspace/capacityModel'
import { ThemeToggle } from './ui/ThemeToggle'
import { RemoteSecurityTierBadge } from './connector/RemoteSecurityTierBadge'
import { gatewayTierFor } from './connector/gatewayTier'
import { loadThemeMode, saveThemeMode, applyThemeMode, type ThemeMode } from './ui/themePreference'

const AGENT_PREFIX = 'agent:'
const ACL_WINDOW_ID = 'acl'
const COMM_WINDOW_ID = 'comm'
const EVENT_WINDOW_ID = 'events'
const EVENT_BROWSE_WINDOW_ID = 'eventBrowse'
const SETTINGS_WINDOW_ID = 'settings'
const AGENT_MGMT_WINDOW_ID = 'agentMgmt'
const AGENT_SETTINGS_WINDOW_ID = 'agentSettings'
const PRODUCT_LEAD_WINDOW_ID = 'productLead'
const COMPACT_WINDOW_ID = 'compact'
const WORKSPACE_WINDOW_ID = 'workspace'
const PROJECT_MGMT_WINDOW_ID = 'projectMgmt'
const CHANNEL_SHARE_WINDOW_ID = 'channelShare'
// CYP-662: the server's privileged operator identity (HubState.OPERATOR_ID). It is injected UNCONDITIONALLY as a
// member of every spoke channel (an auth/ACL participant), so it rides in `agents` (the roster ∪ channel-members
// union) — but it is NOT a spawnable agent: `/ws/agent?agentId=operator` is rejected fail-closed. It must therefore
// never get an agent WINDOW (it stays a legitimate ACL participant column). Token config is irrelevant — the filter
// is unconditional (Backend2: the members-injection is not token-gated).
const OPERATOR_AGENT_ID = 'operator'

// CYP-705 — `seq` is the CANONICAL ordering key (store-assigned, monotonic, viewer-independent); `ts` is a
// client-observed epoch that can skew or collide, so it is only a fallback for legacy rows that carry no
// authoritative seq (seq absent or 0). This matters beyond aesthetics: the unread divider is DEFINED as the first
// message past the cursor by seq, so if the rendered order disagreed with seq the line would land in the wrong
// place. Ordering and the divider must come from the same key.
/** Existing read-state entries as a list (so a POST echo folds in without dropping the other channels). */
const currentEntries = (v: { kind: 'unavailable' } | { kind: 'available'; channels: Readonly<Record<string, ChannelReadState>> }) =>
  v.kind === 'available' ? Object.values(v.channels) : []

// CYP-744: entries are DeliveredMessage envelopes — order on the STORED message (seq/ts ride there untouched; the
// mention spans never affect ordering). Same key as the divider (firstUnreadIndex), which also reads the message.
const byOrder = (a: DeliveredMessage, b: DeliveredMessage): number =>
  hasAuthoritativeSeq(a.message) && hasAuthoritativeSeq(b.message)
    ? (a.message.seq as number) - (b.message.seq as number)
    : a.message.ts - b.message.ts

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
  /** CYP-470: the operator flag resolved from whoami by the AuthGate (role==='OPERATOR'). Overrides the injected-token
   *  guess so the operator gates hang on the real session; falls back to cfg.operator (break-glass / tests). */
  operatorOverride?: boolean
}

export function App({ config, repo, socketDeps, operatorOverride }: AppProps = {}) {
  const cfg = config ?? readHubConfig()
  // CYP-661 (defense-in-depth): memoize so the repo identity is STABLE across re-renders. A fresh `new RestHubRepo`
  // every render (each building a new RestClient) is the churn ROOT that defeated the section load-effects — the
  // component-local useRef cures immunise the critical paths, but a stable repo protects any consumer (incl. future
  // ones) that keys on its identity. Keyed on the primitive apiBase (cfg is a fresh object each render when config is
  // undefined → readHubConfig()).
  const hubRepo = useMemo(() => repo ?? new RestHubRepo(cfg.apiBase), [repo, cfg.apiBase])
  // CYP-470: whoami is the truth for operator; cfg.operator (injected token) is the break-glass / test fallback.
  const operator = operatorOverride ?? cfg.operator

  const setRoster = useHubStore((s) => s.setRoster)
  const setUnreadView = useHubStore((s) => s.setUnreadView)
  const unreadView = useHubStore((s) => s.unreadView)
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
  const onBusyState = useHubStore((s) => s.onBusyState)
  const onTokenUsage = useHubStore((s) => s.onTokenUsage)
  const [aclError, setAclError] = useState<string | null>(null)
  // CYP-288: honest INITIAL-load errors (failed load ≠ empty). Transient UI state (App-local, not the domain store);
  // the panels render an error+retry surface off these, gated on their data still being empty so live/WS data hides it.
  const [rosterLoadError, setRosterLoadError] = useState(false)
  const [channelsLoadError, setChannelsLoadError] = useState(false)
  const [aclEntriesLoadError, setAclEntriesLoadError] = useState(false)
  const [messagesLoadError, setMessagesLoadError] = useState(false)
  // CYP-679: honest load-errors for the remaining bootstrap fetches that feed a false empty/absent surface (same
  // class as CYP-288). getCapacity is EXCLUDED (its pill renders nothing on absent by design — absent ≠ empty).
  const [repoConfigLoadError, setRepoConfigLoadError] = useState(false)
  const [apiKeyLoadError, setApiKeyLoadError] = useState(false)
  const [projectsLoadError, setProjectsLoadError] = useState(false)
  const [workspaceMembersLoadError, setWorkspaceMembersLoadError] = useState(false)
  const [operatorAuditLoadError, setOperatorAuditLoadError] = useState(false)
  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null)
  const [commSendError, setCommSendError] = useState<string | null>(null)
  // CYP-433: the API-key MASKED view (never the plaintext — the server only ever sends {set, masked:"***last4"}).
  const [apiKeyView, setApiKeyView] = useState<ApiKeyView | null>(null)
  // CYP-453: the project repo config ({ configured, url?, branch? }) — drives the honest unset status + prefills.
  const [repoConfig, setRepoConfig] = useState<RepoConfigView | null>(null)
  // CYP-735 §3.1/§4: the honest four-state setup status. `null` means "not loaded" OR "load failed", so it is
  // NOT read as unconfigured — prompting setup on a failed load tells a configured operator to configure.
  const setupStatus = setupStatusOf(repoConfig, repoConfigLoadError)
  const setupPrompt = mayPromptSetup(setupStatus)
  // CYP-735 §3.2 — the guided gate. Inputs are DERIVED, never stored, so the gate and §3.1's banner cannot drift.
  // `repoCloned` stays null until the CYP-736 clone seam lands, so the repo step tops out at "saved" and the gate
  // cannot reach transparent — honest, because an accepted URL is not a clonable repo.
  // CYP-735 §3.3 — the clone lifecycle, now that CYP-736 exposes it. `cloneStartedAt` is the CLIENT's own clock:
  // the server reports the phase, we measure only how long CLONING has lasted. That measurement may produce an
  // advisory hint, never a verdict — a hung clone is indistinguishable from a slow one from here.
  const [cloneStartedAt, setCloneStartedAt] = useState<number | null>(null)
  const [cloneElapsedMs, setCloneElapsedMs] = useState(0)
  const clone = cloneView(repoConfig, cloneElapsedMs)
  const firstRunInputs = {
    setup: setupStatus,
    apiKeySet: apiKeyView === null ? null : apiKeyView.set,
    repoCloned: isCloneDone(clone) ? true : null,
  }
  const [setupSkipped, setSetupSkippedState] = useState(isSetupSkipped)
  // Condition (c): once the server says configured the gate is transparent BY DERIVATION — a stale skip flag can
  // neither hold it open nor keep anything hidden.
  const showGate = firstRunGateMode(firstRunInputs) !== 'transparent' && !setupSkipped
  // CYP-467/94: the project registry + active pointer drive the Event-Browse cross-project axis (operator-only view).
  const [projectsView, setProjectsView] = useState<ProjectsView | null>(null)
  // CYP-642: the server-authoritative capacity snapshot ({current, estimatedMax?}) — drives the capacity pill.
  // Refetched after spawn/exit (lifecycle, add) since those move `current`. null = unknown → pill absent (never 0/0).
  const [capacity, setCapacity] = useState<Capacity | null>(null)
  // CYP-649: the server-owned compact status. null = UNKNOWN (unresolved / load failed) → the panel renders facts +
  // editors ABSENT, never a defaulted idle/off. Refreshed on mount + polled while mounted (status moves as a run does).
  const [compactStatus, setCompactStatus] = useState<CompactStatus | null>(null)
  // CYP-650: the OPERATOR-only workspace roster + recent operator-audit. Fetched (and the window mounted) ONLY for an
  // operator — a member never enumerates the roster (the same egress seam as the event log).
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([])
  const [operatorAudit, setOperatorAudit] = useState<OperatorAudit[]>([])
  // CYP-642: a REAL server overload reject (503 capacity_exceeded from a spawn/start) raises the banner; it
  // self-clears when headroom returns (overloadVisible) and is dismissable. A NEW reject un-dismisses (Q5).
  const [overloadActive, setOverloadActive] = useState(false)
  const [overloadDismissed, setOverloadDismissed] = useState(false)
  // CYP-643: the app-global theme mode (client-local, per-user, durable). Loaded once from localStorage; applied to
  // <html> via data-theme (the tokens CSS recolours). system = no attribute → prefers-color-scheme governs.
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => loadThemeMode(browserStore()))
  // CYP-645: the ONE global composer input-history size N (recall depth). Held here for the stepper's display; the
  // composers read it live via their loadHistorySize() supplier (localStorage is the shared source, so a save is
  // picked up on the next recall — no prop threading).
  const [historySizeValue, setHistorySizeValue] = useState<number>(() => loadHistorySize(browserStore()))
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
  const terminalControlByAgent = useHubStore((s) => s.terminalControlByAgent) // CYP-644: full event for the banner
  const messagesByChannel = useHubStore((s) => s.messagesByChannel)
  const commConnection = useHubStore((s) => s.commConnection)
  const runStateByAgent = useHubStore((s) => s.runStateByAgent)
  const errorCodeByAgent = useHubStore((s) => s.errorCodeByAgent)
  const lifecyclePending = useHubStore((s) => s.lifecyclePending)
  const busyByAgent = useHubStore((s) => s.busyByAgent)
  const contextTokensByAgent = useHubStore((s) => s.contextTokensByAgent)

  // CYP-432: the event log is its own store (separate from the hub state). OPERATOR-ONLY: it carries message
  // bodies, so the whole surface (window + socket + data) is gated on operator — defence-in-depth, not just
  // the server tier (mirrors ShellGate/AclPanel; a W10 backstop if the proxy ever leaks the operator token).
  const onEventsEvent = useEventLogStore((s) => s.onEventsEvent)
  const onEventsClose = useEventLogStore((s) => s.onEventsClose)
  const eventLog = useEventLogStore((s) => s.events)
  const eventsCaughtUp = useEventLogStore((s) => s.caughtUp)
  const eventsAccessRevoked = useEventLogStore((s) => s.accessRevoked)
  const eventsTrimmed = useEventLogStore((s) => s.trimmed)
  const eventsPaused = useEventLogStore((s) => s.paused)
  const eventsPausedAtSeq = useEventLogStore((s) => s.pausedAtSeq)
  // CYP-646: the focused (top-most) window id — list order is z-order, so the last window is focused. Selecting only
  // the id keeps this off the drag/resize re-render path.
  const focusedWindowId = useWindowStore((s) => (s.windows.length > 0 ? s.windows[s.windows.length - 1].id : null))
  // CYP-705 focus gate: the browser half (tab visible + window focused); the in-app half is focusedWindowId.
  const browserFocus = useFocusState()
  // CYP-732: highest upToSeq already REQUESTED per channel (see the mark-read effect for why the echo alone is
  // not a safe loop guard). A ref, not state — it must not itself re-trigger the effect.
  const requestedRead = useRef<Map<string, number>>(new Map())
  // CYP-646 (Count): comm-unread since the comm window was last focused. `commSeen` tracks the total at the last
  // focus; unread = total − seen, reset to 0 whenever the comm window is focused (a focused window has "seen" it).
  const commTotalCount = useMemo(() => {
    let n = 0
    for (const msgs of messagesByChannel.values()) n += msgs.length
    return n
  }, [messagesByChannel])
  const [commSeenCount, setCommSeenCount] = useState(0)
  useEffect(() => {
    if (focusedWindowId === COMM_WINDOW_ID) setCommSeenCount(commTotalCount)
  }, [focusedWindowId, commTotalCount])
  const commUnread = Math.max(0, commTotalCount - commSeenCount)
  // CYP-646 (Severity): the event tail's max open severity (drives the event-window badge at/above WARN).
  const tailMaxSeverity = useMemo(() => maxTailSeverity(eventLog), [eventLog])
  const toggleEventsPause = useEventLogStore((s) => s.togglePause)

  const historySize = useMemo(() => () => loadHistorySize(browserStore()), [])
  // CYP-444: the PO identity is the roster's role==PO, not a config guess. Null until the roster loads (the W9
  // lockout advisory simply won't fire until we truly know who the PO is).
  const poAgentId = rosterPoAgentId(roster)

  // CYP-288: named snapshot loaders — set the data (clearing the error) on success, or flag a load error on failure,
  // so a failed INITIAL load surfaces an honest error+retry instead of a silent empty state. Reused as the panels'
  // onRetry. Stable (useCallback over the memoized hubRepo + stable store actions) → churn-immune (CYP-660/661).
  const loadRoster = useCallback(() => {
    hubRepo.fetchAgents().then((r) => { setRoster(r); setRosterLoadError(false) }).catch(() => setRosterLoadError(true))
  }, [hubRepo, setRoster])
  // CYP-705 — the read-state fetch. On failure the view simply stays `unavailable`, which renders the visible
  // "unknown" marker: no badge, and crucially no all-clear. OPERATOR-tier server-side, so a member gets 403 →
  // unknown everywhere, which is the honest answer rather than a fabricated zero.
  const loadReadState = useCallback(() => {
    hubRepo.fetchReadState().then((rs) => setUnreadView(unreadViewFrom(rs))).catch(() => undefined)
  }, [hubRepo, setUnreadView])
  const loadChannels = useCallback(() => {
    hubRepo.fetchChannels().then((c) => { setChannels(c); setChannelsLoadError(false) }).catch(() => setChannelsLoadError(true))
  }, [hubRepo, setChannels])
  const loadAcl = useCallback(() => {
    hubRepo.fetchAcl().then((a) => { setAcl(a); setAclEntriesLoadError(false) }).catch(() => setAclEntriesLoadError(true))
  }, [hubRepo, setAcl])
  const loadMessages = useCallback((channelId: string) => {
    hubRepo.getMessages(channelId).then((m) => { ingestMessages(m); setMessagesLoadError(false) }).catch(() => setMessagesLoadError(true))
  }, [hubRepo, ingestMessages])
  // CYP-679: the same set-data+clear-error / flag-error loaders for the APPLY-set bootstrap fetches (also reused as
  // each surface's onRetry). Stable over the memoized hubRepo → churn-immune.
  const loadRepoConfig = useCallback(() => {
    hubRepo.getRepoConfig().then((c) => { setRepoConfig(c); setRepoConfigLoadError(false) }).catch(() => setRepoConfigLoadError(true))
  }, [hubRepo])
  // CYP-735 §3.3 — poll the clone status. Thins after the long threshold but NEVER stops before a verdict
  // (thinning is not giving up), and is bound to this component's life: unmount/skip stops it, so there is no
  // forever-loop. A terminal status ends it because there is nothing left to watch.
  useEffect(() => {
    const every = clonePollMs(clone)
    if (every === null) return
    const id = setInterval(() => loadRepoConfig(), every)
    return () => clearInterval(id)
  }, [clone.kind, clone.kind === 'cloning' && clone.long, loadRepoConfig]) // eslint-disable-line react-hooks/exhaustive-deps

  // Client-measured CLONING duration: start the clock when cloning begins, clear it when it ends.
  useEffect(() => {
    if (clone.kind !== 'cloning') {
      setCloneStartedAt(null)
      setCloneElapsedMs(0)
      return
    }
    const started = cloneStartedAt ?? Date.now()
    if (cloneStartedAt === null) setCloneStartedAt(started)
    const id = setInterval(() => setCloneElapsedMs(Date.now() - started), 1_000)
    return () => clearInterval(id)
  }, [clone.kind, cloneStartedAt])

  const loadApiKey = useCallback(() => {
    hubRepo.getApiKey().then((v) => { setApiKeyView(v); setApiKeyLoadError(false) }).catch(() => setApiKeyLoadError(true)) // masked view; plaintext never comes back
  }, [hubRepo])
  const loadProjects = useCallback(() => {
    hubRepo.getProjects().then((p) => { setProjectsView(p); setProjectsLoadError(false) }).catch(() => setProjectsLoadError(true))
  }, [hubRepo])
  const loadWorkspaceMembers = useCallback(() => {
    hubRepo.getWorkspaceMembers().then((m) => { setWorkspaceMembers(m); setWorkspaceMembersLoadError(false) }).catch(() => setWorkspaceMembersLoadError(true))
  }, [hubRepo])
  const loadOperatorAudit = useCallback(() => {
    hubRepo.getOperatorAudit().then((a) => { setOperatorAudit(a); setOperatorAuditLoadError(false) }).catch(() => setOperatorAuditLoadError(true))
  }, [hubRepo])

  // Bootstrap: REST snapshot + the live-socket VM. Runs once; the VM stops on unmount.
  useEffect(() => {
    loadRoster()
    loadChannels()
    loadAcl()
    loadReadState() // CYP-705: unread cursors; on failure the view stays UNKNOWN (visible), never all-clear
    loadApiKey() // CYP-679: honest load-error (masked status else falsely reads "kein Schlüssel")
    loadRepoConfig() // CYP-679: honest load-error (blank form else falsely reads "unconfigured")
    loadProjects() // CYP-679: honest load-error at ProjectManagementPanel (else "Projekte werden geladen…" forever)
    hubRepo.getCapacity().then(setCapacity).catch(() => undefined) // CYP-642 capacity snapshot — CYP-679 N/A: the pill renders nothing on absent by design (absent ≠ empty)
    if (operator) {
      // CYP-650: operator-only egress — a member never fetches the roster/audit (enumeration seam).
      loadWorkspaceMembers() // CYP-679: honest load-error (else falsely reads "Keine Mitglieder")
      loadOperatorAudit() // CYP-679: honest load-error (else falsely reads "Keine Aktionen")
    }
    const live = startLiveHub(
      cfg,
      {
        onCommEvent,
        onTerminalControl,
        // CYP-705 ⑥ (UIUX2 §9b) — a reconnect MUST re-fetch the read-state, not just flip the banner. While the
        // socket was down the cursor may have advanced elsewhere (another device, another tab), so the counts we
        // still hold are stale — and rendering stale counts as current is the same lie as a fabricated zero, just
        // aged. The client re-asks rather than waiting for a server push, so it does not depend on the server
        // choosing to resend after a gap it cannot see.
        onCommOpen: () => {
          setCommConnection('live')
          loadReadState()
        },
        // CYP-437(b): an unexpected drop flips the banner off 'live'; a 1008 (auth revoked) is terminal → 'revoked'.
        onCommClose: (code) => setCommConnection(code === 1008 ? 'revoked' : 'offline'),
        // CYP-445-QA minor (folded into CYP-446): a server run-state event also RESOLVES the transient action-reject
        // notice for that agent — a new confirmed state makes the last reject stale, so clear it here (not only on
        // the next action attempt), consistent with how the feed clears the pending flag.
        onRunState: (ev) => {
          onRunState(ev)
          setAgentLifecycleError(ev.agentId, null)
        },
        // CYP-641: the two activity feeds fold straight into the store (no bodies → no operator gate).
        onBusyState,
        onTokenUsage,
        // CYP-432 fail-closed: wire the /ws/events handlers ONLY for an operator → a non-operator never opens the
        // bodies-carrying socket (liveHub skips it when onEventsEvent is absent).
        onEventsEvent: operator ? onEventsEvent : undefined,
        onEventsClose: operator ? onEventsClose : undefined,
      },
      socketDeps,
    )
    return () => live.stop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // CYP-643: apply the theme mode to <html> whenever it changes (and on mount) — the tokens CSS recolours off
  // data-theme. Guarded for a non-DOM env (defensive; the app always has document).
  useEffect(() => {
    if (typeof document !== 'undefined') applyThemeMode(themeMode, document.documentElement)
  }, [themeMode])

  // Default the comm selection to the first channel once channels arrive.
  useEffect(() => {
    if (selectedChannelId === null && channels.length > 0) setSelectedChannelId(channels[0].id)
  }, [channels, selectedChannelId])

  // CYP-705 — MARK-READ. Viewing a channel ASKS the server to advance the cursor; it never moves the badge
  // locally. The count changes only when the server answers (this 200, or the self-only ReadStateEvent), so a
  // scroll can never fake "read". Only authoritative seqs are sent: with none, no request is made at all —
  // `upToSeq: 0` would be a server no-op but still a claim to have read something.
  useEffect(() => {
    if (selectedChannelId === null) return
    // FOCUS GATE: only advance the cursor while this conversation is actually in front of someone. Without it the
    // `messagesByChannel` dependency made every ARRIVING message advance the cursor — including with the tab
    // hidden — marking read what nobody saw. The cursor is durable and cross-device, so that erases the unread
    // signal everywhere, permanently. Refusing when unsure only leaves something unread, which self-corrects the
    // moment you look at it.
    if (!canAdvanceReadCursor({ ...browserFocus, commWindowFocused: focusedWindowId === COMM_WINDOW_ID })) return
    const rendered = messagesByChannel.get(selectedChannelId) ?? []
    // CYP-744: the read cursor is about the STORED message's seq — unwrap the envelope; unreadModel is unchanged.
    const upTo = markReadUpTo(rendered.map((d) => d.message))
    if (upTo === null) return
    const known = unreadView.kind === 'available' ? unreadView.channels[selectedChannelId] : undefined
    if (known !== undefined && known.lastReadSeq >= upTo) return // already at/past this point — no redundant POST
    // A LOCAL request ledger, deliberately independent of the echo. Without it, termination depends on the server
    // echoing a cursor >= what we asked for THIS channel: a server that clamps, lags, or answers about a different
    // channel would leave the guard above permanently untripped, and since the effect re-runs on every unreadView
    // change, the client would hammer /read in an unbounded loop. Asking once per (channel, upTo) is the honest
    // contract — we do not re-ask merely because we dislike the answer. (Surfaced by a test that hung: the double
    // echoed a different channelId, which is exactly the disagreeing-server case.)
    if ((requestedRead.current.get(selectedChannelId) ?? 0) >= upTo) return
    requestedRead.current.set(selectedChannelId, upTo)
    hubRepo.markRead(selectedChannelId, upTo).then((rs) => setUnreadView(unreadViewFrom([...currentEntries(unreadView), rs]))).catch(() => undefined)
  }, [selectedChannelId, messagesByChannel, unreadView, hubRepo, setUnreadView, browserFocus, focusedWindowId])

  // On channel select, load its ACL-filtered history and fold it in (deduped by id → safe to overlap with live).
  useEffect(() => {
    if (selectedChannelId === null) return
    setCommSendError(null)
    setMessagesLoadError(false) // reset per channel switch — the error is about THIS channel's history load
    loadMessages(selectedChannelId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedChannelId])

  // Open one window per agent + the ACL window (idempotent: only adds windows not already present, so a live
  // channels update that reveals a new agent adds its window without disturbing the existing layout).
  useEffect(() => {
    const wm = useWindowStore.getState()
    const present = new Set(wm.windows.map((w) => w.id))
    let index = wm.windows.length
    for (const agentId of agents) {
      // CYP-662: `operator` is a channel-membership/ACL identity, not a spawnable agent — never open a window for it
      // (a dead phantom: the server rejects /ws/agent?agentId=operator, so the transcript is empty + the composer
      // sends to nowhere, falsely presenting the operator as controllable). It stays an ACL column (agents unchanged).
      if (agentId === OPERATOR_AGENT_ID) continue
      const id = `${AGENT_PREFIX}${agentId}`
      if (!present.has(id)) wm.add(tiledWindow(id, agentId, index++), true)
    }
    if (agents.length > 0 && !present.has(COMM_WINDOW_ID)) wm.add(tiledWindow(COMM_WINDOW_ID, 'Kommunikation', index++), true)
    if (agents.length > 0 && !present.has(ACL_WINDOW_ID)) wm.add(tiledWindow(ACL_WINDOW_ID, 'Zugriffsrechte (ACL)', index++), false)
    // CYP-432: the event log is OPERATOR-ONLY — a non-operator gets no event window at all (no bodies surface).
    if (agents.length > 0 && operator && !present.has(EVENT_WINDOW_ID)) wm.add(tiledWindow(EVENT_WINDOW_ID, 'Ereignis-Protokoll', index++), true)
    // CYP-650: the workspace roster is OPERATOR-ONLY — a non-operator gets NO window at all (enumeration seam), not a
    // gated panel. Mirrors the event-log omission.
    if (agents.length > 0 && operator && !present.has(WORKSPACE_WINDOW_ID)) wm.add(tiledWindow(WORKSPACE_WINDOW_ID, 'Arbeitsbereich', index++), false)
    // CYP-452: Browse is the same operator-gated, content-free metadata as the live-tail → OPERATOR-ONLY window
    // (omission for a non-operator; no Browse route, no /api/events query, no bodies in the DOM). The real scope
    // boundary is server-side (resolveEventScope + masking); this client gate is defence-in-depth + product-scoping.
    if (agents.length > 0 && operator && !present.has(EVENT_BROWSE_WINDOW_ID)) wm.add(tiledWindow(EVENT_BROWSE_WINDOW_ID, 'Ereignis-Browser', index++), false)
    // CYP-433: the settings/API-key window is present for EVERYONE (present-but-disabled) — the masked status leaks
    // nothing; the panel gates editing on operator internally.
    if (agents.length > 0 && !present.has(SETTINGS_WINDOW_ID)) wm.add(tiledWindow(SETTINGS_WINDOW_ID, 'Einstellungen', index++), false)
    // CYP-450: the agent-management window is present for EVERYONE — the roster/list is ungated display; the panel
    // gates add/edit/remove on operator internally (present-but-disabled), never omission.
    if (agents.length > 0 && !present.has(AGENT_MGMT_WINDOW_ID)) wm.add(tiledWindow(AGENT_MGMT_WINDOW_ID, 'Agenten-Verwaltung', index++), false)
    // CYP-657: per-agent settings (colour / CLAUDE.md / worktree path) — present for EVERYONE; the panel gates the
    // mutations on operator (present-but-disabled + gate hint), the display is ungated. Mirrors agent-management.
    if (agents.length > 0 && !present.has(AGENT_SETTINGS_WINDOW_ID)) wm.add(tiledWindow(AGENT_SETTINGS_WINDOW_ID, 'Agenten-Einstellungen', index++), false)
    // CYP-464: Product-Lead report window is present for everyone; the panel fail-closes to the gate-hint (no trigger/
    // list/fetch) for a non-operator — reports are content-free, so this is present-but-gate-hint, not omission (§3).
    if (agents.length > 0 && !present.has(PRODUCT_LEAD_WINDOW_ID)) wm.add(tiledWindow(PRODUCT_LEAD_WINDOW_ID, 'Product-Lead', index++), false)
    // CYP-649: the compact-orchestration window is present for EVERYONE (the status is read-tier); the panel gates
    // editing on operator internally (member = read-only chip + gate hint), never omission.
    if (agents.length > 0 && !present.has(COMPACT_WINDOW_ID)) wm.add(tiledWindow(COMPACT_WINDOW_ID, 'Compact', index++), false)
    // CYP-651: project management window — present for everyone; the panel fail-closes to a gate-hint for a
    // non-operator (no list/mutations), mirrors the agent-management gate pattern.
    if (agents.length > 0 && !present.has(PROJECT_MGMT_WINDOW_ID)) wm.add(tiledWindow(PROJECT_MGMT_WINDOW_ID, 'Projekte', index++), false)
    // CYP-659: cross-project channel-share — present for EVERYONE (GET is read-tier: badge/status visible to readers);
    // the panel gates authorize/revoke on operator internally (present-but-disabled), never omission.
    if (agents.length > 0 && !present.has(CHANNEL_SHARE_WINDOW_ID)) wm.add(tiledWindow(CHANNEL_SHARE_WINDOW_ID, 'Kanal-Freigaben', index++), false)
  }, [agents, operator])

  const onRequestMode = (agentId: string, mode: SelectedView) => {
    hubRepo.requestMode(agentId, mode === 'shell' ? 'TERMINAL' : 'ORCHESTRATION').catch(() => undefined)
  }

  // CYP-643: change + persist the theme mode. The effect re-applies it to <html>; localStorage keeps it across
  // reloads. Purely client-local — no server, no gate.
  const onThemeChange = (mode: ThemeMode) => {
    setThemeMode(mode)
    saveThemeMode(browserStore(), mode)
  }

  // CYP-645: change + persist N. saveHistorySize clamps (0..MAX) and returns the clamped value — the single source of
  // truth for both the display and the composers' live supplier.
  const onHistorySizeChange = (n: number) => setHistorySizeValue(saveHistorySize(browserStore(), n))

  // CYP-431: non-optimistic lifecycle. The click marks a transient pending; the run-state flips only on the
  // server's AgentRunStateEvent (the POST response, mirrored by /ws/lifecycle) — both resolve the pending. A
  // rejected request clears the pending (no event will come) so the transient label can't stick.
  // CYP-642: re-read the server-authoritative capacity after a spawn/exit moved `current`.
  const refreshCapacity = () => hubRepo.getCapacity().then(setCapacity).catch(() => undefined)
  // CYP-649: re-read the server-owned compact status (on load failure it STAYS null → honest "unknown", never a
  // defaulted idle/off). Used by the mount fetch, the poll-while-open, and the post-config refresh.
  const refreshCompactStatus = () => hubRepo.getCompactStatus().then(setCompactStatus).catch(() => undefined)
  // CYP-649: operator config write (allowed / threshold / timings) — non-optimistic: POST then refetch so the panel
  // reflects the SERVER state, never the local draft. The server range-validates (400) and enforces the operator gate.
  const onSetCompactConfig = (config: CompactConfig): Promise<void> =>
    hubRepo.setCompactConfig(config).then(() => {
      void refreshCompactStatus()
    })

  // CYP-649 (CYP-328): refresh the compact status on mount + poll while mounted — the server-owned facts (running /
  // last-run) move AS a sequence runs, so an open window must re-poll to stay honest.
  useEffect(() => {
    void refreshCompactStatus()
    const id = setInterval(() => void refreshCompactStatus(), 5000)
    return () => clearInterval(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  // CYP-642: a REAL server capacity reject (503 capacity_exceeded, H5 — never invented) raises the overload banner
  // and un-dismisses it (a new reject re-surfaces even after a prior dismiss, Q5).
  const noteCapacityReject = (err: unknown) => {
    if (restErrorCode(err) === 'capacity_exceeded') {
      setOverloadActive(true)
      setOverloadDismissed(false)
    }
  }

  const onLifecycle = (agentId: string, action: LifecycleAction) => {
    setAgentLifecycleError(agentId, null) // clear any prior reject notice for this agent
    markLifecyclePending(agentId, action)
    hubRepo
      .setLifecycle(agentId, action)
      .then((ev) => {
        onRunState(ev)
        void refreshCapacity() // a start/stop changed the RUNNING count → refresh the pill
      })
      // CYP-445 §6: a rejected action clears the pending (no feed event will come) AND surfaces why (409/503/…).
      .catch((err) => {
        clearLifecyclePending(agentId)
        setAgentLifecycleError(agentId, lifecycleRejectMessage(err))
        noteCapacityReject(err) // CYP-642: a capacity_exceeded start-reject also raises the overload banner
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
  // CYP-744: mention resolution moved to the server — the client no longer builds a roster-id list to resolve
  // against, so the former `mentionRosterIds` memo is gone. Chips render from the DeliveredMessage spans directly.
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

  // CYP-450 non-optimistic agent CRUD: on server-confirm, REFETCH the roster (the list reflects the server, never a
  // guessed local mutation); a reject rejects the promise so the dialog surfaces the server code. The panel's own
  // onDone (spawnHint / effectHint) fires only after these resolve.
  const refreshRoster = () => hubRepo.fetchAgents().then(setRoster)
  const onCreateAgent = (spec: Parameters<HubRepo['createAgent']>[0]): Promise<void> =>
    hubRepo
      .createAgent(spec)
      .then(() => {
        void refreshRoster()
        void refreshCapacity() // CYP-642: a new agent may spawn → refresh the pill
      })
      // CYP-642: re-throw so the add dialog still surfaces the server code, but first raise the overload banner on a
      // real capacity_exceeded reject.
      .catch((err) => {
        noteCapacityReject(err)
        throw err
      })
  const onUpdateAgent = (id: string, edit: Parameters<HubRepo['updateAgent']>[1]): Promise<void> =>
    hubRepo.updateAgent(id, edit).then(refreshRoster)
  const onRemoveAgent = (id: string, fate: Parameters<HubRepo['removeAgent']>[1]): Promise<void> =>
    hubRepo.removeAgent(id, fate).then(refreshRoster)
  const fetchAgentDetailForEdit = (id: string) =>
    hubRepo.fetchAgentDetail(id).then((d) => ({ role: d.role, persona: d.persona, launch: d.launch }))

  // CYP-657: save just the display colour (AgentEdit.color). Non-optimistic — refetch the roster so the accent
  // reflects the server. Restart-deferred like every AgentEdit (the panel shows the amber effect hint on success).
  const onSaveAgentColor = (id: string, color: string): Promise<void> =>
    hubRepo.updateAgent(id, { color }).then(refreshRoster)

  // CYP-453: repo config save. Non-optimistic — the returned view refreshes the status/prefill; a reject rejects the
  // promise so the RepoSection surfaces the server code (invalid_repo_url) on its error line.
  const onSaveRepo = (req: RepoConfigRequest): Promise<void> => hubRepo.putRepoConfig(req).then((v) => setRepoConfig(v))

  // CYP-651: project mutations, all NON-OPTIMISTIC (the view reflects the server, never the local intent). Errors
  // propagate so the panel maps the server guard codes; the correctness boundary is the server ProjectGuard.
  const refreshProjects = () => hubRepo.getProjects().then(setProjectsView).catch(() => undefined)
  const onCreateProject = (id: string, name: string): Promise<void> => hubRepo.createProject(id, name).then(() => void refreshProjects())
  // Switch is heavyweight + non-optimistic: the returned ProjectsView (200) is the flipped pointer — set it only then.
  const onSwitchProject = (projectId: string): Promise<void> => hubRepo.switchProject(projectId).then((v) => setProjectsView(v))
  const onRenameProject = (id: string, name: string): Promise<void> => hubRepo.renameProject(id, name).then(() => void refreshProjects())
  // Delete: refetch in ALL cases (a 404 = 'already gone' still needs the list to reflect it); the error still
  // propagates so the panel surfaces the 409-last / 409-active guard messages.
  const onDeleteProject = (id: string, deleteWorktrees: boolean): Promise<void> =>
    hubRepo
      .deleteProject(id, deleteWorktrees)
      .then(() => undefined)
      .finally(() => void refreshProjects())

  // CYP-461: connector change (edit) → POST /connector, then refetch the roster so the settled kind reflects the
  // server (non-optimistic). The advisory preview source (getConnectors) is passed straight to the picker.
  const onSetConnector = (id: string, kind: ConnectorKind): Promise<void> =>
    hubRepo.setConnector(id, kind).then(() => {
      void refreshRoster()
    })

  const renderContent = (win: WindowState) => {
    if (win.id === AGENT_MGMT_WINDOW_ID) {
      // present for everyone; the panel gates add/edit/remove on operator (present-but-disabled). The roster is the
      // typed Agent[] source (CYP-444), not the id-union `agents`.
      return (
        <AgentManagementPanel
          agents={roster}
          operator={operator}
          loadError={rosterLoadError}
          onRetryLoad={loadRoster}
          runStateByAgent={runStateByAgent}
          busyByAgent={busyByAgent}
          onCreate={onCreateAgent}
          onUpdate={onUpdateAgent}
          onRemove={onRemoveAgent}
          fetchDetail={fetchAgentDetailForEdit}
          getConnectors={() => hubRepo.getConnectors()}
          onSetConnector={onSetConnector}
        />
      )
    }
    if (win.id === AGENT_SETTINGS_WINDOW_ID) {
      // CYP-657: per-agent settings (colour / CLAUDE.md conflict / worktree path). present-but-disabled for a member;
      // the roster is the typed Agent[] source. previewSurface is illustrative (the contrast guard checks BOTH themes).
      return (
        <AgentSettingsPanel
          agents={roster}
          operator={operator}
          previewSurface={themeMode === 'dark' ? 'dark' : 'light'}
          apiBase={cfg.apiBase}
          fetchDetail={(id) => hubRepo.fetchAgentDetail(id)}
          onSaveColor={onSaveAgentColor}
          getClaudeMd={(id) => hubRepo.getClaudeMd(id)}
          updateClaudeMd={(id, content, ev) => hubRepo.updateClaudeMd(id, { content, expectedVersion: ev })}
          onSetAvatarPreset={(id, preset) => hubRepo.setAvatarPreset(id, preset).then((a) => { void refreshRoster(); return a })}
          onUploadAvatar={(id, file) => hubRepo.uploadAvatar(id, file).then((d) => { void refreshRoster(); return d })}
          onRemoveAvatar={(id) => hubRepo.removeAvatar(id).then(() => { void refreshRoster() })}
        />
      )
    }
    if (win.id === WORKSPACE_WINDOW_ID) {
      // CYP-650: operator-only roster + audit. The window only exists for an operator (added above), so no in-panel
      // gate is needed — the component just renders the operator-only data.
      return (
        <WorkspaceRosterPanel
          members={workspaceMembers}
          audit={operatorAudit}
          membersLoadError={workspaceMembersLoadError}
          onRetryMembers={loadWorkspaceMembers}
          auditLoadError={operatorAuditLoadError}
          onRetryAudit={loadOperatorAudit}
        />
      )
    }
    if (win.id === PROJECT_MGMT_WINDOW_ID) {
      // CYP-651: project switch + management (operator-gated inside the panel). Non-optimistic mutations; the
      // destructive delete is name-echo-armed and the active/last project have delete disabled with an inline reason.
      return (
        <ProjectManagementPanel
          projects={projectsView}
          operator={operator}
          loadError={projectsLoadError}
          onRetryLoad={loadProjects}
          onCreate={onCreateProject}
          onSwitch={onSwitchProject}
          onRename={onRenameProject}
          onDelete={onDeleteProject}
        />
      )
    }
    if (win.id === CHANNEL_SHARE_WINDOW_ID) {
      // CYP-659: cross-project channel-share. present-but-disabled: badge/status read-tier for everyone; authorize/
      // revoke operator-gated. Non-optimistic — the panel re-syncs from the server ChannelShareView echo.
      return (
        <ChannelSharePanel
          channels={channels}
          projects={projectsView?.projects ?? []}
          operator={operator}
          getShare={(cid) => hubRepo.getChannelShare(cid)}
          onShare={(cid, sharedWith) => hubRepo.shareChannel(cid, sharedWith)}
          onUnshare={(cid) => hubRepo.unshareChannel(cid)}
        />
      )
    }
    if (win.id === PRODUCT_LEAD_WINDOW_ID) {
      // CYP-464: operator-gated report surface. The panel itself fail-closes to the gate-hint for a non-operator
      // (no trigger/list/fetch); for an operator it lists snapshots + triggers new ones.
      return (
        <ProductLeadPanel
          operator={operator}
          fetchReports={() => hubRepo.fetchReports()}
          generateReport={(type: ReportType) => hubRepo.generateReport({ type })}
        />
      )
    }
    if (win.id === COMPACT_WINDOW_ID) {
      // CYP-649: compact-orchestration. Status is read-tier (all users see the facts); editing is operator-only
      // (member = read-only chip + gate hint). Non-optimistic + fail-closed (unknown status → facts absent).
      return <CompactPanel status={compactStatus} operator={operator} onSetConfig={onSetCompactConfig} />
    }
    if (win.id === SETTINGS_WINDOW_ID) {
      // CYP-453: the Settings level frames the PROJECT config (repo + framed API-key). present-but-disabled for a
      // non-operator (NOT omitted): the masked/status displays leak nothing; the sections disable their inputs + show
      // the gate hint. Personal prefs (theme/history) stay in the app bar, ungated — never dragged in here (§0).
      return (
        <SettingsPanel
          operator={operator}
          repoConfig={repoConfig}
          onSaveRepo={onSaveRepo}
          apiKeyView={apiKeyView}
          onSaveApiKey={onSaveApiKey}
          getReprovisionPreview={() => hubRepo.getReprovisionPreview()}
          repoLoadError={repoConfigLoadError}
          onRetryRepo={loadRepoConfig}
          apiKeyLoadError={apiKeyLoadError}
          onRetryApiKey={loadApiKey}
        />
      )
    }
    if (win.id === EVENT_WINDOW_ID) {
      // CYP-432 defence-in-depth: never render bodies for a non-operator (even if a window somehow exists), and
      // fail closed to a locked placeholder when access was revoked (WS 1008) — never leave stale bodies showing.
      if (!operator) {
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
      // CYP-448: pause freezes the visible tail at the pause tip; newer events keep buffering (bufferedCount).
      const tail = tailView(eventLog, eventsPaused, eventsPausedAtSeq)
      return (
        <EventLogView
          events={tail.visible}
          caughtUp={eventsCaughtUp}
          trimmed={eventsTrimmed}
          paused={eventsPaused}
          bufferedCount={tail.bufferedCount}
          onTogglePause={toggleEventsPause}
        />
      )
    }
    if (win.id === EVENT_BROWSE_WINDOW_ID) {
      // CYP-452 defence-in-depth: like the event log, never render the bodies-carrying Browse for a non-operator.
      if (!operator) {
        return (
          <p className="event-log-operator-only" data-testid="event-browse-operator-only">
            Der Ereignis-Browser ist nur für Operatoren verfügbar.
          </p>
        )
      }
      return (
        <EventBrowsePanel
          getEvents={(f, after, limit) => hubRepo.getEvents(f, after, limit)}
          agentIds={roster.map((a) => a.id)}
          projects={projectsView?.projects ?? []}
          activeProjectId={projectsView?.activeProjectId ?? ''}
        />
      )
    }
    if (win.id === COMM_WINDOW_ID) {
      const messages = [...(messagesByChannel.get(selectedChannelId ?? '') ?? [])].sort(byOrder)
      return (
        <CommPanel
          channels={channels}
          selectedChannelId={selectedChannelId}
          onSelectChannel={setSelectedChannelId}
          messages={messages}
          senderRole={senderRole}
          readState={unreadView}
          unreadDividerIndex={selectedChannelId === null ? null : firstUnreadIndex(messages.map((d) => d.message), unreadView, selectedChannelId)}
          messagesByChannel={messagesByChannel}
          connection={commConnection}
          canWrite={null}
          sendError={commSendError}
          onSend={onSendComm}
          historySize={historySize}
          channelsLoadError={channelsLoadError}
          onRetryChannels={loadChannels}
          messagesLoadError={messagesLoadError}
          onRetryMessages={() => {
            if (selectedChannelId !== null) loadMessages(selectedChannelId)
          }}
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
          operator={operator}
          onCommit={commitAcl}
          error={aclError}
          loadError={channelsLoadError || aclEntriesLoadError}
          onRetryLoad={() => {
            loadChannels()
            loadAcl()
          }}
        />
      )
    }
    if (win.id.startsWith(AGENT_PREFIX)) {
      const agentId = win.id.slice(AGENT_PREFIX.length)
      return (
        <AgentWindow
          agentId={agentId}
          setupBlocked={setupPrompt}
          wsBase={cfg.wsBase}
          token={cfg.token}
          operator={operator}
          terminalState={terminalStateByAgent.get(agentId) ?? 'MEDIATED'}
          terminalControl={terminalControlByAgent.get(agentId)}
          onRequestMode={onRequestMode}
          lifecycleState={runStateByAgent.get(agentId) ?? 'UNKNOWN'}
          lifecyclePending={lifecyclePending.get(agentId)}
          lifecycleError={lifecycleError.get(agentId) ?? null}
          lifecycleErrorCode={errorCodeByAgent.get(agentId)}
          onLifecycle={onLifecycle}
          socketDeps={socketDeps}
        />
      )
    }
    return null
  }

  // Per-window title-bar badge. CYP-641 (agent activity), CYP-646 (comm Count / event Severity — the non-feed
  // channels of the CMP tri-badge). Each is fail-closed (null → no accessory) and the Count/Severity are focus-gated
  // (a focused window has "seen" its activity).
  const titleAccessoryFor = (win: WindowState): React.ReactNode => {
    if (win.id === COMM_WINDOW_ID) {
      const badge = commCountBadge(commUnread, focusedWindowId === COMM_WINDOW_ID)
      return badge === null ? null : <WindowBadge title={win.title} badge={badge} />
    }
    if (win.id === EVENT_WINDOW_ID) {
      // The event window exists only for an operator (added operator-gated), so this never renders for a member.
      const badge = eventSeverityBadge(tailMaxSeverity, focusedWindowId === EVENT_WINDOW_ID)
      return badge === null ? null : <WindowBadge title={win.title} badge={badge} />
    }
    if (win.id.startsWith(AGENT_PREFIX)) {
      const agentId = win.id.slice(AGENT_PREFIX.length)
      const activity = deriveWindowActivity({
        runState: runStateByAgent.get(agentId),
        busy: busyByAgent.get(agentId) ?? false,
        contextTokens: contextTokensByAgent.get(agentId),
      })
      return activity === null ? null : <WindowActivityBadge title={win.title} activity={activity} />
    }
    return null
  }

  if (showGate) {
    // The gate covers the workspace while setup is genuinely outstanding. Skipping leads to the REAL workspace
    // carrying the §3.1 banner + agent-start gating (condition a) — never a clean-looking workspace.
    return (
      <div className="app-root" data-testid="app-root">
        <FirstRunGate
          inputs={firstRunInputs}
          apiKeyStep={
            <ApiKeyPanel
              view={apiKeyView}
              operator={operator}
              onSave={onSaveApiKey}
              loadError={apiKeyLoadError}
              onRetryLoad={loadApiKey}
            />
          }
          repoStep={
            <>
              {/* CYP-735 §3.3 — the live clone lifecycle beneath the repo form: this is what lifts the step from
                  "gespeichert" to "done", and what keeps it honestly short of done until the server says CLONED_OK. */}
              <CloneStatusRow view={clone} onRetry={loadRepoConfig} />
              <RepoSection
              operator={operator}
              config={repoConfig}
              onSave={onSaveRepo}
              getReprovisionPreview={() => hubRepo.getReprovisionPreview()}
                loadError={repoConfigLoadError}
                onRetryLoad={loadRepoConfig}
              />
            </>
          }
          onSkip={() => {
            setSetupSkipped()
            setSetupSkippedState(true)
          }}
          onRetry={loadRepoConfig}
          loadErrorSurface={<LoadErrorRetry testId="firstrun.loadError" onRetry={loadRepoConfig} />}
        >
          {null}
        </FirstRunGate>
      </div>
    )
  }

  return (
    <div className="app-root" data-testid="app-root">
      {/* CYP-642 capacity pill (present only when there is capacity data — null≠0/0) + CYP-643 theme toggle (always
          present, personal pref). The bar always renders now (the toggle is unconditional); the overload banner sits
          full-width below it; both are auto-height, the desktop takes the rest (flex). */}
      <div className="workspace-bar" data-testid="workspace-bar">
        {capacity != null && <CapacityPill capacity={capacity} />}
        {/* CYP-651: the always-visible project switcher (leading; the workspace context). */}
        <ProjectSwitcher projects={projectsView} operator={operator} onSwitch={onSwitchProject} />
        <div className="workspace-bar-spacer" />
        {/* CYP-645 composer-history stepper + CYP-643 theme toggle — same personal, ungated trailing-slot family. */}
        <ComposerHistoryStepper size={historySizeValue} onChange={onHistorySizeChange} />
        <ThemeToggle mode={themeMode} onChange={onThemeChange} />
      </div>
      {/* CYP-733 — the CYP-676 tier disclosure, finally wired. It lives in the app chrome rather than inside the
          comm window on purpose: the spec requires it to be ALWAYS VISIBLE, and a window can be closed. Its own
          strip (like the overload banner) rather than in the toolbar row, because the disclosure is a full
          sentence and must never be squeezed or truncated — a shortened honesty line is a softened one.
          The tier is derived from the live connection, and is UNKNOWN whenever the connection is not up: we do not
          describe the security of a connection that is not carrying traffic. It can never render NATIVE. */}
      <div className="workspace-tier" data-testid="workspace-tier">
        <RemoteSecurityTierBadge tier={gatewayTierFor(commConnection)} />
      </div>
      {/* CYP-735 §3.1 — a STANDING condition (not dismissable): agents cannot start until the hub is set up.
          Shown only on a server-stated configured:false; a load error surfaces its own error+retry instead. */}
      {setupPrompt && (
        <UnconfiguredBanner
          onSetUp={() => {
            // Condition (b): the resume path returns to the GUIDANCE, not merely to a settings window — a user who
            // skipped and now wants help should get the guided flow back, not be dropped at a form.
            clearSetupSkipped()
            setSetupSkippedState(false)
          }}
        />
      )}
      {/* CYP-758 — ERROR OVERRIDES SKIP. The guided gate (which carries a config-load error when not skipped) is
          hidden once skipped; without this, `error ∧ skipped` showed nothing — a failed config read reading as
          "all clear" (the collapse setupStatus.ts forbids). So a standing, NON-modal error+retry cue surfaces the
          load failure skip-independently — WITHOUT re-raising the skipped gate (that would re-nag). Deliberately
          DISTINCT from UnconfiguredBanner ("hub not set up"): this says "setup status not loadable — retry", a
          different fact, its own testid. Only reachable here when skipped (error ⇒ gate mode 'loading' ⇒ this
          branch runs only if setupSkipped), so it never double-surfaces with the gate. */}
      {setupErrorCueVisible(setupStatus, setupSkipped) && (
        <div className="workspace-setup-error" data-testid="workspace.setupError.strip">
          <LoadErrorRetry
            testId="workspace.setupError"
            onRetry={loadRepoConfig}
            message="Einrichtungs-Status konnte nicht geladen werden."
          />
        </div>
      )}
      {overloadVisible(overloadActive, overloadDismissed, capacity) && (
        <OverloadBanner onDismiss={() => setOverloadDismissed(true)} />
      )}
      {/* CYP-641 titleAccessory (activity badge) rides on each WindowFrame, inside the CYP-642 desktop region. */}
      <div className="workspace-desktop">
        <WindowHost>
          {(win) => (
            <WindowFrame window={win} titleAccessory={titleAccessoryFor(win)}>
              {renderContent(win)}
            </WindowFrame>
          )}
        </WindowHost>
      </div>
    </div>
  )
}
