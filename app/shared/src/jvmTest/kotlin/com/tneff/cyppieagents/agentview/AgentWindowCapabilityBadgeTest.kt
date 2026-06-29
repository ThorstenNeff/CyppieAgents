package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connector.ConnectorTags
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-123 host-wiring: the connector **fidelity badge** is anchored in the agent-window header (its own
 * Fidelity axis, beside the lifecycle status) and is **fail-closed by absence** — present only when the agent's
 * capabilities are degraded or not-yet-reported (`null`), absent for a full-fidelity agent. Clicking it opens
 * the capability panel. Mutation-style: drop the `isDegraded` gate → the full-fidelity `assertDoesNotExist`
 * goes RED; drop the badge from the header → all three RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentWindowCapabilityBadgeTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun src() = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.STOPPED))

    private fun full() = Capabilities(
        CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE,
        CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )

    private fun degraded() = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED, CapabilityStatus.LIMITED,
        CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE, ConnectorKind.MCP,
    )

    @Test
    fun fullFidelity_showsNoBadge_failClosedByAbsence() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val s = remember { src() }
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = s, lifecycleSource = s, canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, capabilities = full())
            }
        }
        onNodeWithTag(ConnectorTags.fidelityBadge("backend")).assertDoesNotExist()
    }

    @Test
    fun degraded_showsBadgeInHeader_andClickOpensPanel() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                val s = remember { src() }
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = s, lifecycleSource = s, canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, capabilities = degraded(), onCapabilityBadgeClick = { clicked = true })
            }
        }
        onNodeWithTag(ConnectorTags.fidelityBadge("backend")).assertExists().performClick()
        assertTrue(clicked, "the header fidelity badge must open the capability panel")
    }

    @Test
    fun nullCaps_showsBadge_notYetReported() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val s = remember { src() }
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = s, lifecycleSource = s, canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, capabilities = null)
            }
        }
        onNodeWithTag(ConnectorTags.fidelityBadge("backend")).assertExists()
    }
}
