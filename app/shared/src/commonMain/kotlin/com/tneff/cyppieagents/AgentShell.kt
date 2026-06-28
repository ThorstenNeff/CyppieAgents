package com.tneff.cyppieagents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tneff.cyppieagents.acl.AclApi
import com.tneff.cyppieagents.acl.AclLiveSource
import com.tneff.cyppieagents.acl.AclPanel
import com.tneff.cyppieagents.acl.AclRepository
import com.tneff.cyppieagents.acl.AclViewModel
import com.tneff.cyppieagents.acl.AclWsClient
import com.tneff.cyppieagents.agentview.AgentSession
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
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

private const val COMM_WINDOW_ID = "comm"
private const val ACL_WINDOW_ID = "acl"
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
) {
    val cfg = remember { config ?: defaultShellConfig() }

    val windows = remember {
        buildList {
            add("po" to "Product Owner")
            add("frontend" to "Frontend")
            add("backend" to "Backend")
            add(COMM_WINDOW_ID to "Kommunikation")
            // ACL matrix (CYP-48): always present — editable for an operator, read-only partial view for an
            // agent/no-token (ACL-MATRIX.md §4, deliberately NOT an omission like the event-log windows).
            add(ACL_WINDOW_ID to "Zugriffsrechte")
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the first measured host size for the initial tiling; window positions then persist.
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        val state = remember {
            WindowManagerState(WindowReducer.tile(windows, hostWidth = hostWidth, hostHeight = hostHeight))
        }
        // Feed the measured host size into the manager so its resize re-clamp (CYP-16 F1/F6) fires.
        LaunchedEffect(hostWidth, hostHeight) {
            state.updateHostSize(hostWidth, hostHeight)
        }
        WindowHost(
            state = state,
            windowContent = { window ->
                when (window.id) {
                    COMM_WINDOW_ID -> {
                        val commViewModel = viewModel(key = COMM_WINDOW_ID) {
                            CommViewModel(resolvedCommApi, resolvedLiveSource, viewerId = "operator")
                        }
                        CommPanel(commViewModel)
                    }
                    ACL_WINDOW_ID -> {
                        val vm = viewModel(key = ACL_WINDOW_ID) {
                            AclViewModel(resolvedAclApi, resolvedAclLiveSource, editable = cfg.operatorToken != null)
                        }
                        AclPanel(vm)
                    }
                    EVENTLOG_BROWSE_WINDOW_ID -> {
                        val vm = viewModel(key = EVENTLOG_BROWSE_WINDOW_ID) { EventBrowseViewModel(resolvedEventsApi) }
                        EventBrowsePanel(vm)
                    }
                    EVENTLOG_TAIL_WINDOW_ID -> {
                        val vm = viewModel(key = EVENTLOG_TAIL_WINDOW_ID) { EventTailViewModel(resolvedEventsLiveSource) }
                        EventTailPanel(vm)
                    }
                    else -> {
                        // viewModel keyed by agent id → one AgentViewModel per agent, proper VM lifecycle.
                        val agentViewModel = viewModel(key = window.id) { AgentViewModel(resolveSession(window.id)) }
                        AgentWindow(agentId = window.id, viewModel = agentViewModel)
                    }
                }
            },
        )
    }
}
