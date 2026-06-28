package com.tneff.cyppieagents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.SettingsPanel
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.settings.StubConfigRepository
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.settings_title
import org.jetbrains.compose.resources.stringResource

private const val COMM_WINDOW_ID = "comm"
private const val ACL_WINDOW_ID = "acl"
private const val SETTINGS_WINDOW_ID = "settings"
private const val EVENTLOG_BROWSE_WINDOW_ID = "eventlog"
private const val EVENTLOG_TAIL_WINDOW_ID = "eventtail"

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
) {
    val cfg = remember { config ?: defaultShellConfig() }

    // i18n window title for the settings window (the other titles are hardcoded today; CYP-84 wires the
    // one new key the design defines). Resolved here so the title bar reads it like any string resource.
    val settingsTitle = stringResource(Res.string.settings_title)
    val windows = remember(settingsTitle) {
        buildList {
            add("po" to "Product Owner")
            add("frontend" to "Frontend")
            add("backend" to "Backend")
            add(COMM_WINDOW_ID to "Kommunikation")
            // ACL matrix (CYP-48): always present — editable for an operator, read-only partial view for an
            // agent/no-token (ACL-MATRIX.md §4, deliberately NOT an omission like the event-log windows).
            add(ACL_WINDOW_ID to "Zugriffsrechte")
            // Project settings (CYP-84/85): always present — editable for an operator, read-only + gate
            // hint otherwise (PROJECT-SETTINGS §1.3; same "gate visible" stance as ACL, not an omission).
            add(SETTINGS_WINDOW_ID to settingsTitle)
            // Operator-only observability windows: offered ONLY with an operator token (omission, not a
            // dead "no access" window — EVENT-LOG-UI §5.6). Live sources swap in at CYP-39/40.
            if (cfg.operatorToken != null) {
                add(EVENTLOG_BROWSE_WINDOW_ID to "Event-Log")
                add(EVENTLOG_TAIL_WINDOW_ID to "Live-Tail")
            }
        }
    }

    // One shared WS+HTTP client for agent sockets and comm REST; closed when the shell leaves
    // composition. The JVM/desktop engine (CIO) is wired; web/ios/android engines = CYP-27.
    val httpClient = remember { HttpClient { install(WebSockets) } }
    DisposableEffect(Unit) { onDispose { httpClient.close() } }

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
                    it == EVENTLOG_TAIL_WINDOW_ID || it == SETTINGS_WINDOW_ID
            }
            .toSet()
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Project-settings data port (CYP-84/85): stub until the backend seam (CYP-96); injectable so tests
    // stay hermetic. Operator token drives editability (server also enforces the gate; fail-closed UI).
    val resolvedConfigRepository = remember(configRepository) { configRepository ?: StubConfigRepository() }

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
    val agentVms = LinkedHashMap<String, AgentViewModel>()
    for ((id, _) in windows) {
        if (id == COMM_WINDOW_ID || id == ACL_WINDOW_ID || id == SETTINGS_WINDOW_ID ||
            id == EVENTLOG_BROWSE_WINDOW_ID || id == EVENTLOG_TAIL_WINDOW_ID
        ) continue
        agentVms[id] = viewModel(key = id) {
            AgentViewModel(
                session = resolveSession(id),
                agentId = id,
                lifecycle = resolvedLifecycleApi,
                lifecycleSource = resolvedLifecycleSource,
                canControl = cfg.operatorToken != null,
            )
        }
    }
    val commVm = viewModel(key = COMM_WINDOW_ID) {
        CommViewModel(resolvedCommApi, resolvedLiveSource, viewerId = "operator")
    }
    val aclVm = viewModel(key = ACL_WINDOW_ID) {
        AclViewModel(resolvedAclApi, resolvedAclLiveSource, editable = cfg.operatorToken != null)
    }
    // Project settings (CYP-84/85): hoisted like the others; editable iff an operator token is present.
    val settingsVm = viewModel(key = SETTINGS_WINDOW_ID) {
        SettingsViewModel(resolvedConfigRepository, editable = cfg.operatorToken != null)
    }
    // Operator-gated VMs exist only with an operator token — the windows themselves are omitted
    // otherwise, so C1 has no source and no badge can appear (fail-closed omission, WINDOW-BADGES §5).
    val browseVm: EventBrowseViewModel? =
        if (cfg.operatorToken != null) viewModel(key = EVENTLOG_BROWSE_WINDOW_ID) { EventBrowseViewModel(resolvedEventsApi) } else null
    val tailVm: EventTailViewModel? =
        if (cfg.operatorToken != null) viewModel(key = EVENTLOG_TAIL_WINDOW_ID) { EventTailViewModel(resolvedEventsLiveSource) } else null

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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the first measured host size for the initial tiling; window positions then persist.
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        val state = remember {
            WindowManagerState(
                WindowReducer.tile(windows, hostWidth, hostHeight, isRtl = isRtl, contentWindowIds = contentWindowIds),
                contentWindowIds = contentWindowIds,
            )
        }
        // CYP-26 §2.1: the initial tile may have run before the host was measured (size 0). Re-tile ONCE
        // on the first real measurement so the default layout is fully visible — never auto-re-tile after.
        var tiledToHost by remember { mutableStateOf(false) }
        LaunchedEffect(hostWidth, hostHeight) {
            if (hostWidth > 0f && hostHeight > 0f && !tiledToHost) {
                state.fit(isRtl)
                tiledToHost = true
            }
            // Feed the measured host size into the manager so its resize re-clamp (CYP-16 F1/F6) fires.
            state.updateHostSize(hostWidth, hostHeight)
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
                    COMM_WINDOW_ID -> CommPanel(commVm)
                    ACL_WINDOW_ID -> AclPanel(aclVm)
                    SETTINGS_WINDOW_ID -> SettingsPanel(settingsVm)
                    EVENTLOG_BROWSE_WINDOW_ID -> browseVm?.let { EventBrowsePanel(it) }
                    EVENTLOG_TAIL_WINDOW_ID -> tailVm?.let { EventTailPanel(it) }
                    else -> agentVms[window.id]?.let { AgentWindow(agentId = window.id, viewModel = it) }
                }
            },
        )
    }
}
