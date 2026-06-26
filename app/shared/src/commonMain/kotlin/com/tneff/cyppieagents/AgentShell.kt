package com.tneff.cyppieagents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowReducer

/**
 * The app shell (CYP-15): a "desktop" of floating agent windows. Marries the window manager
 * (CYP-10) with the agent renderer (CYP-6) — [WindowHost] hosts one [AgentWindow] per agent as its
 * `windowContent`.
 *
 * Agents are hardcoded for the MVP (`po`/`frontend`/`backend`, matching the platform config and the
 * CYP-14 `knownSlots`); they will come from the Agent registry once config-driven boot lands. Each
 * window's session is a [StubAgentSession] until the live `/ws/agent` is on develop (CYP-13) — then
 * the per-agent swap to `MappingAgentSession(ws.events, ws::send)`.
 */
@Composable
fun AgentShell(modifier: Modifier = Modifier) {
    val agents = remember {
        listOf(
            "po" to "Product Owner",
            "frontend" to "Frontend",
            "backend" to "Backend",
        )
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the first measured host size for the initial tiling; window positions then persist
        // across recomposition (drag/resize state lives in WindowManagerState).
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        val state = remember {
            WindowManagerState(WindowReducer.tile(agents, hostWidth = hostWidth, hostHeight = hostHeight))
        }
        // Feed the measured host size into the manager so its resize re-clamp (CYP-16 F1/F6) fires
        // when the window/viewport changes — otherwise windows can be stranded off-screen.
        LaunchedEffect(hostWidth, hostHeight) {
            state.updateHostSize(hostWidth, hostHeight)
        }
        WindowHost(
            state = state,
            windowContent = { window ->
                // viewModel keyed by agent id → one AgentViewModel per agent, proper VM lifecycle.
                val agentViewModel = viewModel(key = window.id) { AgentViewModel(StubAgentSession()) }
                AgentWindow(agentId = window.id, viewModel = agentViewModel)
            },
        )
    }
}
