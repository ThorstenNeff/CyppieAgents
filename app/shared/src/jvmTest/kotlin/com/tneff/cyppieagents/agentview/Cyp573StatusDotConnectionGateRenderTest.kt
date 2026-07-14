package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.comm.ConnectionStatus
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_unknown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-573 (the WIRING tooth) — the header status DOT is fed the ViewModel's connection-gated
 * [AgentViewModel.displayLifecycleState], NOT the raw [AgentViewModel.lifecycleState]. This proves the render
 * binding (that the pure gate reaches the pixel); the flicker-guard TIMING is proven separately on virtual time
 * (Cyp573ConnectionGatedDotTest). The gate window is injected as 0 so the transition is deterministic here — no
 * long waits, no timing flake.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp573StatusDotConnectionGateRenderTest {

    private class ConnSession(private val conn: StateFlow<ConnectionStatus>) : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
        override val connection: StateFlow<ConnectionStatus> = conn
    }

    /**
     * A RUNNING agent whose session goes (sustained) DISCONNECTED shows the honest UNKNOWN label on the dot, then
     * RUNNING again on reconnect. Reddening mutation: point the dot back at `state` (raw lifecycleState) instead of
     * `dotState` at the [StatusIndicator] call ⇒ the label stays RUNNING across the disconnect ⇒ red.
     */
    @Test
    fun dot_degradesToUnknown_onDisconnect_restoresOnReconnect() = runComposeUiTest {
        val conn = MutableStateFlow(ConnectionStatus.LIVE)
        lateinit var runningLabel: String
        lateinit var unknownLabel: String
        setContent {
            MaterialTheme {
                runningLabel = stringResource(Res.string.agent_status_running)
                unknownLabel = stringResource(Res.string.agent_status_unknown)
                val src = remember { StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.RUNNING)) }
                val vm = remember {
                    AgentViewModel(
                        ConnSession(conn),
                        agentId = "backend",
                        lifecycle = src,
                        lifecycleSource = src,
                        sustainedDisconnectMs = 0L, // deterministic: gate fires immediately, no timing flake
                    )
                }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.status("backend"), useUnmergedTree = true).assertExists()
        // LIVE ⇒ the dot shows the fresh RUNNING state.
        onNodeWithText(runningLabel).assertExists()

        // Sustained disconnect ⇒ the dot degrades to the honest UNKNOWN (never a stale RUNNING).
        conn.value = ConnectionStatus.DISCONNECTED
        waitForIdle()
        onNodeWithText(unknownLabel).assertExists()
        onNodeWithText(runningLabel).assertDoesNotExist()

        // Reconnect ⇒ fresh server state returns.
        conn.value = ConnectionStatus.LIVE
        waitForIdle()
        onNodeWithText(runningLabel).assertExists()
        onNodeWithText(unknownLabel).assertDoesNotExist()
    }
}
