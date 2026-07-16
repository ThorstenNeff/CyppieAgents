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
 * CYP-573 T-B (the WIRING tooth) — end-to-end proof that the header actually connection-gates the status.
 *
 * The pure [gatedLifecycleState] tooth ([Cyp573StatusDotConnectionGateTest]) proves the DECISION but not the
 * wiring: if the `StatusIndicator(..., connection)` call-site argument were reverted, `connection` would fall back
 * to its LIVE default and the bug would return with the pure tooth still green. This renders the real
 * [AgentWindow] with a RUNNING lifecycle state behind a **DISCONNECTED** session and asserts the header shows the
 * honest "unbekannt", never the stale "Läuft".
 *
 * Reddening mutation: drop the `connection` argument at the [StatusIndicator] call site (or the `!= LIVE` guard) ⇒
 * the RUNNING label renders across the drop ⇒ red.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp573StatusDotConnectionGateRenderTest {

    /** A session whose live feed is DOWN (a real WS drop / server restart), lifecycle held at last-known RUNNING. */
    private fun disconnectedSession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
        override val connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    }

    @Test
    fun runningBehindADroppedFeed_rendersUnknown_notStaleRunning() = runComposeUiTest {
        lateinit var runningLabel: String
        lateinit var unknownLabel: String
        setContent {
            MaterialTheme {
                runningLabel = stringResource(Res.string.agent_status_running)
                unknownLabel = stringResource(Res.string.agent_status_unknown)
                // Last-known lifecycle state = RUNNING (the stale value the source holds across the drop)...
                val src = remember { StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.RUNNING)) }
                val vm = remember {
                    AgentViewModel(
                        disconnectedSession(), // ...but the feed that would refresh it is DOWN.
                        agentId = "backend",
                        lifecycle = src,
                        lifecycleSource = src,
                        canControl = false,
                    )
                }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.status("backend")).assertExists()
        // The fix: the dropped feed cannot prove RUNNING → the header fails closed to "unbekannt".
        onNodeWithText(unknownLabel).assertExists()
        onNodeWithText(runningLabel).assertDoesNotExist()
        // The reconnecting chip is present in lockstep (connection != LIVE) — the two freshness signals agree.
        onNodeWithTag(AgentViewTags.reconnecting("backend")).assertExists()
    }
}
