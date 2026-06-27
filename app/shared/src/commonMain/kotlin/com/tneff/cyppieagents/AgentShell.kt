package com.tneff.cyppieagents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/** Dev server (CYP-13 `/ws/agent` + comm REST). Config-driven boot replaces these later (CYP-24). */
private const val AGENT_WS_BASE_URL = "ws://localhost:8080"
private const val COMM_REST_BASE_URL = "http://localhost:8080"
private const val OPERATOR_TOKEN = "dev-operator-token"
private const val COMM_WINDOW_ID = "comm"

/**
 * The app shell (CYP-15): a "desktop" of floating windows. Marries the window manager (CYP-10) with
 * the agent renderer (CYP-6, one [AgentWindow] per agent over the live `/ws/agent` stream) and the
 * comm panel (CYP-21, one [CommPanel] window over comm REST + a stub live source).
 *
 * The single shared [HttpClient] backs both the agent WebSockets and the comm REST calls. Both the
 * agent session and the comm data are injectable so tests stay hermetic without touching the network.
 *
 * Agents are hardcoded for the MVP (`po`/`frontend`/`backend`, CYP-14 `knownSlots`); they come from
 * the Agent registry once config-driven boot lands (CYP-24).
 */
@Composable
fun AgentShell(
    modifier: Modifier = Modifier,
    /** Override the per-agent session (tests inject a stub); `null` → the live `/ws/agent` session. */
    sessionFactory: ((String) -> AgentSession)? = null,
    /** Override the comm data port (tests inject a fake); `null` → the comm REST repository. */
    commApi: CommApi? = null,
    /** Override the comm live source; defaults to the stub until `/ws/comm` lands (CYP-18). */
    commLiveSource: CommLiveSource = StubCommLiveSource(),
) {
    val windows = remember {
        listOf(
            "po" to "Product Owner",
            "frontend" to "Frontend",
            "backend" to "Backend",
            COMM_WINDOW_ID to "Kommunikation",
        )
    }

    // One shared WS+HTTP client for agent sockets and comm REST; closed when the shell leaves
    // composition. The JVM/desktop engine (CIO) is wired; web/ios/android engines = CYP-27.
    val httpClient = remember { HttpClient { install(WebSockets) } }
    DisposableEffect(Unit) { onDispose { httpClient.close() } }

    val resolveSession: (String) -> AgentSession = sessionFactory ?: { agentId ->
        val ws = AgentWsClient(httpClient, AGENT_WS_BASE_URL, agentId, "dev-token-$agentId")
        MappingAgentSession(source = ws.events, sink = ws::send)
    }

    // Operator viewer (CYP-17). Live comm data needs operator-token acceptance on the read endpoints
    // (CYP-18); until then the REST calls return empty and the panel runs on the stub live source.
    val defaultCommApi = remember(httpClient) { CommRepository(httpClient, COMM_REST_BASE_URL, OPERATOR_TOKEN) }
    val resolvedCommApi = commApi ?: defaultCommApi

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
                if (window.id == COMM_WINDOW_ID) {
                    val commViewModel = viewModel(key = COMM_WINDOW_ID) {
                        CommViewModel(resolvedCommApi, commLiveSource, viewerId = "operator")
                    }
                    CommPanel(commViewModel)
                } else {
                    // viewModel keyed by agent id → one AgentViewModel per agent, proper VM lifecycle.
                    val agentViewModel = viewModel(key = window.id) { AgentViewModel(resolveSession(window.id)) }
                    AgentWindow(agentId = window.id, viewModel = agentViewModel)
                }
            },
        )
    }
}
