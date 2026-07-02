package com.tneff.cyppieagents

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import com.tneff.cyppieagents.auth.UserTier
import com.tneff.cyppieagents.workspace.WorkspaceHttpRepository
import com.tneff.cyppieagents.workspace.WorkspaceRepository
import com.tneff.cyppieagents.workspace.WorkspaceRosterPanel
import com.tneff.cyppieagents.workspace.WorkspaceRosterViewModel
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
import com.tneff.cyppieagents.net.sharedWsHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_mgmt_title
import kmpcyppieagents.app.shared.generated.resources.report_title
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
    /** Override the project-settings data port (CYP-84/85); `null` → the in-memory stub until CYP-96 lands. */
    configRepository: ConfigRepository? = null,
    /** Override the agent-management data port (CYP-86/87/88); `null` → the in-memory stub until CYP-97 lands. */
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
    /** CYP-188 — the signed-in user's native session credential (`X-Session-Token`) for the shared HTTP/WS client,
     *  so a session-only user (no operator token) authenticates its reads/sockets. `null`/absent → none (a browser
     *  session rides its same-origin `ory_kratos_session` cookie; the operator token stays break-glass). */
    sessionToken: () -> String? = { null },
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

    // One shared WS+HTTP client (created here — the agent-management REST client below needs it). Closed
    // when the shell leaves composition. The JVM/desktop engine (CIO) is wired; other engines = CYP-27.
    // CYP-115: keep-alive pinging (sharedWsHttpClient) so idle comm/lifecycle sockets aren't Darwin-idle-closed.
    val httpClient = remember { sharedWsHttpClient(sessionToken) }
    DisposableEffect(Unit) { onDispose { httpClient.close() } }

    // Agent-management VM (CYP-86/87/88): now the LIVE REST client against the CYP-97 endpoints (stub→real
    // swap, no UI/VM change). Hoisted FIRST because its agent list (GET /api/agents) drives the **dynamic**
    // window set — an added agent gets a window, a removed one loses it. Editable iff an operator token is
    // present (server also enforces the gate; fail-closed UI).
    val resolvedAgentMgmtRepo = remember(agentManagementRepository, httpClient, cfg) {
        agentManagementRepository
            ?: AgentManagementHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val agentMgmtVm = viewModel(key = AGENT_MGMT_WINDOW_ID) {
        AgentManagementViewModel(resolvedAgentMgmtRepo, editable = isOperator)
    }
    val managedAgents = agentMgmtVm.state.collectAsState().value.agents

    // Project switcher + management (CYP-91/92): ONE VM backs the top-level bar and the management overlay.
    // Now the LIVE REST client against the /api/projects registry endpoints (stub→real swap, no UI/VM change).
    // Switching + mutations are operator-gated (server also enforces; fail-closed). The bar makes the active
    // project unambiguous and re-fetches the project view on switch; the shell-wide per-project window
    // re-scope is the backend-gated piece deferred in ProjectModel.kt (lights up with live re-instancing).
    val resolvedProjectRepo = remember(projectRepository, httpClient, cfg) {
        projectRepository ?: HttpProjectRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val projectVm = viewModel(key = "projectSwitcher") {
        ProjectViewModel(resolvedProjectRepo, editable = isOperator)
    }
    // CYP-94: the project registry feeds the event-log cross-project filter (operator-only surfaces).
    val projectState = projectVm.state.collectAsState().value

    // CYP-93: cross-project authorization port — now the LIVE client against /api/channels/{id}/share
    // (stub→real swap, no UI/VM change). `sharedWith` derives from the operator's OTHER projects (reach stays
    // per-agent ACL-gated server-side → no over-widen); read live from the project VM so it isn't stale.
    val resolvedCrossProjectRepo = remember(crossProjectRepository, httpClient, cfg, projectVm) {
        crossProjectRepository ?: HttpCrossProjectRepository(
            httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "",
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

    val resolveSession: (String) -> AgentSession = sessionFactory ?: { agentId ->
        val ws = AgentWsClient(httpClient, cfg.hubWsBaseUrl, agentId, cfg.agentToken(agentId))
        MappingAgentSession(source = ws.events, sink = ws::send)
    }

    // Operator viewer (CYP-17). The operator token comes from config (runtime), not baked; without it
    // the comm REST / `/ws/comm` calls return 401 (→ empty / Disconnected banner) until one is set.
    val defaultCommApi = remember(httpClient, cfg) {
        CommRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedCommApi = commApi ?: defaultCommApi

    val defaultLiveSource = remember(httpClient, cfg) {
        CommWsClient(httpClient, cfg.hubWsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLiveSource = commLiveSource ?: defaultLiveSource

    // Event-Log read sources, now LIVE (CYP-39 `/api/events` REST + CYP-40 `/ws/events` WS) — Browse and
    // Live-Tail go live together, operator-only/fail-closed like comm. Injectable so tests stay hermetic;
    // `:app:webAppDemo` injects stubs for the Maestro flows. Windows exist only when operatorToken != null.
    val defaultEventsApi = remember(httpClient, cfg) {
        EventsApiClient(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedEventsApi = eventsApi ?: defaultEventsApi
    val defaultEventsLiveSource = remember(httpClient, cfg) {
        EventsWsClient(httpClient, cfg.hubWsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedEventsLiveSource = eventsLiveSource ?: defaultEventsLiveSource

    // ACL matrix sources (CYP-48). Operator-token-bound: PUT is operator-only (server 403s otherwise);
    // GET returns the operator's full view or the agent's partial view. Editable iff an operator token
    // is present (the agent-token read-only repo is a tracked follow-up — see AclViewModel KDoc).
    val defaultAclApi = remember(httpClient, cfg) {
        AclRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedAclApi = aclApi ?: defaultAclApi
    val defaultAclLiveSource = remember(httpClient, cfg) {
        AclWsClient(httpClient, cfg.hubWsBaseUrl, cfg.operatorToken ?: "")
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
                    it == ROSTER_WINDOW_ID
            }
            .toSet()
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Project-settings data port (CYP-84/85): now the LIVE REST client against the CYP-96 endpoints
    // (CYP-85 stub→real swap — no UI/VM change). Injectable so tests stay hermetic. Operator token drives
    // editability + the operator-only PUTs (server enforces 403 too; fail-closed UI; key write-only).
    val defaultConfigRepository = remember(httpClient, cfg) {
        ConfigHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedConfigRepository = configRepository ?: defaultConfigRepository
    // Product-Lead report port (CYP-90): now the LIVE REST client against the CYP-89 endpoints (stub→real
    // swap, no UI/VM change). Injectable so tests stay hermetic. Accessible iff an operator token is present
    // (server enforces 401/403 too; fail-closed — no token → no list, no report, never a partial).
    val defaultReportRepository = remember(httpClient, cfg) {
        ReportHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedReportRepository = reportRepository ?: defaultReportRepository

    // Agent lifecycle (CYP-73). Controls = operator-gated REST (POST /api/agents/{id}/{stop|start|
    // restart}); status display = non-gated (GET /api/agents snapshot + /ws/lifecycle deltas). Operator
    // token drives both the action auth and the participant-gated lifecycle socket. Injectable so tests
    // stay hermetic. Controls are enabled only with an operator token (fail-closed) in the VM/header.
    val defaultLifecycleApi = remember(httpClient, cfg) {
        AgentLifecycleRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLifecycleApi = lifecycleApi ?: defaultLifecycleApi
    val defaultLifecycleSource = remember(httpClient, cfg) {
        AgentLifecycleLiveSource(httpClient, cfg.hubHttpBaseUrl, cfg.hubWsBaseUrl, cfg.operatorToken ?: "")
    }
    val resolvedLifecycleSource = lifecycleSource ?: defaultLifecycleSource

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
        agentVms[id] = viewModel(key = id) {
            AgentViewModel(
                session = resolveSession(id),
                agentId = id,
                lifecycle = resolvedLifecycleApi,
                lifecycleSource = resolvedLifecycleSource,
                canControl = isOperator,
            )
        }
    }
    val commVm = viewModel(key = COMM_WINDOW_ID) {
        CommViewModel(resolvedCommApi, resolvedLiveSource, viewerId = "operator")
    }
    // CYP-186 roster repo — OPERATOR-only reads (GET /api/workspace/members). Hoisted so the ACL matrix
    // (CYP-189 human-grant band) and the roster window share ONE instance; never fetched as a non-operator.
    val resolvedWorkspaceRepo = workspaceRepository
        ?: WorkspaceHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    val aclVm = viewModel(key = ACL_WINDOW_ID) {
        // CYP-189: the human-grant band consults the roster ONLY when editable (operator) — Invariante E.
        AclViewModel(resolvedAclApi, resolvedAclLiveSource, editable = isOperator, workspaceRepository = resolvedWorkspaceRepo)
    }
    // Project settings (CYP-84/85): hoisted like the others; editable iff an operator token is present.
    val settingsVm = viewModel(key = SETTINGS_WINDOW_ID) {
        SettingsViewModel(resolvedConfigRepository, editable = isOperator)
    }
    // Product-Lead reports (CYP-90): hoisted; accessible iff operator token (fail-closed — without it the
    // VM never loads a report). Aggregates operator-gated observability, so no token → no report at all.
    val productLeadVm = viewModel(key = PRODUCT_LEAD_WINDOW_ID) {
        ProductLeadViewModel(resolvedReportRepository, accessible = isOperator)
    }
    // Connector capabilities (CYP-123): read-only per-agent fidelity, hoisted once for all agent windows. Live
    // read against `Agent.capabilities` (GET /api/agents); fail-closed (null caps → "not yet reported", never
    // faked full). Non-gated display (the truth is shown to anyone) — the connector write/opt-in is a separate
    // operator-gated seam (stub until CYP-122).
    val resolvedConnectorCapRepo = remember(connectorCapabilityRepository, httpClient, cfg) {
        connectorCapabilityRepository ?: ConnectorCapabilityHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    val connectorCapVm = viewModel(key = "connectorCapabilities") {
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
            ?: ConnectorSelectionHttpRepository(httpClient, cfg.hubHttpBaseUrl, cfg.operatorToken ?: "")
    }
    // Operator-gated VMs exist only with an operator token — the windows themselves are omitted
    // otherwise, so C1 has no source and no badge can appear (fail-closed omission, WINDOW-BADGES §5).
    val browseVm: EventBrowseViewModel? =
        if (isOperator) viewModel(key = EVENTLOG_BROWSE_WINDOW_ID) { EventBrowseViewModel(resolvedEventsApi) } else null
    val tailVm: EventTailViewModel? =
        if (isOperator) viewModel(key = EVENTLOG_TAIL_WINDOW_ID) { EventTailViewModel(resolvedEventsLiveSource) } else null

    // CYP-186 roster: OPERATOR-only. Built only when the window is mounted (showRoster) → no MEMBER load, no leak.
    val rosterVm: WorkspaceRosterViewModel? =
        if (showRoster(tier)) viewModel(key = ROSTER_WINDOW_ID) { WorkspaceRosterViewModel(resolvedWorkspaceRepo) } else null

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

    Column(modifier = modifier.fillMaxSize()) {
      // CYP-92: the project switcher is a top-level bar ABOVE the window host (not a canvas window) — always
      // visible, context-independent, framing the whole scoped shell below.
      // CYP-186: the persistent role indicator rides in the top bar. operatorName is BE1-pending (the
      // workspace member/operator identity isn't on the /api/auth/me seam yet) → null omits the "Operator: …" line.
      ProjectSwitcherBar(projectVm, tier = tier, operatorName = null)
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

        WindowHost(
            state = state,
            onFit = { state.fit(isRtl) },
            badgeFor = { id -> badges[id] },
            windowContent = { window ->
                // Reuse the hoisted (always-alive) VMs — never a second viewModel() here, so each
                // window keeps exactly one subscription whether rendered in the canvas or the pager.
                when (window.id) {
                    COMM_WINDOW_ID -> CommPanel(commVm, crossProjectSlot = { cid ->
                        CrossProjectControls(
                            viewModel(key = "crossproject-$cid") {
                                CrossProjectViewModel(resolvedCrossProjectRepo, cid, editable = isOperator)
                            },
                            // PO flag-2: the target projects = the operator's other projects (the derived sharedWith).
                            targetProjects = projectState.projects.filter { it.id != projectState.activeProjectId },
                        )
                    })
                    ACL_WINDOW_ID -> AclPanel(aclVm)
                    SETTINGS_WINDOW_ID -> SettingsPanel(settingsVm)
                    AGENT_MGMT_WINDOW_ID -> AgentManagementPanel(
                        agentMgmtVm,
                        // CYP-126 ADD: the picker feeds NewAgentSpec.connectorKind (onKindChosen) — no
                        // connector-endpoint call, no restart hint (fresh spawn). B still goes through the
                        // ack-gated opt-in before the kind is accepted into the spec.
                        addConnectorPickerSlot = {
                            val addVm = viewModel(key = "connectorSelection-add") {
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
                            val editVm = viewModel(key = "connectorSelection-edit-${target.id}-${target.connectorKind}") {
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
                    EVENTLOG_BROWSE_WINDOW_ID -> browseVm?.let { EventBrowsePanel(it, projects = projectState.projects, activeProjectId = projectState.activeProjectId) }
                    EVENTLOG_TAIL_WINDOW_ID -> tailVm?.let { EventTailPanel(it, projects = projectState.projects, activeProjectId = projectState.activeProjectId) }
                    ROSTER_WINDOW_ID -> rosterVm?.let { WorkspaceRosterPanel(it) }
                    else -> agentVms[window.id]?.let {
                        AgentWindow(
                            agentId = window.id,
                            viewModel = it,
                            capabilities = connectorCapState.capabilities[window.id],
                            provider = connectorCapState.providers[window.id],
                            onCapabilityBadgeClick = { connectorCapVm.openPanel(window.id) },
                        )
                    }
                }
            },
        )
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
    }
}
