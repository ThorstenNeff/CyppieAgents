package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-381 §6/§7b — the persistent WARN frame banners driven by the CYP-354 control-state (feed truth, fail-closed):
 *  - **INTERACTIVE** → the hub-blind banner ("the hub is not mediating" + holder), NOT the context-lost banner;
 *  - **CONTEXT_LOST** → the context-lost banner, NOT the hub-blind banner;
 *  - **MEDIATED / null** → NEITHER (fail-closed — the stub reports none of these, so no banner shows: honest).
 *  Never keystrokes/PTY content — state/identity only. jvmTest render locale = EN.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp381HandoffBannerTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun renderWith(control: AgentTerminalControlEvent?, body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    val vm = remember { AgentViewModel(emptySession(), agentId = "backend", canControl = false) }
                    AgentWindow(agentId = "backend", viewModel = vm, control = control)
                }
            }
            waitForIdle()
            body()
        }

    @Test
    fun interactive_showsHubBlindBanner_withHolder_notContextLost() = renderWith(
        AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "alice", since = 1_700_000_000_000L),
    ) {
        // Mutation: return the MEDIATED/null branch for INTERACTIVE (drop the banner) → RED.
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true)
            .assertTextContains("hub is not mediating", substring = true)
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true)
            .assertTextContains("@alice", substring = true)
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun contextLost_showsContextLostBanner_notHubBlind() = renderWith(
        AgentTerminalControlEvent("backend", TerminalControlState.CONTEXT_LOST),
    ) {
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true)
            .assertTextContains("history not restored", substring = true)
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun mediated_showsNoBanner_failClosed() = renderWith(
        AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED),
    ) {
        // Honesty: MEDIATED (and an absent/null control) render NO banner — the stub state stays banner-free.
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun nullControl_showsNoBanner_failClosed() = renderWith(null) {
        onNodeWithTag(AgentViewTags.handoffBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true).assertDoesNotExist()
    }
}
