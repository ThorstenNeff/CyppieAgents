package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.window.COMPOSER_COMPACT_INPUT_THRESHOLD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-370 — the **CommPanel** composer carries the same compact breakpoint as the agent composer, so it gets the
 * same wall. `Cyp370ComposerCompactBreakpointGuardTest` guards `AgentWindow`'s composer; deleting *this* panel's
 * `maxWidth < COMPOSER_COMPACT_INPUT_THRESHOLD + 96.dp` would slip past that one entirely. The shared line needs a
 * guard at each site, or half of it is unprotected.
 *
 * jvmTest, deliberately. The `AgentWindow` guard tried commonTest for wasm reach and failed (`runComposeUiTest`'s
 * capture is not wasm-safe here); this one never could, because CommPanel's send label is a `stringResource`.
 * **Measured: that resource DOES resolve under jvmTest** (the send button's `contentDescription` reads "Send" on
 * an en-locale JVM), so the guard is buildable — the karma limit is real but it is a *browser* limit, and jvmTest
 * is in the gate. The compact/label distinction is read from the **literal glyph** `➤`, which needs no resource,
 * so the straddle is locale-independent; only the a11y name relies on the resource, and it is asserted as
 * *present*, not as a specific localized string.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp370CommComposerCompactBreakpointGuardTest {

    private val threshold = COMPOSER_COMPACT_INPUT_THRESHOLD + 96f

    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    /** One channel, auto-selected → the conversation pane (and its composer) renders at any width. */
    private class OneChannelApi : CommApi {
        override suspend fun channels() = listOf(Channel("a", "A", ChannelKind.HUB, listOf("operator")))
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("s", channelId, "operator", body, 9L)
    }

    private class FakeWritable(val v: List<String>) : WritableChannelsApi {
        override suspend fun writableChannels() = v
    }

    private fun vm() = CommViewModel(
        OneChannelApi(), IdleSource(), viewerId = "operator",
        writableChannels = FakeWritable(listOf("a")), scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private data class Send(val glyphShown: Boolean, val accessibleName: String?)

    @Test
    fun belowTheThreshold_sendIsAGlyph_aboveIt_itIsNot() {
        val below = sendAt(threshold - 1f)
        val above = sendAt(threshold + 1f)
        assertTrue(
            below.glyphShown,
            "CYP-370: below the compact threshold (${threshold - 1f} dp) the CommPanel send control must be the " +
                "glyph, so the input keeps its width. It was not. Did the CommPanel compact breakpoint go?",
        )
        assertTrue(
            !above.glyphShown,
            "CYP-370: above the threshold (${threshold + 1f} dp) the send control must show its label, not the glyph.",
        )
        assertTrue(
            below.glyphShown != above.glyphShown,
            "CYP-370: the threshold does not change the send control — the straddle proves nothing.",
        )
    }

    @Test
    fun theGlyphSendControl_keepsAnAccessibleName() {
        val below = sendAt(threshold - 1f)
        assertTrue(
            !below.accessibleName.isNullOrBlank(),
            "CYP-370: the glyph CommPanel send control must keep an accessible name (the localized send label); " +
                "a glyph with no name is announced as nothing. It was: ${below.accessibleName}",
        )
    }

    private fun sendAt(width: Float): Send {
        lateinit var result: Send
        runComposeUiTest {
            setContent { MaterialTheme { Box(Modifier.width(width.dp).height(800.dp)) { CommPanel(vm()) } } }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(CommTags.COMPOSER_SEND).fetchSemanticsNodes().isNotEmpty()
            }
            val send = onNodeWithTag(CommTags.COMPOSER_SEND).fetchSemanticsNode()
            result = Send(
                glyphShown = onAllNodesWithText(GLYPH).fetchSemanticsNodes().isNotEmpty(),
                accessibleName = send.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
            )
        }
        return result
    }

    private companion object {
        const val GLYPH = "➤"
    }
}
