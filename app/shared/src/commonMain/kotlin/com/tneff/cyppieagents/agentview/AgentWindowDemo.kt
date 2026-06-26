package com.tneff.cyppieagents.agentview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Self-contained demo of the agent renderer wired to [StubAgentSession] — lets CYP-6 be run and
 * shown (Desktop/Web) without any backend. Replaced/removed once the real Hub-WS session and the
 * window manager (CYP-3) host the renderer.
 */
@Composable
fun AgentWindowDemo(modifier: Modifier = Modifier) {
    val agentViewModel = viewModel { AgentViewModel(StubAgentSession()) }
    AgentWindow(agentId = "backend", viewModel = agentViewModel, modifier = modifier)
}
