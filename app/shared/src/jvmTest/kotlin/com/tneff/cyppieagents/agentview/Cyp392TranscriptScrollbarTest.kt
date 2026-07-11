package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-392 — the transcript vertical scrollbar (Desktop/Web; the jvm test stands in for the skiko renderer). The
 * scrollbar is present ONLY when the transcript overflows its viewport, and absent when it fits — the ticket's
 * "nur sichtbar wenn scrollbar". The transition (short → tall content) is the guard against a scrollbar that is
 * always present or never present.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp392TranscriptScrollbarTest {

    /** Many rows in a short window overflow → the scrollbar is placed.
     *  Mutation: drop the `canScroll*` gate to always-show ⇒ still present here (green) — the FITS test below is the
     *  one that reds an always-on scrollbar; together they pin "only when scrollable". */
    @Test
    fun overflowingTranscript_showsScrollbar() {
        runComposeUiTest {
            val vm = AgentViewModel(StubAgentSession(), agentId = AGENT_ID)
            repeat(60) { vm.onSend("line $it") } // 60 user-turn rows → overflows a short window
            setContent { MaterialTheme { Box(Modifier.size(360.dp, 220.dp)) { AgentWindow(AGENT_ID, vm) } } }
            waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentViewTags.scrollbar(AGENT_ID)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(AgentViewTags.scrollbar(AGENT_ID)).assertIsDisplayed()
        }
    }

    /** A single row in a tall window fits → NO scrollbar (the honest "only when scrollable").
     *  Mutation: remove the `canScroll*` gate (always show) ⇒ the scrollbar exists here ⇒ red. */
    @Test
    fun fittingTranscript_hasNoScrollbar() {
        runComposeUiTest {
            val vm = AgentViewModel(StubAgentSession(), agentId = AGENT_ID) // just the stub's one "ready" notice
            setContent { MaterialTheme { Box(Modifier.size(360.dp, 900.dp)) { AgentWindow(AGENT_ID, vm) } } }
            waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentViewTags.stream(AGENT_ID)).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithTag(AgentViewTags.scrollbar(AGENT_ID)).fetchSemanticsNodes().size,
                "a transcript that fits shows no scrollbar",
            )
        }
    }

    private companion object {
        const val AGENT_ID = "po"
    }
}
