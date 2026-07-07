package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-273 — the composer's per-channel write treatment (tri-state, fail-closed). Three distinct renders driven
 * by the DERIVED `CommUiState.canWrite`:
 *  - `true`  (selected channel in the writable set) → an ENABLED input, no read-only hint.
 *  - `false` (readable but NOT writable) → the proactive read-only hint (`COMPOSER_READONLY`), no input.
 *  - `null`  (writability unknown / fetch error) → a DISABLED input, and **no** "no permission" claim (honesty:
 *            an unknown is not a denial — the CYP-288 fail-closed class).
 *
 * Mutation proof: force the composer to ignore `canWrite` (always editable) → the read-only and disabled cases
 * RED; swallow the fetch error to an empty set instead of null → the unknown case shows the read-only hint (RED).
 */
@OptIn(ExperimentalTestApi::class)
class CommComposerWritabilityRenderTest {

    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    /** One channel 'a', auto-selected → the composer's write state is fully determined by the writable seam. */
    private class OneChannelApi : CommApi {
        override suspend fun channels(): List<Channel> = listOf(Channel("a", "A", ChannelKind.HUB, listOf("operator")))
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("srv", channelId, "operator", body, 9L)
    }

    private class FakeWritable(val value: List<String>, val fail: Boolean = false) : WritableChannelsApi {
        override suspend fun writableChannels(): List<String> {
            if (fail) throw RuntimeException("writable boom")
            return value
        }
    }

    // Unconfined → the channels + writable loads settle SYNCHRONOUSLY at construction → deterministic waitForIdle.
    private fun vm(writable: WritableChannelsApi) = CommViewModel(
        OneChannelApi(), IdleSource(), viewerId = "operator",
        writableChannels = writable, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun writableChannel_composerEnabled_noReadOnlyHint() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(FakeWritable(listOf("a")))) } } }
        waitForIdle()
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertExists()
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertIsEnabled()
        onNodeWithTag(CommTags.COMPOSER_READONLY).assertDoesNotExist()
    }

    @Test
    fun readOnlyChannel_showsReadOnlyHint_noInput() = runComposeUiTest {
        // 'a' readable but not writable (empty writable set) → known read-only → the hint, not an input.
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(FakeWritable(emptyList()))) } } }
        waitForIdle()
        onNodeWithTag(CommTags.COMPOSER_READONLY).assertExists()
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertDoesNotExist()
    }

    @Test
    fun unknownWritability_composerDisabled_announcesDisabledNotReadOnly() = runComposeUiTest {
        // Fetch error → writable null → fail-closed: a DISABLED input, but NOT the "no permission" hint (honesty).
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(FakeWritable(emptyList(), fail = true))) } } }
        waitForIdle()
        // UIUX-§-QA #1 (a11y mirror): while writability is UNKNOWN the screen reader must hear "disabled" but
        // NEVER a denial reason. The composer uses `enabled = false` (SR → "disabled"), NOT `readOnly = true`
        // (which would announce "read-only") — so `assertIsNotEnabled()` pins the honest SR state AND reds a
        // regression to a read-only field (a readOnly TextField stays enabled → this assert fails). And the
        // read-only reason node (COMPOSER_READONLY) — the sole carrier of the read-only wording, visual AND SR —
        // is ABSENT, so no unbacked "kein Schreibrecht" claim leaks acoustically. Both the input and the send
        // button are inert (fail-closed).
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertExists()          // #2: keeps its form → reads as an inactive field
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertIsNotEnabled()    // SR announces "disabled", not "read-only"
        onNodeWithTag(CommTags.COMPOSER_SEND).assertIsNotEnabled()
        onNodeWithTag(CommTags.COMPOSER_READONLY).assertDoesNotExist() // no read-only reason node → no SR denial claim
    }
}
