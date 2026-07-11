package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-387 §0/§2 — the WIRING proof (what UIUX-QA checks by hand): ↑/↓ in the focused single-line composer really
 * reach the recall cursor and set the field. The transition logic itself is pinned purely by [Cyp387ComposerRecallTest]
 * (incl. test 3 immutability); this proves the key actually routes end-to-end through [AgentWindow].
 */
@OptIn(ExperimentalTestApi::class)
class Cyp387ComposerHistoryKeyWiringTest {

    @Test
    fun upArrow_recallsNewestThenOlder_downReturnsNewer() {
        runComposeUiTest {
            val vm = AgentViewModel(StubAgentSession(), agentId = AGENT_ID)
            vm.onSend("erste nachricht")
            vm.onSend("zweite nachricht") // history newest-last: [erste, zweite]
            setContent { MaterialTheme { Box(Modifier.size(700.dp, 900.dp)) { AgentWindow(AGENT_ID, vm) } } }

            waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentViewTags.input(AGENT_ID)).fetchSemanticsNodes().isNotEmpty() }
            val input = onNodeWithTag(AgentViewTags.input(AGENT_ID))
            input.requestFocus()

            input.performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals("zweite nachricht", input.editableText(), "↑ recalls the NEWEST sent message into the field")

            input.performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals("erste nachricht", input.editableText(), "a second ↑ steps to the older message")

            input.performKeyInput { pressKey(Key.DirectionDown) }
            assertEquals("zweite nachricht", input.editableText(), "↓ steps back toward the newer message")
        }
    }

    private fun SemanticsNodeInteraction.editableText(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private companion object {
        const val AGENT_ID = "po"
    }
}
