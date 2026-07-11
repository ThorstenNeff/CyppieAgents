package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-381 §7.1 — the CONTEXT_LOST transcript chrome, its **durable-vs-live** split. The discontinuity band is
 * anchored to the loss instant (a durable landmark), NOT the momentary control-state — so it must render when the
 * feed reports CONTEXT_LOST, **persist after the state recovers to MEDIATED** (while the live banner clears), and
 * be **absent** when there is no landmark (never guessed). Colour-demotion of the receded rows is visual (no text
 * node to assert); the load-bearing, testable invariant is the landmark's durability, pinned here.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp381ContextLostTranscriptTest {

    private val agentId = "backend"
    private val stamp = 1_783_628_386_000L
    private val minute = 60_000L

    private fun sessionEmitting(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    /** One row BEFORE the loss (forgotten history) and one AFTER (fresh, in memory). */
    private fun transcriptAroundLoss() = arrayOf(
        AgentEvent.AssistantText("a-old", "vor dem Verlust", complete = true, tsMs = stamp),
        AgentEvent.AssistantText("a-new", "nach dem Verlust", complete = true, tsMs = stamp + minute),
    )

    /** The loss instant, between the two rows → the band sits atop the second (fresh) row. */
    private val lossTs = stamp + 30_000L

    @Test
    fun contextLost_rendersTheDurableDiscontinuityBand() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(*transcriptAroundLoss()), agentId) }
                AgentWindow(
                    agentId = agentId,
                    viewModel = vm,
                    control = AgentTerminalControlEvent(agentId, TerminalControlState.CONTEXT_LOST, since = lossTs),
                )
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.contextLostDivider(agentId), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.contextLostDivider(agentId), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theBand_persistsAfterRecoveryToMediated_whileTheLiveBannerClears() = runComposeUiTest {
        val control = mutableStateOf<AgentTerminalControlEvent?>(
            AgentTerminalControlEvent(agentId, TerminalControlState.CONTEXT_LOST, since = lossTs),
        )
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(*transcriptAroundLoss()), agentId) }
                AgentWindow(agentId = agentId, viewModel = vm, control = control.value)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.contextLostDivider(agentId), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The next real turn moves the live state CONTEXT_LOST → MEDIATED: the live banner clears, but the durable
        // landmark stays put (anchored to the loss ts, not the momentary state). This IS the honesty test.
        control.value = AgentTerminalControlEvent(agentId, TerminalControlState.MEDIATED)
        waitForIdle()
        onNodeWithTag(AgentViewTags.contextLostDivider(agentId), useUnmergedTree = true)
            .assertIsDisplayed() // durable: survives recovery
        onNodeWithTag(AgentViewTags.contextLostBanner(agentId), useUnmergedTree = true)
            .assertDoesNotExist() // live chrome cleared
    }

    @Test
    fun noLandmark_whenControlIsNull_neverGuessed() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(*transcriptAroundLoss()), agentId) }
                AgentWindow(agentId = agentId, viewModel = vm, control = null)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream(agentId), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.contextLostDivider(agentId), useUnmergedTree = true).assertDoesNotExist()
    }
}
