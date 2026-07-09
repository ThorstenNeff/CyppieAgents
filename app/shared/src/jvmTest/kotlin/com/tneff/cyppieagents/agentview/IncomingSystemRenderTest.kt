package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-326 #1 — an injected incoming/system message renders as a distinct `IncomingSystem` row: a visible "System"
 * label + decorative `⇥`, the raw injected text verbatim, and an invisible "System: …" screen-reader description
 * — distinct from the operator's own [AgentEvent.UserTurn] (CYP-323). (`a11y_transcript_system` = "System: %1$s"
 * is locale-identical, so the assertion is locale-robust.)
 */
@OptIn(ExperimentalTestApi::class)
class IncomingSystemRenderTest {

    private fun sessionEmitting(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    @Test
    fun incomingSystem_rendersDistinctSystemRow_withRawTextAndAria() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(AgentEvent.IncomingSystem("s1", "/compact", tsMs = 0L)), "backend") }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithContentDescription("System: /compact", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The row is tagged as the system kind, and the SR description carries the label + the raw text verbatim.
        onNodeWithTag(AgentViewTags.event("backend", 0, EventKind.INCOMING_SYSTEM), useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("System: /compact", substring = true).assertExists()
        // Distinct from the operator's own composer turn (CYP-323): NOT a userTurn row.
        assertTrue(
            onAllNodesWithTag(AgentViewTags.event("backend", 0, EventKind.USER_TURN)).fetchSemanticsNodes().isEmpty(),
            "an injected system message is not the operator's userTurn",
        )
    }
}
