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
 * Fidelity axis, beside the lifecycle status) and is **fail-closed by absence** — absent for a full-fidelity
 * agent, present when the agent's capabilities are degraded. Clicking it opens the capability panel.
 *
 * **CYP-771 / CYP-746 — `null` caps is no longer one case but two, split by the LIFECYCLE:**
 *  - **D (`null` + RUNNING)** ⇒ the `○` "not yet reported" badge IS present: it should have reported and hasn't.
 *  - **E (`null` + not RUNNING)** ⇒ **NO badge**: a stopped agent has nothing to report yet, so a `○` there was a
 *    false claim. The lifecycle **dot** (`statusDotSpec` RING/FILL) carries E instead.
 *
 * That is why [nullCaps_whileRunning_showsBadge_notYetReported] pins the RUNNING fixture explicitly — before
 * CYP-746 this test used a STOPPED agent and passed, which is exactly the lie the change removed. Its E twin
 * [nullCaps_whileStopped_showsNoBadge_notStarted] holds the other half so neither direction can rot silently.
 *
 * Mutation-style: drop the `isDegraded` gate → the full-fidelity `assertDoesNotExist` goes RED; drop the badge
 * from the header → every presence test RED (the E absence test stays green — it asserts the complement).
 */
@OptIn(ExperimentalTestApi::class)
class AgentWindowCapabilityBadgeTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    /** A STOPPED agent — for the fidelity-independent cases and the CYP-746 **E** (not-started) case. */
    private fun src() = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.STOPPED))

    /** A RUNNING agent — the CYP-746 **D** state, the only one in which `null` caps still earns the `○` badge.
     *  (`AgentSession.connection` defaults to LIVE, so the CYP-573 connection gate passes RUNNING through.) */
    private fun runningSrc() = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.RUNNING))

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

    /**
     * CYP-746 **D** — `null` caps on a **RUNNING** agent: it should have reported and hasn't, so the `○`
     * "not yet reported" badge IS present. (CYP-771: the fixture must be RUNNING; with the old STOPPED fixture
     * this asserted a badge for state E, which is the false claim CYP-746 removed.)
     */
    @Test
    fun nullCaps_whileRunning_showsBadge_notYetReported() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val s = remember { runningSrc() }
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = s, lifecycleSource = s, canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, capabilities = null)
            }
        }
        onNodeWithTag(ConnectorTags.fidelityBadge("backend")).assertExists()
    }

    /**
     * CYP-746 **E** — `null` caps on a **not-running** agent: NO fidelity badge. A stopped agent has nothing to
     * report yet, so the `○` would be a false "it failed to report" claim; the lifecycle dot carries E instead.
     * The twin of [nullCaps_whileRunning_showsBadge_notYetReported]: together they pin that the badge's presence
     * at `null` caps is decided by the LIFECYCLE, not by `null` alone.
     */
    @Test
    fun nullCaps_whileStopped_showsNoBadge_notStarted() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val s = remember { src() }
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = s, lifecycleSource = s, canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, capabilities = null)
            }
        }
        onNodeWithTag(ConnectorTags.fidelityBadge("backend")).assertDoesNotExist()
    }
}
