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
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/** Dev server for the live agent stream (CYP-13 `/ws/agent`). Config-driven boot replaces this later. */
private const val AGENT_WS_BASE_URL = "ws://localhost:8080"

/**
 * The app shell (CYP-15): a "desktop" of floating agent windows. Marries the window manager
 * (CYP-10) with the agent renderer (CYP-6) — [WindowHost] hosts one [AgentWindow] per agent.
 *
 * CYP-6 swap: each window's session is now the **live** [MappingAgentSession] over [AgentWsClient]
 * (`/ws/agent`, CYP-13) — the first real event stream. The session is injectable via [sessionFactory]
 * so tests stay hermetic (a stub) without touching the network.
 *
 * Agents are hardcoded for the MVP (`po`/`frontend`/`backend`, matching the platform config and the
 * CYP-14 `knownSlots`); they come from the Agent registry once config-driven boot lands.
 */
@Composable
fun AgentShell(
    modifier: Modifier = Modifier,
    /** Override the per-agent session (tests inject a stub); `null` → the live `/ws/agent` session. */
    sessionFactory: ((String) -> AgentSession)? = null,
) {
    val agents = remember {
        listOf(
            "po" to "Product Owner",
            "frontend" to "Frontend",
            "backend" to "Backend",
        )
    }

    // One shared WS-capable client for all agent windows; closed when the shell leaves composition.
    // The JVM/desktop engine (CIO) is wired; web/ios/android engines are a flagged follow-up.
    val httpClient = remember { HttpClient { install(WebSockets) } }
    DisposableEffect(Unit) { onDispose { httpClient.close() } }

    val resolveSession: (String) -> AgentSession = sessionFactory ?: { agentId ->
        val ws = AgentWsClient(httpClient, AGENT_WS_BASE_URL, agentId, "dev-token-$agentId")
        MappingAgentSession(source = ws.events, sink = ws::send)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the first measured host size for the initial tiling; window positions then persist
        // across recomposition (drag/resize state lives in WindowManagerState).
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        val state = remember {
            WindowManagerState(WindowReducer.tile(agents, hostWidth = hostWidth, hostHeight = hostHeight))
        }
        // Feed the measured host size into the manager so its resize re-clamp (CYP-16 F1/F6) fires.
        LaunchedEffect(hostWidth, hostHeight) {
            state.updateHostSize(hostWidth, hostHeight)
        }
        WindowHost(
            state = state,
            windowContent = { window ->
                // viewModel keyed by agent id → one AgentViewModel per agent, proper VM lifecycle.
                val agentViewModel = viewModel(key = window.id) { AgentViewModel(resolveSession(window.id)) }
                AgentWindow(agentId = window.id, viewModel = agentViewModel)
            },
        )
    }
}
