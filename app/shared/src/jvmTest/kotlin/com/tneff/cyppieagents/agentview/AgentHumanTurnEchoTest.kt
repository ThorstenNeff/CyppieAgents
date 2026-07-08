package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.ui.MaritimeDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-323 — the human turn is echoed into the LOCAL transcript on send, chronologically before the agent's
 * reply, set apart with `colorScheme.secondary` + a decorative leading `›`, and given an invisible
 * "Deine Nachricht: …" screen-reader label (WCAG 1.4.1 — the `›` is a visual-only second signal, so a
 * non-visual discriminator is mandatory).
 *
 * Mutation proof:
 *  - onSend swallows the echo (drop the `_transcript.update{…UserTurn…}` line) → no user-turn row →
 *    [humanTurn_echoedBeforeAgentReply_withSrLabel] REDs.
 *  - the user-turn text is coloured `onSurface`/inherited instead of `secondary` →
 *    [userTurnText_rendersSecondaryRole_notOnSurface] REDs.
 *  - the SR label is dropped → the content-description assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class AgentHumanTurnEchoTest {

    private val agentId = "backend"

    private fun sessionEmitting(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    /** Records forwarded turns and replies once per send — lets a test observe echo-before-reply ordering. */
    private class EchoOnSendSession : AgentSession {
        private val bus = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
        val sent = mutableListOf<String>()
        override val events: Flow<AgentEvent> = bus
        override fun sendMessage(text: String) {
            sent += text
            bus.tryEmit(AgentEvent.AssistantText("reply-${sent.size}", "OK: $text", complete = true))
        }
    }

    // --- Pure reducer: chronological order + idempotent id-dedup (no VM / dispatcher) ---

    @Test
    fun foldEvents_userTurn_precedesAgentReply_inOrder() {
        val folded = foldEvents(
            listOf(
                AgentEvent.UserTurn("user-0", "Hallo"),
                AgentEvent.AssistantText("a-1", "Antwort", complete = true),
            )
        )
        assertEquals(2, folded.size)
        assertTrue(folded[0] is AgentEvent.UserTurn, "human turn folds first")
        assertTrue(folded[1] is AgentEvent.AssistantText, "agent reply follows")
        assertEquals("Hallo", (folded[0] as AgentEvent.UserTurn).text)
    }

    @Test
    fun foldEvent_userTurn_dedupedByStableId() {
        val turn = AgentEvent.UserTurn("user-0", "Hallo")
        val twice = foldEvent(foldEvent(emptyList(), turn), turn) // same id re-applied → insurance
        assertEquals(1, twice.size, "a re-applied turn with the same id must not double")
    }

    // --- Render: echo via the real composer path ---

    @Test
    fun humanTurn_echoedBeforeAgentReply_withSrLabel() = runComposeUiTest {
        val session = EchoOnSendSession()
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(session, agentId) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.input(agentId)).performTextInput("Hallo Agent")
        onNodeWithTag(AgentViewTags.sendBtn(agentId)).performClick()
        waitForIdle()

        // The echo lands at index 0, BEFORE the agent reply that arrives on the stream at index 1.
        onNodeWithTag(AgentViewTags.event(agentId, 0, EventKind.USER_TURN), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.event(agentId, 1, EventKind.ASSISTANT_TEXT)).assertExists()
        // Invisible SR label carries the message text (locale-robust: assert on the message, not the DE/EN prefix).
        onNodeWithContentDescription("Hallo Agent", substring = true).assertExists()
        // …and the turn is still forwarded to the session.
        assertEquals(listOf("Hallo Agent"), session.sent)
    }

    @Test
    fun agentOnlyTranscript_hasNoUserTurnMarker() = runComposeUiTest {
        // Discriminator: a pure agent transcript carries no userTurn row and no "Deine Nachricht:" SR label.
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(AgentEvent.AssistantText("a-1", "Nur Agent", complete = true)), agentId) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.event(agentId, 0, EventKind.ASSISTANT_TEXT)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithTag(AgentViewTags.event(agentId, 0, EventKind.USER_TURN)).fetchSemanticsNodes().isEmpty(),
            "an agent row must not be tagged userTurn",
        )
        assertTrue(
            onAllNodesWithContentDescription("Nur Agent", substring = true).fetchSemanticsNodes().isEmpty(),
            "an agent row must not carry the invisible human-turn label (assistant text is `text`, not contentDescription)",
        )
    }

    @Test
    fun userTurnText_rendersSecondaryRole_notOnSurface() = runComposeUiTest {
        val session = EchoOnSendSession()
        setContent {
            MaterialTheme(colorScheme = MaritimeDark) {
                val vm = remember { AgentViewModel(session, agentId) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.input(agentId)).performTextInput("Farbe")
        onNodeWithTag(AgentViewTags.sendBtn(agentId)).performClick()
        waitForIdle()

        // Capture the MESSAGE text node specifically (via its SR label), NOT the whole row — the decorative caret
        // is also secondary, so a row-level capture couldn't tell a mis-coloured message from the caret.
        val pm = onNodeWithContentDescription("Farbe", substring = true, useUnmergedTree = true)
            .captureToImage().toPixelMap()
        // Night secondary #93CCEA → blue≈0.92, green≈0.80, red≈0.58 — distinct from onSurface #DCE7ED (red≈0.86)
        // and from the dark surface #06121A (blue≈0.10). A secondary-coloured glyph pixel must exist.
        var secondaryPixels = 0
        for (y in 0 until pm.height) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                if (c.blue > 0.7f && c.green > 0.55f && c.red < 0.72f) secondaryPixels++
            }
        }
        assertTrue(secondaryPixels > 0, "human-turn text must render in colorScheme.secondary, not onSurface/inherited")
    }
}
