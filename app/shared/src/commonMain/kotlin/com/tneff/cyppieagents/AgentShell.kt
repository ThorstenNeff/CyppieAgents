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
import com.tneff.cyppieagents.comm.CommWsClient
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

private const val COMM_WINDOW_ID = "comm"

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
) {
    val cfg = remember { config ?: defaultShellConfig() }

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
                        CommViewModel(resolvedCommApi, resolvedLiveSource, viewerId = "operator")
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
