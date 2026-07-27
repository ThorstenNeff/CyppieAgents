package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-819 (A2) — the **indicator-demotion honesty core**: on a session-wide 1008 revoke each agent window stops
 * claiming currency. This proves the two per-window demotions that live in [AgentWindow]/[AgentHeader]:
 *  - the reconnecting **`↻` chip is SUPPRESSED** (a terminal revoke is not a recoverable reconnect — `↻` would
 *    falsely imply recovery); and
 *  - the run-state **dot demotes to UNKNOWN** (no frozen `RUNNING` claim), even while the underlying socket reads LIVE.
 *
 * A/B against the SAME inputs isolates the revoke axis. Non-vacuity (mutation):
 *  - drop the `if (!statusRevoked)` guard on the chip → the chip renders under revoke → [chipSuppressed] RED;
 *  - drop the `effectiveConnection` demotion (use raw `connection`) → the dot stays `Running` → [dotDemoted] RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp819IndicatorDemotionRenderTest {

    private fun sessionWith(conn: ConnectionStatus) = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override val connection = MutableStateFlow(conn)
        override fun sendMessage(text: String) {}
    }

    private class RunningLifecycle(private val state: AgentLifecycleState) : AgentLifecycleApi, AgentLifecycleSource {
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = mapOf("backend" to state)
        override fun events(): Flow<AgentLifecycleEvent> = emptyFlow()
        override suspend fun start(agentId: String) {}
        override suspend fun stop(agentId: String) {}
        override suspend fun restart(agentId: String) {}
    }

    private fun chip() = AgentViewTags.reconnecting("backend")

    @Test
    fun chipSuppressed_onRevoke_butShown_whenMerelyDisconnected() = runComposeUiTest {
        // A non-LIVE socket normally shows the reconnecting chip …
        val disconnected = RunningLifecycle(AgentLifecycleState.RUNNING)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionWith(ConnectionStatus.DISCONNECTED), agentId = "backend", lifecycle = disconnected, lifecycleSource = disconnected, canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm, statusRevoked = false)
            }
        }
        waitForIdle()
        onNodeWithTag(chip(), useUnmergedTree = true).assertExists() // baseline: disconnected → ↻ shows
    }

    @Test
    fun chipSuppressed_onRevoke() = runComposeUiTest {
        // … but a revoke SUPPRESSES it (terminal, not reconnecting), same non-LIVE socket.
        val disconnected = RunningLifecycle(AgentLifecycleState.RUNNING)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionWith(ConnectionStatus.DISCONNECTED), agentId = "backend", lifecycle = disconnected, lifecycleSource = disconnected, canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm, statusRevoked = true)
            }
        }
        waitForIdle()
        onNodeWithTag(chip(), useUnmergedTree = true).assertDoesNotExist() // revoke → ↻ suppressed
    }

    @Test
    fun dotDemotedToUnknown_onRevoke_evenWhenSocketLive() = runComposeUiTest {
        // RUNNING + a LIVE socket: without revoke the dot claims "Running"; a revoke demotes it to UNKNOWN.
        val running = RunningLifecycle(AgentLifecycleState.RUNNING)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionWith(ConnectionStatus.LIVE), agentId = "backend", lifecycle = running, lifecycleSource = running, canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm, statusRevoked = true)
            }
        }
        waitForIdle()
        // The dot no longer claims "Running" (it demotes to the UNKNOWN ring) — no frozen live value.
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun dotShowsRunning_withoutRevoke_control() = runComposeUiTest {
        val running = RunningLifecycle(AgentLifecycleState.RUNNING)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionWith(ConnectionStatus.LIVE), agentId = "backend", lifecycle = running, lifecycleSource = running, canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm, statusRevoked = false)
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertExists()
    }
}
