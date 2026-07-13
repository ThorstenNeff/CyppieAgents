package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tneff.cyppieagents.connector.CapabilityPanel
import com.tneff.cyppieagents.connector.ConnectorCapabilityHttpRepository
import com.tneff.cyppieagents.connector.ConnectorCapabilityRepository
import com.tneff.cyppieagents.connector.ConnectorCapabilityViewModel
import com.tneff.cyppieagents.connector.ConnectorPicker
import com.tneff.cyppieagents.connector.ConnectorSelectionHttpRepository
import com.tneff.cyppieagents.connector.ConnectorSelectionRepository
import com.tneff.cyppieagents.connector.ConnectorSelectionViewModel
import com.tneff.cyppieagents.model.ConnectorKind
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tneff.cyppieagents.workspace.CapacityReadout
import com.tneff.cyppieagents.workspace.CapacityViewModel
import com.tneff.cyppieagents.workspace.HubCapacitySource
import com.tneff.cyppieagents.workspace.LiveHubCapacitySource
import com.tneff.cyppieagents.workspace.OverloadBanner
import com.tneff.cyppieagents.workspace.RemoteContextBanner
import com.tneff.cyppieagents.workspace.StubHubCapacitySource
import com.tneff.cyppieagents.auth.UserTier
import com.tneff.cyppieagents.workspace.WorkspaceHttpRepository
import com.tneff.cyppieagents.workspace.WorkspaceRepository
import com.tneff.cyppieagents.workspace.WorkspaceRosterPanel
import com.tneff.cyppieagents.workspace.WorkspaceRosterViewModel
import com.tneff.cyppieagents.compact.CompactHttpRepository
import com.tneff.cyppieagents.compact.CompactPanel
import com.tneff.cyppieagents.compact.CompactRepository
import com.tneff.cyppieagents.compact.CompactEventsViewModel
import com.tneff.cyppieagents.compact.CompactViewModel
import com.tneff.cyppieagents.workspace.isOperatorAccess
import com.tneff.cyppieagents.workspace.showRoster
import com.tneff.cyppieagents.acl.AclApi
import com.tneff.cyppieagents.acl.AclLiveSource
import com.tneff.cyppieagents.acl.AclPanel
import com.tneff.cyppieagents.acl.AclRepository
import com.tneff.cyppieagents.acl.AclViewModel
import com.tneff.cyppieagents.acl.AclWsClient
import com.tneff.cyppieagents.agentview.AgentLifecycleApi
import com.tneff.cyppieagents.agentview.AgentLifecycleLiveSource
import com.tneff.cyppieagents.agentview.AgentLifecycleRepository
import com.tneff.cyppieagents.agentview.AgentLifecycleSource
import com.tneff.cyppieagents.agentview.BusyStateLiveSource
import com.tneff.cyppieagents.agentview.BusyStateSource
import com.tneff.cyppieagents.agentview.BusyStateViewModel
import com.tneff.cyppieagents.agentview.ModeRepository
import com.tneff.cyppieagents.agentview.ModeHttpRepository
import com.tneff.cyppieagents.agentview.TerminalControlLiveSource
import com.tneff.cyppieagents.agentview.TerminalControlSource
import com.tneff.cyppieagents.agentview.TerminalControlStateViewModel
import com.tneff.cyppieagents.agentview.TokenUsageLiveSource
import com.tneff.cyppieagents.agentview.TokenUsageSource
import com.tneff.cyppieagents.agentview.TokenUsageViewModel
import com.tneff.cyppieagents.agentview.AgentSession
import com.tneff.cyppieagents.agentview.AgentStatus
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.AgentWsClient
import com.tneff.cyppieagents.agentview.MappingAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.CommLiveSource
import com.tneff.cyppieagents.comm.CommPanel
import com.tneff.cyppieagents.comm.CommRepository
import com.tneff.cyppieagents.comm.CommViewModel
import com.tneff.cyppieagents.comm.CommWsClient
import com.tneff.cyppieagents.comm.HttpWritableChannelsApi
import com.tneff.cyppieagents.comm.WritableChannelsApi
import com.tneff.cyppieagents.eventlog.EventBrowsePanel
import com.tneff.cyppieagents.eventlog.EventBrowseViewModel
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventTailPanel
import com.tneff.cyppieagents.eventlog.EventTailViewModel
import com.tneff.cyppieagents.eventlog.EventsApi
import com.tneff.cyppieagents.eventlog.EventsApiClient
import com.tneff.cyppieagents.eventlog.EventsWsClient
import com.tneff.cyppieagents.agentmgmt.AgentManagementHttpRepository
import com.tneff.cyppieagents.agentmgmt.AgentManagementPanel
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.project.HttpProjectRepository
import com.tneff.cyppieagents.crossproject.CrossProjectControls
import com.tneff.cyppieagents.crossproject.CrossProjectRepository
import com.tneff.cyppieagents.crossproject.CrossProjectViewModel
import com.tneff.cyppieagents.crossproject.HttpCrossProjectRepository
import com.tneff.cyppieagents.project.ProjectRepository
import com.tneff.cyppieagents.project.ProjectSwitcherBar
import com.tneff.cyppieagents.project.ProjectViewModel
import com.tneff.cyppieagents.project.ProjectVmStoreManager
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.report.ProductLeadPanel
import com.tneff.cyppieagents.report.ProductLeadViewModel
import com.tneff.cyppieagents.report.ReportHttpRepository
import com.tneff.cyppieagents.report.ReportRepository
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.SettingsPanel
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.settings.ConfigHttpRepository
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.agentsettings.AgentSettingsPanel
import com.tneff.cyppieagents.agentsettings.AgentSettingsViewModel
import com.tneff.cyppieagents.agentsettings.ClaudeMdHttpApi
import com.tneff.cyppieagents.agentsettings.rememberImagePicker
import com.tneff.cyppieagents.ui.AgentAvatarView
import com.tneff.cyppieagents.ui.LocalAvatarBaseUrl
import com.tneff.cyppieagents.ui.LocalAvatarImageLoader
import com.tneff.cyppieagents.ui.SenderPalette
import com.tneff.cyppieagents.ui.ComposerHistorySizeStepper
import com.tneff.cyppieagents.ui.DEFAULT_COMPOSER_HISTORY_SIZE
import com.tneff.cyppieagents.ui.ThemeMode
import com.tneff.cyppieagents.ui.ThemeModeToggle
import com.tneff.cyppieagents.ui.TitleBarColors
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.tneff.cyppieagents.net.hub.HubTransport
import com.tneff.cyppieagents.net.hub.TransportModeResolver
import com.tneff.cyppieagents.terminal.TerminalView
import com.tneff.cyppieagents.terminal.WsTerminalSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_mgmt_title
import kmpcyppieagents.app.shared.generated.resources.agent_ready_notice
import kmpcyppieagents.app.shared.generated.resources.report_title
import kmpcyppieagents.app.shared.generated.resources.project_loading
import kmpcyppieagents.app.shared.generated.resources.compact_window_title
import kmpcyppieagents.app.shared.generated.resources.settings_title
import kmpcyppieagents.app.shared.generated.resources.workspace_members_title
import org.jetbrains.compose.resources.stringResource

private const val COMM_WINDOW_ID = "comm"
private const val ACL_WINDOW_ID = "acl"
private const val SETTINGS_WINDOW_ID = "settings"
private const val AGENT_MGMT_WINDOW_ID = "agentMgmt"
private const val PRODUCT_LEAD_WINDOW_ID = "productLead"
private const val EVENTLOG_BROWSE_WINDOW_ID = "eventlog"
private const val EVENTLOG_TAIL_WINDOW_ID = "eventtail"
private const val ROSTER_WINDOW_ID = "workspaceRoster"
private const val COMPACT_WINDOW_ID = "compact"

/**
 * The app shell (CYP-15): a "desktop" of floating windows. Marries the window manager (CYP-10) with
 * the agent renderer (CYP-6, one [AgentWindow] per agent over the live `/ws/agent` stream) and the
 * comm panel (CYP-21, one [CommPanel] window over comm REST + a stub live source).
 *
 * Hub URL + tokens come from [ShellConfig] (CYP-28) — never hardcoded. The JVM/desktop default reads
 * the environment and falls back to the local server on the CYP-24 port (8787); the privileged
 * operator token is supplied at runtime, never baked. The single shared [HttpClient] backs both the
 * agent WebSockets and the comm REST calls. Agent session and comm data are injectable so tests stay
 * hermetic without touching the network.
 */
/**
 * CYP-333/381: the Terminal connection is **live**. With the CYP-355 hand-off motor merged, TERMINAL mode attaches
 * this window to the motor's interactive `claude --resume` session (the agent's real, same session) over
 * `/ws/terminal`. The `bash -l` worktree shell (CYP-348 — `git status`/`ls`/inspect, BootOrchestrator→PtyManager
 * default) remains the interim fallback for when no motor session is live, so **no client mode parameter is needed**.
 * The Shell segment binds a real [WsTerminalSession] to the Desktop `TerminalView`. Bundles with CYP-361 (PtyManager
 * ctor hardening: no command-less 2-claude vector). Left as a const kill-switch (flip to `false` to gate) for operability.
 */
private const val WORKTREE_SHELL_LIVE_ENABLED = true

@Composable
fun AgentShell(
    modifier: Modifier = Modifier,
    /** Hub URL + tokens; `null` → the platform default ([defaultShellConfig]). */
    config: ShellConfig? = null,
    /** The signed-in user's workspace tier (CYP-186). Fail-closed default MEMBER; OPERATOR (or a break-glass
     *  operator token) unlocks the mutation surfaces. NOT the Agent-Role. */
    tier: UserTier = UserTier.MEMBER,
    /** Override the per-agent session (tests inject a stub); `null` → the live `/ws/agent` session. */
    sessionFactory: ((String) -> AgentSession)? = null,
    /** Override the comm data port (tests inject a fake); `null` → the comm REST repository. */
    commApi: CommApi? = null,
    /** Override the comm live source (tests inject a stub); `null` → the live `/ws/comm` adapter. */
    commLiveSource: CommLiveSource? = null,
    /** CYP-273: override the writable-channels port (tests inject a fake); `null` → the live
     *  `GET /api/channels/writable` HTTP client ([HttpWritableChannelsApi]) that drives the composer enable/disable. */
    writableChannelsApi: WritableChannelsApi? = null,
    /** Override the event-log REST port (tests/dev inject a stub); `null` → the stub until CYP-39. */
    eventsApi: EventsApi? = null,
    /** Override the event-log live source (tests/dev inject a stub); `null` → the stub until CYP-40. */
    eventsLiveSource: EventLiveSource? = null,
    /** Override the ACL data port (tests/dev inject a fake); `null` → the ACL REST repository (CYP-48). */
    aclApi: AclApi? = null,
    /** Override the ACL live source (tests/dev inject a stub); `null` → the live `/ws/comm` ACL adapter. */
    aclLiveSource: AclLiveSource? = null,
    /** Override the agent lifecycle action port (CYP-73); `null` → the in-memory stub until the REST client lands. */
    lifecycleApi: AgentLifecycleApi? = null,
    /** Override the agent lifecycle state source (CYP-73); `null` → the in-memory stub until `/ws/lifecycle` lands. */
    lifecycleSource: AgentLifecycleSource? = null,
    /** Override the CYP-316 context-token source (`/ws/token-usage`); `null` → the live source. Tests inject a stub. */
    tokenUsageSource: TokenUsageSource? = null,
    /** Override the CYP-324 busy-state source (`/ws/busy-state`); `null` → the live source. Tests inject a stub. */
    busyStateSource: BusyStateSource? = null,
    /** Override the CYP-354 terminal-control source (`/ws/terminal-state`, read-only mirror); `null` → the live
     *  source. Tests inject a stub. */
    terminalControlSource: TerminalControlSource? = null,
    /** Override the CYP-381 hand-off command port (`POST /api/agents/{id}/mode`); `null` → the live
     *  [ModeHttpRepository] (CYP-355 motor). Tests inject a stub / rejecting / holding variant to exercise the
     *  confirm + reject + IDLE-gate paths without a live server; `:app:webAppDemo` injects [StubModeRepository]. */
    modeRepository: ModeRepository? = null,
    /** Override the CYP-326 compact-orchestration port; `null` → the in-memory stub until Backend's Milestone-C
     *  endpoints land (`GET /api/compact/status` · `POST /api/compact/config`), then the live HTTP repo. */
    compactRepository: CompactRepository? = null,
    /** Override the project-settings data port (CYP-84/85); `null` → the `ConfigHttpRepository` (CYP-96 landed). */
    configRepository: ConfigRepository? = null,
    /**
     * Override the agent-management data port (CYP-86/87/88); `null` → the `AgentManagementHttpRepository`
     * (CYP-97 landed). It also drives the **dynamic agent-window list**, so a backendless caller that leaves
     * this `null` renders no agent windows at all rather than failing loudly (CYP-339).
     */
    agentManagementRepository: AgentManagementRepository? = null,
    /** Override the Product-Lead report data port (CYP-90); `null` → the in-memory stub until CYP-89 lands. */
    reportRepository: ReportRepository? = null,
    /** Override the cross-project authorization port (CYP-93); `null` → the in-memory stub until the backend seam lands. */
    crossProjectRepository: CrossProjectRepository? = null,
    /** Override the project-lifecycle data port (CYP-91/92); `null` → the in-memory stub until the registry seam lands. */
    projectRepository: ProjectRepository? = null,
    /** Override the connector-capability read port (CYP-123); `null` → the live read against `Agent.capabilities`. */
    connectorCapabilityRepository: ConnectorCapabilityRepository? = null,
    /** Override the connector-selection write port (CYP-123 Inc 2); `null` → the in-memory stub until CYP-122. */
    connectorSelectionRepository: ConnectorSelectionRepository? = null,
    /** Override the workspace-roster read port (CYP-186 BE3a); `null` → the live `GET /api/workspace/members`. */
    workspaceRepository: WorkspaceRepository? = null,
    /** CYP-417 — the content-free hub-capacity feed (S-G ResourceGovernor); `null` → the [StubHubCapacitySource]
     *  (unknown capacity ⇒ readout absent, no banner) until Backend's `CAPACITY_CHANGED`/`SPAWN_REJECTED` seam lands. */
    capacitySource: HubCapacitySource? = null,
    /** CYP-249 — injectable per-project ViewModelStore LRU (test seam: observe warm/evicted projects). `null` → the
     *  shell owns one (K=3). Prod never injects; tests pass one to assert the K-cap + eviction across switches. */
    projectVmStores: ProjectVmStoreManager? = null,
    /** CYP-188 — the signed-in user's native session credential (`X-Session-Token`) for the shared HTTP/WS client,
     *  so a session-only user (no operator token) authenticates its reads/sockets. `null`/absent → none (a browser
     *  session rides its same-origin `ory_kratos_session` cookie; the operator token stays break-glass). */
    sessionToken: () -> String? = { null },
    /** CYP-411 — the hub connection transport (Epic CYP-395 S-H). `null` → the Phase-1 [LocalHubTransport] built
     *  from [config]'s [ShellConfig.hubEndpoint] (behaviour-identical to the pre-seam direct-connect). Tests inject
     *  a stub/local transport (the mode-flip Stub path); every REST repo + WS live-source below reads its
     *  `httpBaseUrl`/`wsBaseUrl`/`httpClient` from here, so they are mode-blind. */
    transport: HubTransport? = null,
    /** CYP-268 R3 — the current app theme mode (owned + persisted by the App.kt seam). Default SYSTEM keeps the
     *  R1 follow-system behaviour and leaves every existing call site/test unchanged (the toggle is opt-in chrome). */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** CYP-268 R3 — invoked when the user picks a mode from the switcher-bar toggle; the seam persists + recolours. */
    onThemeModeChange: (ThemeMode) -> Unit = {},
    /** CYP-387 — the ONE global composer input-history size N (owned + persisted by the App.kt seam). Default 20;
     *  `0` = off. Mirrored live onto every open agent VM so a change takes effect without wiping content. */
    composerHistorySize: Int = DEFAULT_COMPOSER_HISTORY_SIZE,
    /** CYP-387 — invoked when the user changes N in the settings stepper; the seam clamps (0..200) + persists. */
    onComposerHistorySizeChange: (Int) -> Unit = {},
    /** CYP-527 — the remote-operating context signal: the connected remote hub's display name, or `null` when
     *  operating locally / not yet CONNECTED / torn down. Non-null ⇒ the persistent [RemoteContextBanner] WARN
     *  strip mounts (`%1$s` = this name). Bound by the composition root to `RemoteSessionState.conn == CONNECTED`
     *  on the remote path (NOT `RemoteHubConnectGate.entered`, which fires locally). Default null = local, no banner.
     *  (Threaded as the hub name — not a bare Boolean — because the UIUX-locked copy interpolates the hub name.) */
    remoteContext: String? = null,
) {
    val cfg = remember { config ?: defaultShellConfig() }

    // CYP-186 — the ONE gate-source swap (hybrid access-model C, Auftraggeber 2026-07-02): operator surfaces are
    // editable when the user's tier is OPERATOR OR a break-glass operator token is present. This one derivation
    // replaces every former `cfg.operatorToken != null` editable/canControl line below — no panel changed, only the
    // source. Fail-closed: an unknown tier is MEMBER (and no token) ⇒ read-only.
    val isOperator = isOperatorAccess(tier, cfg.operatorToken)

    // i18n window titles for the system windows the designs name (other titles are hardcoded today).
    val settingsTitle = stringResource(Res.string.settings_title)
    val agentMgmtTitle = stringResource(Res.string.agent_mgmt_title)
    val productLeadTitle = stringResource(Res.string.report_title)
    val rosterTitle = stringResource(Res.string.workspace_members_title)
    val compactTitle = stringResource(Res.string.compact_window_title)

    // CYP-411 (Epic CYP-395 S-H) — the hub connection transport seam. Phase 1 = LocalHubTransport (direct HTTP/WS
    // on the hub's endpoint, from ShellConfig), which OWNS + builds the one shared WS+HTTP client exactly as before
    // (sharedWsHttpClient(sessionToken): CYP-115 keep-alive + X-Session-Token + same-origin credentials). Every repo
    // / live-source below reads httpBaseUrl/wsBaseUrl/httpClient from the transport, so it is mode-blind. Closed when
    // the shell leaves composition. `httpClient` stays a local alias so the ~25 call sites + remember-keys are
    // unchanged (pure refactor). TransportModeResolver.defaultMode() = LOCAL in Phase 1; S-J drives the choice.
    val resolvedTransport = remember {
        transport ?: TransportModeResolver.create(TransportModeResolver.defaultMode(), cfg.hubEndpoint(), sessionToken)
    }
    val httpClient = resolvedTransport.httpClient
    DisposableEffect(Unit) { onDispose { resolvedTransport.close() } }

    // CYP-216: an authed Coil ImageLoader over the SHARED client — the avatar serve endpoint
    // (GET /api/agents/{id}/avatar) is participant-gated, so image GETs must carry the same
    // session/operator credential. Provided to the shared AgentAvatarView via CompositionLocals.
    val avatarPlatformContext = LocalPlatformContext.current
    val avatarImageLoader = remember(httpClient, avatarPlatformContext) {
        ImageLoader.Builder(avatarPlatformContext)
            .components { add(KtorNetworkFetcherFactory(httpClient = { httpClient })) }
            .build()
    }

    // Project switcher + management (CYP-91/92): ONE VM backs the top-level bar and the management overlay.
    // Now the LIVE REST client against the /api/projects registry endpoints (stub→real swap, no UI/VM change).
    // Switching + mutations are operator-gated (server also enforces; fail-closed).
    // CYP-246: HOISTED FIRST so `activeProjectId` scopes every per-project data VM below. Backend partitions
    // the agent set per project (rescope swaps the active slice → `/api/agents` returns the switched-to
    // project's agents, fresh = empty); the client's job is to RE-SCOPE on switch. We do that by appending
    // `activeProjectId` to each verified-stale, project-scoped VM's key → `viewModel()` hands back a FRESH
    // instance on switch → its `init` re-fetches in the new scope → managedAgents/channels/… update → the
    // windows (derived from managedAgents) rebuild. This is the "live re-instancing" the shell previously
    // deferred. Scoped, not a rasur: only the audited-stale VMs are re-keyed (see the ticket audit note);
    // the workspace-scoped roster VM (team-wide, project-independent) stays constant-keyed — re-keying it
    // would be a pointless reload of identical data.
    val resolvedProjectRepo = remember(projectRepository, httpClient, cfg) {
        projectRepository ?: HttpProjectRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val projectVm = viewModel(key = "projectSwitcher") {
        ProjectViewModel(resolvedProjectRepo, editable = isOperator)
    }
    // CYP-94: the project registry feeds the event-log cross-project filter (operator-only surfaces).
    val projectState = projectVm.state.collectAsState().value
    // CYP-246: the active project id — the re-key suffix for every per-project VM below.
    val activeProjectId = projectState.activeProjectId

    // CYP-249 loading-gate (Option A, PO-ratified): the project-scoped desktop (its VMs + sockets + per-project
    // store) is composed ONLY once the active project is CONFIRMED (`!loading`). Before the initial reload the VM
    // seeds `activeProjectId="default"`; composing the desktop then would mint a phantom "default" per-project store
    // (fetching VMs + sockets) that lingers until it self-evicts. `loading` flips true→false exactly ONCE (the
    // initial reload — switches never re-set it), so this gate guards only the FIRST mount: no switch regression.
    // The workspace-scoped ProjectSwitcherBar stays OUTSIDE the gate → the bar (with "loading…") shows during the wait.
    Column(modifier = modifier.fillMaxSize()) {
      // CYP-92: the project switcher is a top-level bar ABOVE the window host (always visible, context-independent).
      // CYP-186: the persistent role indicator rides here; operatorName is BE1-pending (null omits the "Operator:" line).
      // CYP-268 R3: the app-global theme toggle rides the bar's trailing slot — a client-local, per-user preference
      // (NOT operator-gated, NOT project-scoped; it follows no project switch). Stays OUTSIDE the loading gate.
      // CYP-417: the workspace-scoped hub-capacity VM (shell store, like projectVm). LIVE against Backend's
      // ResourceGovernor seam (Stub→real swap, no UI/VM change): GET /api/capacity snapshot ⊕ CAPACITY_CHANGED /
      // SPAWN_REJECTED off /ws/events. Advisory-only — the server owns the hard fail-closed gate (H5); the pill/
      // banner just reflect it. Operator-token-bound like every sibling live source. `:app:webAppDemo` injects a
      // StubHubCapacitySource for the Maestro flows (readout absent + no banner = honest cold-start).
      val defaultCapacitySource = remember(httpClient, cfg) {
          LiveHubCapacitySource(httpClient, resolvedTransport.httpBaseUrl, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
      }
      val capacityVm = viewModel(key = "hubCapacity") { CapacityViewModel(capacitySource ?: defaultCapacitySource) }
      ProjectSwitcherBar(
          projectVm, tier = tier, operatorName = null,
          capacityReadout = { CapacityReadout(capacityVm.capacity.collectAsState().value) },
          overloadBanner = {
              if (capacityVm.overloadVisible.collectAsState().value) OverloadBanner(onDismiss = capacityVm::dismissOverload)
          },
          // CYP-527: the remote-operating context WARN banner — mounted iff `remoteContext` (the connected remote
          // hub's name) is non-null. Absent in Local mode / before CONNECTED / after teardown (the caller's gate).
          remoteContextBanner = {
              remoteContext?.let { RemoteContextBanner(hubName = it) }
          },
          // CYP-268 R3 theme toggle + CYP-387 input-history size stepper — both personal, ungated, non-project
          // preferences ride the bar's trailing slot together.
          trailing = { compact ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  ComposerHistorySizeStepper(
                      size = composerHistorySize, onChange = onComposerHistorySizeChange, compact = compact,
                  )
                  ThemeModeToggle(mode = themeMode, onChange = onThemeModeChange, compact = compact)
              }
          },
      )
      if (projectState.loading) {
        ProjectLoadingPlaceholder(modifier = Modifier.weight(1f).fillMaxWidth())
      } else {

    // CYP-249: bound the client's warm per-project socket set to the server's runtime LRU (K=3, see the switch-runtime
    // contract). Each project's project-scoped VMs live in their OWN ViewModelStore (passed as viewModelStoreOwner
    // below); the active + 2 most-recently-hot stay warm (a cheap switch-back, no reconnect), and the least-recently-
    // used beyond K is disposed → its VMs' onCleared() → viewModelScope cancel → its /ws sockets close. Re-entry mints
    // a fresh store → fresh VMs → a clean reconnect + cursor-resume (CYP-198/204) — the normal re-entry path, not an
    // error. The workspace-scoped projectSwitcher (above) + roster (below) OMIT this owner → they stay on the always-
    // alive shell store, never evicted. The CYP-246 "-$activeProjectId" key suffix is retained as defense-in-depth
    // (isolation holds even if this owner wiring regresses). ownerFor() is a side-effect-free get-or-create (safe in
    // composition); the MRU-promote + destructive eviction run in noteActive() from the post-commit effect.
    val defaultProjectStores = remember { ProjectVmStoreManager() }
    val projectStores = projectVmStores ?: defaultProjectStores
    DisposableEffect(projectStores) { onDispose { projectStores.clearAll() } }
    val projectStoreOwner = remember(activeProjectId) { projectStores.ownerFor(activeProjectId) }
    LaunchedEffect(activeProjectId) { projectStores.noteActive(activeProjectId) }

    // Agent-management VM (CYP-86/87/88): the LIVE REST client against the CYP-97 endpoints (stub→real swap,
    // no UI/VM change). Its agent list (GET /api/agents) drives the **dynamic** window set — an added agent
    // gets a window, a removed one loses it. CYP-246: re-keyed on activeProjectId (the reported-bug VM,
    // definitely stale on switch) so a switch re-instances it → re-fetch → managedAgents reflects the new
    // project's slice (fresh project = 0 agents → 0 agent windows). Repo stays project-agnostic (the SERVER
    // resolves scope from the registry pointer — no client projectId), so only the VM re-instances.
    val resolvedAgentMgmtRepo = remember(agentManagementRepository, httpClient, cfg) {
        agentManagementRepository
            ?: AgentManagementHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val agentMgmtVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "$AGENT_MGMT_WINDOW_ID-$activeProjectId") {
        AgentManagementViewModel(resolvedAgentMgmtRepo, editable = isOperator)
    }
    val agentMgmtState = agentMgmtVm.state.collectAsState().value
    val managedAgents = agentMgmtState.agents

    // CYP-93: cross-project authorization port — now the LIVE client against /api/channels/{id}/share
    // (stub→real swap, no UI/VM change). `sharedWith` derives from the operator's OTHER projects (reach stays
    // per-agent ACL-gated server-side → no over-widen); read live from the project VM so it isn't stale.
    val resolvedCrossProjectRepo = remember(crossProjectRepository, httpClient, cfg, projectVm) {
        crossProjectRepository ?: HttpCrossProjectRepository(
            httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "",
            granteeProjects = {
                val s = projectVm.state.value
                s.projects.map { it.id }.toSet() - s.activeProjectId
            },
        )
    }

    // Dynamic window set (S14): agent windows are derived from the managed agent list; the system
    // windows (comm/acl/settings/agentMgmt/productLead + operator-only event-log) stay static. The
    // window manager re-tiles when the *set* of windows changes (add/remove) — the static path is
    // unaffected, so there is no canvas regress in normal use (position-preservation = tracked CYP-100).
    val windows: List<Pair<String, String>> = managedAgents.map { it.id to it.name } + buildList {
        add(COMM_WINDOW_ID to "Kommunikation")
        // ACL matrix (CYP-48): always present — editable for an operator, read-only partial view for an
        // agent/no-token (ACL-MATRIX.md §4, deliberately NOT an omission like the event-log windows).
        add(ACL_WINDOW_ID to "Zugriffsrechte")
        // Project settings (CYP-84/85) + agent management (CYP-86/87/88): always present, operator-gated.
        add(SETTINGS_WINDOW_ID to settingsTitle)
        add(AGENT_MGMT_WINDOW_ID to agentMgmtTitle)
        // Product-Lead reports (CYP-90): always present; operator-gated/fail-closed — without a token the
        // panel shows only the gate hint (no report), not an omission (PRODUCT-LEAD §3).
        add(PRODUCT_LEAD_WINDOW_ID to productLeadTitle)
        // CYP-326: compact-orchestration gate — always present, VISIBLE TO ALL (§1.2), control operator-gated
        // inside the panel (the auto-compaction effect is team-wide, so members have an honest right to see it).
        add(COMPACT_WINDOW_ID to compactTitle)
        // Operator-only observability windows: offered ONLY with an operator token (omission, not a
        // dead "no access" window — EVENT-LOG-UI §5.6). Live sources swap in at CYP-39/40.
        if (isOperator) {
            add(EVENTLOG_BROWSE_WINDOW_ID to "Event-Log")
            add(EVENTLOG_TAIL_WINDOW_ID to "Live-Tail")
        }
        // CYP-186 roster (§3.2): OPERATOR-only member roster, offered ONLY to a real OPERATOR tier (not the
        // break-glass token — the backend GET is tier-gated). A MEMBER's window set never contains it.
        if (showRoster(tier)) add(ROSTER_WINDOW_ID to rosterTitle)
    }

    // CYP-383: resolve the localized "ready" label here, in composition, and capture it into the session
    // factory — the mapper is pure Kotlin and cannot call stringResource itself (spec §2, §5).
    val readyNotice = stringResource(Res.string.agent_ready_notice)
    val resolveSession: (String) -> AgentSession = sessionFactory ?: { agentId ->
        val ws = AgentWsClient(httpClient, resolvedTransport.wsBaseUrl, agentId, cfg.agentToken(agentId))
        MappingAgentSession(
            source = ws.events, sink = ws::send, connection = ws.connection, readyNoticeText = readyNotice,
        )
    }

    // Operator viewer (CYP-17). The operator token comes from config (runtime), not baked; without it
    // the comm REST / `/ws/comm` calls return 401 (→ empty / Disconnected banner) until one is set.
    val defaultCommApi = remember(httpClient, cfg) {
        CommRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedCommApi = commApi ?: defaultCommApi

    val defaultLiveSource = remember(httpClient, cfg) {
        CommWsClient(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLiveSource = commLiveSource ?: defaultLiveSource

    // CYP-273 (wiring): the live writable-channels port — same bearer/base as the comm REST. Drives the
    // per-channel composer enable/disable (fail-closed to disabled while unknown/erroring).
    val defaultWritable = remember(httpClient, cfg) {
        HttpWritableChannelsApi(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedWritable = writableChannelsApi ?: defaultWritable

    // Event-Log read sources, now LIVE (CYP-39 `/api/events` REST + CYP-40 `/ws/events` WS) — Browse and
    // Live-Tail go live together, operator-only/fail-closed like comm. Injectable so tests stay hermetic;
    // `:app:webAppDemo` injects stubs for the Maestro flows. Windows exist only when operatorToken != null.
    val defaultEventsApi = remember(httpClient, cfg) {
        EventsApiClient(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedEventsApi = eventsApi ?: defaultEventsApi
    val defaultEventsLiveSource = remember(httpClient, cfg) {
        EventsWsClient(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedEventsLiveSource = eventsLiveSource ?: defaultEventsLiveSource

    // ACL matrix sources (CYP-48). Operator-token-bound: PUT is operator-only (server 403s otherwise);
    // GET returns the operator's full view or the agent's partial view. Editable iff an operator token
    // is present (the agent-token read-only repo is a tracked follow-up — see AclViewModel KDoc).
    val defaultAclApi = remember(httpClient, cfg) {
        AclRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedAclApi = aclApi ?: defaultAclApi
    val defaultAclLiveSource = remember(httpClient, cfg) {
        AclWsClient(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedAclLiveSource = aclLiveSource ?: defaultAclLiveSource

    // Content windows (Agent/Comm) carry a composer → wider tiled min-width (CYP-26 §2.2). ACL / Event-Log
    // are reading surfaces and keep the 160 dp floor.
    val contentWindowIds = remember(windows) {
        windows.map { it.first }
            .filterNot {
                it == ACL_WINDOW_ID || it == EVENTLOG_BROWSE_WINDOW_ID ||
                    it == EVENTLOG_TAIL_WINDOW_ID || it == SETTINGS_WINDOW_ID ||
                    it == AGENT_MGMT_WINDOW_ID || it == PRODUCT_LEAD_WINDOW_ID ||
                    it == ROSTER_WINDOW_ID || it == COMPACT_WINDOW_ID
            }
            .toSet()
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Project-settings data port (CYP-84/85): now the LIVE REST client against the CYP-96 endpoints
    // (CYP-85 stub→real swap — no UI/VM change). Injectable so tests stay hermetic. Operator token drives
    // editability + the operator-only PUTs (server enforces 403 too; fail-closed UI; key write-only).
    val defaultConfigRepository = remember(httpClient, cfg) {
        ConfigHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedConfigRepository = configRepository ?: defaultConfigRepository
    // Product-Lead report port (CYP-90): now the LIVE REST client against the CYP-89 endpoints (stub→real
    // swap, no UI/VM change). Injectable so tests stay hermetic. Accessible iff an operator token is present
    // (server enforces 401/403 too; fail-closed — no token → no list, no report, never a partial).
    val defaultReportRepository = remember(httpClient, cfg) {
        ReportHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedReportRepository = reportRepository ?: defaultReportRepository

    // Agent lifecycle (CYP-73). Controls = operator-gated REST (POST /api/agents/{id}/{stop|start|
    // restart}); status display = non-gated (GET /api/agents snapshot + /ws/lifecycle deltas). Operator
    // token drives both the action auth and the participant-gated lifecycle socket. Injectable so tests
    // stay hermetic. Controls are enabled only with an operator token (fail-closed) in the VM/header.
    val defaultLifecycleApi = remember(httpClient, cfg) {
        AgentLifecycleRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLifecycleApi = lifecycleApi ?: defaultLifecycleApi
    val defaultLifecycleSource = remember(httpClient, cfg) {
        AgentLifecycleLiveSource(httpClient, resolvedTransport.httpBaseUrl, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLifecycleSource = lifecycleSource ?: defaultLifecycleSource
    // CYP-316: the per-agent context-token feed (`/ws/token-usage`, participant-gated like lifecycle → same bearer).
    val defaultTokenUsageSource = remember(httpClient, cfg) {
        TokenUsageLiveSource(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedTokenUsageSource = tokenUsageSource ?: defaultTokenUsageSource
    // CYP-324: the per-agent busy feed (`/ws/busy-state`, participant-gated like lifecycle → same bearer).
    val defaultBusyStateSource = remember(httpClient, cfg) {
        BusyStateLiveSource(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedBusyStateSource = busyStateSource ?: defaultBusyStateSource
    // CYP-354: the per-agent terminal-control mode feed (`/ws/terminal-state`, participant-gated like busy → same bearer).
    val defaultTerminalControlSource = remember(httpClient, cfg) {
        TerminalControlLiveSource(httpClient, resolvedTransport.wsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedTerminalControlSource = terminalControlSource ?: defaultTerminalControlSource
    // CYP-381: the hand-off command port. **Real swap done** (CYP-355 motor merged): default = ModeHttpRepository,
    // the live `POST /api/agents/{id}/mode` against BE-2. Non-optimistic by contract — the VM flips only on the
    // server's CONFIRMED (a REJECTED 200 body throws → stay in the old mode). Operator-gated route; a session with
    // no operator token 403s → ModeChangeException → honest error row (fail-closed). Tests inject a stub/rejecting
    // variant via the [modeRepository] override to exercise the reject + IDLE-defer paths without a live server.
    val resolvedModeRepository = remember(modeRepository, httpClient, cfg) {
        modeRepository ?: ModeHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }

    // CYP-55: hoist the per-window VMs to the always-composed shell. Two reasons: (1) each VM opens
    // exactly ONE subscription — a separate badge collector would double-subscribe the cold WS flows
    // (CommVM/AgentVM/EventTailVM already collect); (2) the badge sources must stay live even when the
    // phone pager composes only the active page, so they hang on this always-alive state, not on the
    // per-page lifecycle. windowContent below reuses these very instances (no second viewModel()).
    // One AgentViewModel per managed agent (dynamic, keyed by id → a new agent gets its own VM, a
    // removed one is simply no longer iterated). Reused in windowContent (no second viewModel()).
    val agentVms = LinkedHashMap<String, AgentViewModel>()
    for (managed in managedAgents) {
        val id = managed.id
        // CYP-246: the store key carries activeProjectId (the MAP key stays the bare id for windowContent
        // lookup) — so two projects that reuse an agent id (po/frontend/backend are conventional) never share
        // a retained AgentViewModel + its `/ws/agent` socket across a switch. Follows the re-keyed agent set.
        agentVms[id] = viewModel(viewModelStoreOwner = projectStoreOwner, key = "agent-$activeProjectId-$id") {
            AgentViewModel(
                session = resolveSession(id),
                agentId = id,
                lifecycle = resolvedLifecycleApi,
                lifecycleSource = resolvedLifecycleSource,
                // CYP-381: route the toggle through the non-optimistic hand-off command. `resolvedModeRepository` is
                // the live ModeHttpRepository (`POST /api/agents/{id}/mode`, CYP-355 motor) — the view flips only on
                // the server's CONFIRMED, never optimistically. The IDLE-gate observes the same busy feed the
                // title-bar `*` uses (a take-over issued mid-turn shows "wartet bis Turn fertig", no hijack).
                modeRepository = resolvedModeRepository,
                busySource = resolvedBusyStateSource,
                canControl = isOperator,
            )
        }
    }
    // CYP-387: mirror the ONE global history size N onto every open agent VM on each recomposition. The VM is
    // NOT re-keyed on N (that would rebuild it and wipe its per-agent content) — instead it reads this live var,
    // so raising/lowering N (or `0`=off) takes effect immediately on already-open windows.
    for (vm in agentVms.values) vm.historyCapacity = composerHistorySize
    // CYP-246: re-keyed on activeProjectId. Comm data (channels/timeline/agents) is project-scoped; the VM
    // loads channels+agents ONCE in init and the live `/ws/comm` only pushes ChannelsChanged — the selected
    // timeline + agents map never re-scope on switch. Re-key = a clean, deterministic reload in the new scope.
    val commVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "$COMM_WINDOW_ID-$activeProjectId") {
        // CYP-273 (wiring): the writable-channels port is now LIVE — the composer is disabled for channels the
        // operator can't write to (and fail-closed while unknown/loading/erroring), no longer the interim
        // "everything writable" posture. Re-keyed per project so each project's writable set is resolved fresh.
        CommViewModel(resolvedCommApi, resolvedLiveSource, viewerId = "operator", writableChannels = resolvedWritable)
    }
    // CYP-186 roster repo — OPERATOR-only reads (GET /api/workspace/members). Hoisted so the ACL matrix
    // (CYP-189 human-grant band) and the roster window share ONE instance; never fetched as a non-operator.
    val resolvedWorkspaceRepo = workspaceRepository
        ?: WorkspaceHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    // CYP-246: re-keyed on activeProjectId. ACL channels/entries/agents are project-scoped; the VM loads once
    // and the live stream only upserts single entries (old-project entries never prune) → stale on switch.
    // (The `members` human-roster is workspace-scoped, so the reload re-fetches identical members — harmless.)
    val aclVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "$ACL_WINDOW_ID-$activeProjectId") {
        // CYP-189: the human-grant band consults the roster ONLY when editable (operator) — Invariante E.
        AclViewModel(resolvedAclApi, resolvedAclLiveSource, editable = isOperator, workspaceRepository = resolvedWorkspaceRepo)
    }
    // Project settings (CYP-84/85): hoisted like the others; editable iff an operator token is present.
    // CYP-246: re-keyed on activeProjectId. Project settings (repo url/branch + API-key) are per-project and
    // the VM loads once with no re-scope trigger → stale on switch.
    val settingsVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "$SETTINGS_WINDOW_ID-$activeProjectId") {
        SettingsViewModel(resolvedConfigRepository, editable = isOperator)
    }
    // CYP-326: the compact-orchestration gate is GLOBAL/team-wide (server-owned state), NOT project-scoped —
    // so this VM is deliberately NOT re-keyed on activeProjectId (unlike settings/ACL above): one instance backs
    // the window for the app's lifetime. `editable = isOperator` (control operator-gated; the server also 403s).
    // CYP-326 Milestone C: the live endpoints exist (`GET /api/compact/status` read-tier · `POST /api/compact/config`
    // operator) → default to the live repo. Reads authenticate members via the shared client's session token
    // (CYP-188); the operator Bearer gates the config write. Tests inject a stub.
    val resolvedCompactRepository = remember(compactRepository, httpClient, cfg) {
        compactRepository ?: CompactHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    val compactVm = viewModel(key = "compact-global") {
        CompactViewModel(resolvedCompactRepository, editable = isOperator)
    }
    // CYP-327 Feature B: the per-sequence compact-event list. Operator-only — it reads the operator-gated event
    // feed (like the event-log windows), so a non-operator gets no list (the compact window's gate/status/threshold
    // stay visible to all). Global (not re-keyed), matching the global compact window. The panel scopes these to the
    // current/last run via `status.lastRun.correlationId` (authoritative, server-stamped).
    val compactEventsVm =
        if (isOperator) viewModel(key = "compactEvents-global") { CompactEventsViewModel(resolvedEventsApi, resolvedEventsLiveSource) } else null
    // Product-Lead reports (CYP-90): hoisted; accessible iff operator token (fail-closed — without it the
    // VM never loads a report). Aggregates operator-gated observability, so no token → no report at all.
    // CYP-246: re-keyed on activeProjectId. The Product-Lead report aggregates the active project's
    // observability and the VM loads once (accessible-gated) → stale on switch.
    val productLeadVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "$PRODUCT_LEAD_WINDOW_ID-$activeProjectId") {
        ProductLeadViewModel(resolvedReportRepository, accessible = isOperator)
    }
    // Connector capabilities (CYP-123): read-only per-agent fidelity, hoisted once for all agent windows. Live
    // read against `Agent.capabilities` (GET /api/agents); fail-closed (null caps → "not yet reported", never
    // faked full). Non-gated display (the truth is shown to anyone) — the connector write/opt-in is a separate
    // operator-gated seam (stub until CYP-122).
    val resolvedConnectorCapRepo = remember(connectorCapabilityRepository, httpClient, cfg) {
        connectorCapabilityRepository ?: ConnectorCapabilityHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    // CYP-246: re-keyed on activeProjectId — the caps are read per-agent (project-scoped via the agent set)
    // and the VM loads once, so a switch must re-instance it to reflect the new project's agents.
    val connectorCapVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "connectorCapabilities-$activeProjectId") {
        ConnectorCapabilityViewModel(resolvedConnectorCapRepo)
    }
    val connectorCapState = connectorCapVm.state.collectAsState().value

    // Connector selection write (CYP-123 / CYP-126, spec §3): now the LIVE REST client against the CYP-122
    // connector opt-in endpoint (POST /api/agents/{id}/connector — stub→real swap, no UI change). Operator-gated
    // (editable iff an operator token is present); the server re-checks the operator gate + the B ack + audits.
    // The agentMgmt dialogs that host the picker are already operator-gated, so the picker inherits that gate
    // (no second gate, §3.3). The per-dialog picker VMs are created agent-bound inside the add/edit slots below.
    val resolvedConnectorSelRepo = remember(connectorSelectionRepository, httpClient, cfg) {
        connectorSelectionRepository
            ?: ConnectorSelectionHttpRepository(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: "")
    }
    // Operator-gated VMs exist only with an operator token — the windows themselves are omitted
    // otherwise, so C1 has no source and no badge can appear (fail-closed omission, WINDOW-BADGES §5).
    // CYP-246: re-keyed on activeProjectId. Browse loads its first page once (the panel re-applies the filter
    // only on user action, not on switch); Tail's ring never clears on switch and the client never re-subscribes
    // the socket — both stale on switch → re-instance for a clean, in-scope reload.
    val browseVm: EventBrowseViewModel? =
        if (isOperator) viewModel(viewModelStoreOwner = projectStoreOwner, key = "$EVENTLOG_BROWSE_WINDOW_ID-$activeProjectId") { EventBrowseViewModel(resolvedEventsApi) } else null
    val tailVm: EventTailViewModel? =
        if (isOperator) viewModel(viewModelStoreOwner = projectStoreOwner, key = "$EVENTLOG_TAIL_WINDOW_ID-$activeProjectId") { EventTailViewModel(resolvedEventsLiveSource) } else null

    // CYP-186 roster: OPERATOR-only. Built only when the window is mounted (showRoster) → no MEMBER load, no leak.
    // CYP-246: DELIBERATELY NOT re-keyed on activeProjectId — the workspace member roster is team-wide
    // (GET /api/workspace/members is workspace-scoped, not project-scoped), so it is identical across projects.
    // Re-keying it would force a needless reload flash of the same data on every switch (the guardrail case).
    val rosterVm: WorkspaceRosterViewModel? =
        if (showRoster(tier)) viewModel(key = ROSTER_WINDOW_ID) { WorkspaceRosterViewModel(resolvedWorkspaceRepo) } else null

    // CYP-316: the shell-level per-agent context-token map — ONE `/ws/token-usage` socket for all agents, upserted
    // by agentId (latest-wins). Re-keyed on activeProjectId: the socket binds the ACTIVE project's tracker at
    // connect, so a switch must reopen it (like the lifecycle sockets). Participant feature (NOT operator-gated —
    // the title-bar number is participant-visible); default source is live, injectable for hermetic tests.
    val tokenUsageVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "tokenUsage-$activeProjectId") {
        TokenUsageViewModel(resolvedTokenUsageSource)
    }
    val contextTokens = tokenUsageVm.tokens.collectAsState().value

    // CYP-324: the shell-level per-agent busy map — ONE `/ws/busy-state` socket for all agents, upserted by agentId
    // (latest-wins). Re-keyed on activeProjectId: the socket binds the ACTIVE project's tracker at connect, so a
    // switch must reopen it (like the lifecycle/token sockets). Participant feature (the `*` is participant-visible).
    val busyStateVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "busyState-$activeProjectId") {
        BusyStateViewModel(resolvedBusyStateSource)
    }
    val busy = busyStateVm.busy.collectAsState().value

    // CYP-354: the shell-level per-agent terminal-control map — ONE `/ws/terminal-state` socket for all agents,
    // upserted by agentId (latest-wins). Re-keyed on activeProjectId like the busy/lifecycle sockets (the socket
    // binds the ACTIVE project's tracker at connect). Read-only mirror; feeds the title-bar mode marker only.
    val terminalControlVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "terminalControl-$activeProjectId") {
        TerminalControlStateViewModel(resolvedTerminalControlSource)
    }
    val controlStates = terminalControlVm.states.collectAsState().value

    // Collect the badge-relevant slices of the hoisted VM state (single subscription each).
    // B1: comm-wide unread (others, while unfocused). A1: per-agent ERROR status. C1: the highest
    // severity currently in the operator-gated tail buffer — a content-free enum, never event content.
    val commUnread = commVm.state.collectAsState().value.unreadCount
    val agentStatuses = LinkedHashMap<String, AgentStatus>()
    for ((id, vm) in agentVms) {
        agentStatuses[id] = vm.status.collectAsState().value
    }
    val tailMaxSeverity: Severity? =
        tailVm?.state?.collectAsState()?.value?.events?.maxOfOrNull { it.severity }

      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // Capture the first measured host size for the initial tiling; window positions then persist.
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        // CYP-100: ONE stable state instance (no re-create on set change). The FIRST layout once the host
        // is measured is a full tile (resetTo); every later set change preserves existing windows' positions
        // and only places the new window in a free slot (syncWindows) — an added agent no longer reshuffles
        // the desktop. Keyed by the ordered id list so the effect fires on add/remove.
        val windowKey = windows.joinToString(",") { it.first }
        val state = remember { WindowManagerState(emptyList(), contentWindowIds) }
        var laidOut by remember { mutableStateOf(false) }
        LaunchedEffect(hostWidth, hostHeight, windowKey) {
            // Feed the measured host size in first so resetTo/syncWindows tile against the real size, and
            // the resize re-clamp (CYP-16 F1/F6) fires for existing windows.
            state.updateHostSize(hostWidth, hostHeight)
            if (hostWidth > 0f && hostHeight > 0f) {
                if (!laidOut) {
                    state.resetTo(windows, contentWindowIds, isRtl) // first full layout
                    laidOut = true
                } else {
                    state.syncWindows(windows, contentWindowIds, isRtl) // preserve positions, place new free
                }
            }
        }

        // CYP-55 B1: keep the comm VM's unread counter in step with focus (canvas: top z-order;
        // pager: active page — focusedId tracks both). On focus it resets and suppresses, so leaving
        // the comm window only ever surfaces traffic that arrived while you were elsewhere.
        val focusedId = state.focusedId
        LaunchedEffect(focusedId) { commVm.markCommFocused(focusedId == COMM_WINDOW_ID) }

        // Fail-closed, focus-gated badge map (CYP-55) — pure policy in [deriveWindowBadges]. tailVm is
        // null without an operator token → tailMaxSeverity null → C1 omitted (no leak). No source → no
        // entry → no windowBadge.<id> node downstream.
        val badges = deriveWindowBadges(
            focusedId = focusedId,
            commWindowId = COMM_WINDOW_ID,
            commUnread = commUnread,
            agentStatuses = agentStatuses,
            eventTailWindowId = EVENTLOG_TAIL_WINDOW_ID,
            tailMaxSeverity = tailMaxSeverity,
        )

        // CYP-211: the id→Agent lookup drives per-window titlebar theming; `settingsAgentId` = which agent's
        // settings overlay is open (null = none). The AlertDialog renders above the desktop regardless of position.
        val agentById = remember(managedAgents) { managedAgents.associateBy { it.id } }
        var settingsAgentId by remember { mutableStateOf<String?>(null) }
        // CYP-246: a project switch closes any open per-agent settings overlay — its target belongs to the
        // previous project's agent set (which is being torn down), so leaving it open would show a stale agent.
        LaunchedEffect(activeProjectId) { settingsAgentId = null }
        // CYP-216: provide the authed avatar loader + base URL to every AgentAvatarView beneath (titlebar, comm,
        // event-log, settings panel). Locals default null → those sites render stages 3-4 when not wrapped.
        CompositionLocalProvider(
            LocalAvatarImageLoader provides avatarImageLoader,
            LocalAvatarBaseUrl provides resolvedTransport.httpBaseUrl,
        ) {
        settingsAgentId?.let { sid ->
            val a = agentById[sid]
            // CYP-246: the store key carries activeProjectId (like the agent VMs) — agent ids are reused across
            // projects, so a bare "agentSettings-$sid" key would hand back the PREVIOUS project's retained overlay VM
            // on a same-id reopen (its init{load()} ran in the old scope). That stale VM shows the wrong name AND a
            // save from it lands the old project's values in the new project's override (the repo is project-agnostic;
            // the SERVER resolves scope to the now-active project) — a data-integrity leak, not just cosmetics. The
            // settingsAgentId reset above only closes the overlay across a switch; it does NOT clear the retained VM.
            val agentSettingsVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "agentSettings-$activeProjectId-$sid") {
                AgentSettingsViewModel(
                    sid, resolvedAgentMgmtRepo, editable = isOperator, initialName = a?.name ?: sid, initialColorHex = a?.color,
                    // CYP-310: the LIVE worktree CLAUDE.md port — GET is participant-read, POST operator-gated; the
                    // operator token serves both (operator ⊇ participant), mirroring the comm REST wiring.
                    claudeMdApi = ClaudeMdHttpApi(httpClient, resolvedTransport.httpBaseUrl, cfg.operatorToken ?: ""),
                )
            }
            // CYP-216: the platform image picker (wasmJs/jvm real; android/ios stub) → the VM does the pre-check
            // + multipart upload + server-truth adopt. onRequestUpload launches the picker for THIS agent's VM.
            val requestUpload = rememberImagePicker { picked ->
                agentSettingsVm.uploadAvatar(picked.bytes, picked.filename, picked.mimeType)
            }
            AgentSettingsPanel(
                agentSettingsVm,
                onDismiss = { settingsAgentId = null },
                // CYP-237: a successful save closes the overlay AND refreshes the management list (server truth) so
                // the live titlebar/name/colour reflect the edit without a page reload. Cancel/backdrop → onDismiss only.
                onSaved = { agentMgmtVm.refresh(); settingsAgentId = null },
                onRequestUpload = requestUpload,
            )
        }

        WindowHost(
            state = state,
            onFit = { state.fit(isRtl) },
            badgeFor = { id -> badges[id] },
            // CYP-316: feed each window's live context-token count from the WS map (mirrors badgeFor). Absent
            // key OR a null value → null → the title bar shows no number (unknown ≠ 0).
            contextTokensFor = { id -> contextTokens[id] },
            // CYP-324: feed each window's busy flag from the WS map (mirrors contextTokensFor). Absent key → false →
            // no `*` (unknown ≠ busy); only an explicit busy=true event lights it, an explicit false clears it.
            busyFor = { id -> busy[id] ?: false },
            // CYP-354 §5.1: feed each window's terminal-control event from the WS map (mirrors busyFor). Absent key →
            // null → the marker treats it as MEDIATED (the default) → NO marker (absent == MEDIATED). CYP-381: the
            // WHOLE event (holder-identity + since), not just the enum. Read-only mirror.
            controlEventFor = { id -> controlStates[id] },
            // CYP-250: desktop empty-state for a 0-agent project (the tool windows still coexist, so this keys on
            // the agent list, NOT the window set). The CTA routes into the EXISTING add flow — bring the
            // agent-management window to front + open its add dialog — and is operator-gated (honest gate hint,
            // no dead CTA). Self-clearing: managedAgents is non-empty as soon as the first agent exists.
            // CYP-270: gate on the agent-list load COMPLETING. On a project switch (or initial mount) the re-keyed
            // agentMgmtVm starts `loading=true` with `agents=[]`; a bare isEmpty() would flash the "add first agent"
            // CTA in a NON-empty project during the ~re-fetch window (reads as data loss). Only a genuinely-empty,
            // fully-loaded project shows it. This is the per-switch analogue of CYP-267's initial-mount loading-gate.
            agentsEmpty = !agentMgmtState.loading && managedAgents.isEmpty(),
            canAddAgent = isOperator,
            onAddFirstAgent = {
                state.focus(AGENT_MGMT_WINDOW_ID)
                agentMgmtVm.openAdd()
            },
            // CYP-211: agent windows get their identity-coloured titlebar (system windows → null → default M3) +
            // the ⋮ settings button; system windows (comm/acl/…) are not in [agentById] → no theme, no button.
            titleBarColorsFor = { id ->
                agentById[id]?.let { agent ->
                    val sc = SenderPalette.forAgent(agent.id, agent.role, agent.color)
                    TitleBarColors(background = sc.avatarFill, content = sc.onAvatar, border = sc.borderColor)
                }
            },
            settingsFor = { id -> if (id in agentById) ({ settingsAgentId = id }) else null },
            // CYP-216 §5.1: agent windows get the leading inverted-disc avatar (same TitleBarColors the bar is themed
            // with → the avatar inverts within it). System windows (not in agentById) → null → no leading avatar.
            titleBarLeadingFor = { id ->
                agentById[id]?.let { agent ->
                    val sc = SenderPalette.forAgent(agent.id, agent.role, agent.color)
                    val tb = TitleBarColors(background = sc.avatarFill, content = sc.onAvatar, border = sc.borderColor)
                    val slot: @Composable () -> Unit = { AgentAvatarView(agent, size = 20.dp, tintedBar = tb) }
                    slot
                }
            },
            windowContent = { window ->
                // Reuse the hoisted (always-alive) VMs — never a second viewModel() here, so each
                // window keeps exactly one subscription whether rendered in the canvas or the pager.
                when (window.id) {
                    COMM_WINDOW_ID -> CommPanel(commVm, crossProjectSlot = { cid ->
                        CrossProjectControls(
                            // CYP-258: key on activeProjectId too. Hub-and-spoke seeds same-id `po-<worker>` channels
                            // per project, so a bare `crossproject-$cid` key would hand back the PREVIOUS project's
                            // retained VM after a switch → stale share badge/status (its init{reload()} ran in the old
                            // scope; the repo is project-agnostic, server resolves the active project). Verified
                            // deterministically (CrossProjectSwitchScopeTest): the project-scoped key clears it — and
                            // it DOES suffice through the real CommPanel LazyColumn nesting (naive-suffix concern ruled out).
                            viewModel(viewModelStoreOwner = projectStoreOwner, key = "crossproject-$activeProjectId-$cid") {
                                CrossProjectViewModel(resolvedCrossProjectRepo, cid, editable = isOperator)
                            },
                            // PO flag-2: the target projects = the operator's other projects (the derived sharedWith).
                            targetProjects = projectState.projects.filter { it.id != projectState.activeProjectId },
                        )
                    })
                    ACL_WINDOW_ID -> AclPanel(aclVm)
                    SETTINGS_WINDOW_ID -> SettingsPanel(settingsVm)
                    COMPACT_WINDOW_ID -> CompactPanel(
                        compactVm,
                        compactEvents = compactEventsVm?.events?.collectAsState()?.value,
                    )
                    AGENT_MGMT_WINDOW_ID -> AgentManagementPanel(
                        agentMgmtVm,
                        // CYP-228: name the active project in the add-dialog scope note (server-authoritative pointer).
                        activeProjectName = projectState.projects.find { it.id == projectState.activeProjectId }?.name,
                        // CYP-126 ADD: the picker feeds NewAgentSpec.connectorKind (onKindChosen) — no
                        // connector-endpoint call, no restart hint (fresh spawn). B still goes through the
                        // ack-gated opt-in before the kind is accepted into the spec.
                        addConnectorPickerSlot = {
                            // CYP-258: key on activeProjectId. This VM's factory captures a method-ref to
                            // `agentMgmtVm`, which re-instantiates per project (CYP-246). A constant key would
                            // retain the OLD project's add-picker VM after a switch → its onKindChosen still points
                            // at the previous project's agentMgmtVm → the connector choice lands on the stale add
                            // form and the agent created in the new project gets the default connector. Re-keying
                            // gives a fresh VM per project that captures the current agentMgmtVm.
                            val addVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "connectorSelection-add-$activeProjectId") {
                                ConnectorSelectionViewModel(
                                    resolvedConnectorSelRepo, agentId = null,
                                    editable = isOperator,
                                    initialKind = ConnectorKind.STREAM_JSON,
                                    onKindChosen = agentMgmtVm::setAddConnectorKind,
                                )
                            }
                            ConnectorPicker(addVm)
                        },
                        // CYP-126 EDIT: agent-bound — the picker initialises to the agent's CURRENT connector
                        // (so an existing B agent shows B, never silently reset to A) and a B confirm POSTs the
                        // dedicated connector endpoint for THIS agent. Keyed by id+connectorKind so a re-fetched
                        // truth yields a fresh VM with the right initial kind.
                        editConnectorPickerSlot = { target ->
                            // CYP-258: include activeProjectId for consistency — agent ids repeat across projects, and
                            // two projects' same-id agent with the same connectorKind would otherwise share one keyed
                            // VM. (No method-ref capture here, so lower-risk than the add slot, but same posture.)
                            val editVm = viewModel(viewModelStoreOwner = projectStoreOwner, key = "connectorSelection-edit-$activeProjectId-${target.id}-${target.connectorKind}") {
                                ConnectorSelectionViewModel(
                                    resolvedConnectorSelRepo, agentId = target.id,
                                    editable = isOperator,
                                    initialKind = target.connectorKind,
                                )
                            }
                            ConnectorPicker(editVm)
                        },
                    )
                    PRODUCT_LEAD_WINDOW_ID -> ProductLeadPanel(productLeadVm)
                    EVENTLOG_BROWSE_WINDOW_ID -> browseVm?.let { EventBrowsePanel(it, projects = projectState.projects, activeProjectId = projectState.activeProjectId, agents = agentById) }
                    EVENTLOG_TAIL_WINDOW_ID -> tailVm?.let { EventTailPanel(it, projects = projectState.projects, activeProjectId = projectState.activeProjectId, agents = agentById) }
                    ROSTER_WINDOW_ID -> rosterVm?.let { WorkspaceRosterPanel(it) }
                    else -> agentVms[window.id]?.let {
                        AgentWindow(
                            agentId = window.id,
                            viewModel = it,
                            capabilities = connectorCapState.capabilities[window.id],
                            capabilitiesLoading = connectorCapState.loading,
                            provider = connectorCapState.providers[window.id],
                            onCapabilityBadgeClick = { connectorCapVm.openPanel(window.id) },
                            // CYP-381 §6/§7b: this agent's CYP-354 control-state (same map the titlebar marker uses)
                            // → the window frame renders the hub-blind / context-lost banners. Absent key → null →
                            // no banner (fail-closed; the stub reports none of these so they stay absent — honest).
                            control = controlStates[window.id],
                            // CYP-333/381: the content-view Terminal, LIVE (see [WORKTREE_SHELL_LIVE_ENABLED]).
                            // Bind a fresh WsTerminalSession to the Desktop TerminalView against /ws/terminal (CYP-332
                            // contract). In TERMINAL mode the CYP-355 motor owns an interactive `claude --resume` PTY and
                            // this socket attaches as a viewer; with no live motor session it falls back to the `bash -l`
                            // worktree shell (CYP-348). It is remembered per agent so it stays stable while shown and is
                            // torn down (TerminalView DisposableEffect) on switch-away. The session connects lazily on
                            // first collect, so the flag being on does NOT eagerly spawn anything — only opening the
                            // Terminal view does.
                            terminalContent = if (WORKTREE_SHELL_LIVE_ENABLED) {
                                { id, m ->
                                    val session = remember(id) {
                                        WsTerminalSession(httpClient, resolvedTransport.wsBaseUrl, id, cfg.operatorToken ?: "")
                                    }
                                    TerminalView(session, m)
                                }
                            } else null,
                            terminalGatedNote = !WORKTREE_SHELL_LIVE_ENABLED,
                        )
                    }
                }
            },
        )
        } // CYP-216: end LocalAvatar* provider
        // CYP-123: the capability detail panel opens from the header fidelity badge — one Dialog at shell level,
        // keyed by the opened agent. Read-only; fail-closed content (null caps → "not yet reported"). Tap-out closes.
        connectorCapState.openPanelAgentId?.let { openId ->
            Dialog(onDismissRequest = { connectorCapVm.closePanel() }) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                    CapabilityPanel(
                        caps = connectorCapState.capabilities[openId],
                        agentId = openId,
                        provider = connectorCapState.providers[openId],
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
      }
      } // CYP-249 loading-gate: end the !loading desktop branch
    }
}

/** CYP-249 loading-gate: testTag for the desktop-area loading placeholder shown on the initial (pre-`!loading`) mount. */
internal const val SHELL_LOADING_TAG = "shell.loading"

/**
 * CYP-249 loading-gate (Option A): the desktop-area placeholder shown while the initial project view loads
 * (`ProjectUiState.loading`). A brief, one-time state on first mount — `loading` flips true→false exactly once,
 * switches never re-set it — so it is not a per-switch spinner. Keeps the project-scoped VMs from composing
 * against the unconfirmed seed project (no phantom per-project store). The switcher bar stays visible above it.
 */
@Composable
private fun ProjectLoadingPlaceholder(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.project_loading)
    Box(
        modifier = modifier.testTag(SHELL_LOADING_TAG).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
