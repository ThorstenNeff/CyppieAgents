package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-335 — every one of the six transcript line kinds renders an `HH:mm` gutter; the two client-born rows take
 * it from the injected clock; the cell is baseline-aligned, labelled for a screen reader, and still addressable
 * by test tag despite its cleared semantics.
 *
 * Times are asserted through [formatLocalHhMm] against the *runner's* zone rather than a hardcoded string: the
 * suite must not fail because CI runs in UTC and a developer runs in Berlin. What is pinned is the invariant —
 * the row shows the local rendering of the event's own `tsMs`.
 */
@OptIn(ExperimentalTestApi::class)
class TranscriptTimestampRenderTest {

    private val agentId = "backend"

    /** 2026-07-09T20:19:46Z — a realistic, non-zero stamp. */
    private val stamp = 1_783_628_386_000L

    /** What the running machine's zone renders for [tsMs] — the same seam the UI uses. */
    private fun expected(tsMs: Long) = formatLocalHhMm(tsMs)

    private fun sessionEmitting(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    @Test
    fun allSixLineKinds_renderTheirOwnTimestamp() = runComposeUiTest {
        // One of each kind, each with a DISTINCT stamp one minute apart, so a row showing another row's time
        // (or a single shared "now") fails rather than coincidentally passing.
        val minute = 60_000L
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(
                        sessionEmitting(
                            AgentEvent.Notice("n-1", "Session gestartet", tsMs = stamp),
                            AgentEvent.AssistantText("a-1", "Antwort", complete = true, tsMs = stamp + minute),
                            AgentEvent.ToolCall("t-1", "read_file", "b.kts", ToolStatus.OK, tsMs = stamp + 2 * minute),
                            AgentEvent.Result("r-1", "42 Zeilen", isError = false, tsMs = stamp + 3 * minute),
                            AgentEvent.IncomingSystem("s-1", "/compact", tsMs = stamp + 4 * minute),
                            AgentEvent.UserTurn("u-1", "hallo", tsMs = stamp + 5 * minute),
                        ),
                        agentId,
                    )
                }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 5), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The cell's semantics are cleared, so its VISIBLE text is not in the tree — the labelled description is.
        // Asserting `contains` keeps this locale-robust: DE "um 09:14 Uhr" and EN "at 09:14" both contain the time.
        repeat(6) { index ->
            onNodeWithTag(AgentViewTags.eventTime(agentId, index), useUnmergedTree = true)
                .assertContentDescriptionContains(expected(stamp + index * minute), substring = true)
        }
    }

    @Test
    fun timeCell_isAddressableByTag_andAnnouncesTheLabelledTime() = runComposeUiTest {
        // The cell's semantics are cleared, so it has no text node: it must still be addressable by tag AND it
        // must speak the labelled description, not bare digits. Both are pinned here.
        //
        // Scope note (measured, not assumed): the spec warns that `clearAndSetSemantics` swallows a `testTag`
        // applied before it. Moving the tag outside the block leaves this test GREEN on Compose 1.9 / Kotlin 2.4
        // — the failure mode does not reproduce. The tag stays inside anyway (spec contract, order-independent),
        // but this test does not prove that gotcha and must not be cited as if it did.
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(sessionEmitting(AgentEvent.Notice("n-1", "hi", tsMs = stamp)), agentId)
                }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
            .assertExists()
            .assertContentDescriptionContains(expected(stamp), substring = true)
    }

    @Test
    fun timeCell_isBaselineAligned_notTopAligned() = runComposeUiTest {
        // The distinguishing property: the time is labelSmall (11 sp), the content bodyMedium (14 sp). Aligning
        // the BOXES (Alignment.Top) puts both tops at the same y. Aligning the BASELINES pushes the smaller
        // digits DOWN, so their first baseline meets the content's. A strict `>` separates the two.
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(
                        sessionEmitting(AgentEvent.AssistantText("a-1", "Antwort", complete = true, tsMs = stamp)),
                        agentId,
                    )
                }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val timeTop = onNodeWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top.value
        val contentTop = onNodeWithTag(AgentViewTags.event(agentId, 0, EventKind.ASSISTANT_TEXT), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top.value
        assertTrue(
            timeTop > contentTop,
            "baseline alignment must push the smaller digits down; top-aligned would give timeTop == contentTop " +
                "(timeTop=$timeTop, contentTop=$contentTop)",
        )
    }

    @Test
    fun timeCell_baselinePropagatesThroughResultRowsPaddedContainer() = runComposeUiTest {
        // The one row the spec asked Dev5 to verify: ResultRow wraps its text in a tinted, `vertical = 4.dp`
        // padded container. If Compose did not propagate the first baseline through that padding, the cell would
        // fall back to top alignment and the digits would float. It does — the padding shifts the reported
        // baseline down, which is exactly the wanted result, so the gap here is LARGER than the unpadded case.
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(
                        sessionEmitting(
                            AgentEvent.AssistantText("a-1", "Antwort", complete = true, tsMs = stamp),
                            AgentEvent.Result("r-1", "42 Zeilen gelesen", isError = false, tsMs = stamp),
                        ),
                        agentId,
                    )
                }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 1), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        fun gap(index: Int, contentTag: String): Float {
            val timeTop = onNodeWithTag(AgentViewTags.eventTime(agentId, index), useUnmergedTree = true)
                .getUnclippedBoundsInRoot().top.value
            val contentTop = onNodeWithTag(contentTag, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value
            return timeTop - contentTop
        }
        val assistantGap = gap(0, AgentViewTags.event(agentId, 0, EventKind.ASSISTANT_TEXT))
        val resultGap = gap(1, AgentViewTags.event(agentId, 1, EventKind.TOOL_RESULT))
        assertTrue(resultGap > 0f, "the padded ResultRow must still baseline-align its time cell (gap=$resultGap)")
        assertTrue(
            resultGap > assistantGap,
            "ResultRow's 4dp top padding shifts its baseline down, so its time cell sits LOWER than the " +
                "assistant row's (assistantGap=$assistantGap, resultGap=$resultGap)",
        )
    }

    @Test
    fun growingAssistantRow_showsFirstDeltasTime_notTheLatest() = runComposeUiTest {
        // Rendered proof of the fold rule: three deltas 10 minutes apart collapse to one row dated by the first.
        val tenMinutes = 600_000L
        val bus = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(
                        object : AgentSession {
                            override val events: Flow<AgentEvent> = bus
                            override fun sendMessage(text: String) {}
                        },
                        agentId,
                    )
                }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        bus.tryEmit(AgentEvent.AssistantText("a-1", "Ich ", complete = false, tsMs = stamp))
        bus.tryEmit(AgentEvent.AssistantText("a-1", "arbeite.", complete = true, tsMs = stamp + tenMinutes))

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
            .assertContentDescriptionContains(expected(stamp), substring = true)
        assertEquals(
            0,
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 1), useUnmergedTree = true).fetchSemanticsNodes().size,
            "the two deltas folded into ONE row — there is no second time gutter",
        )
    }

    @Test
    fun userTurn_isDatedByTheInjectedClock() = runComposeUiTest {
        // The composer's echo never touches the wire, so its time comes from the VM's clock. Faked → deterministic.
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(sessionEmitting(), agentId, nowMs = { stamp }) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.input(agentId)).performTextInput("hallo")
        onNodeWithTag(AgentViewTags.sendBtn(agentId)).performClick()

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
            .assertContentDescriptionContains(expected(stamp), substring = true)
    }

    @Test
    fun connErrorNotice_isDatedByTheInjectedClock() = runComposeUiTest {
        // A fatal (non-reconnecting) stream error appends the honest "connection lost" notice; it too is dated
        // by the injected clock.
        val failing = object : AgentSession {
            override val events: Flow<AgentEvent> = flow { throw IllegalStateException("fatal") }
            override fun sendMessage(text: String) {}
        }
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(failing, agentId, nowMs = { stamp }) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.eventTime(agentId, 0), useUnmergedTree = true)
            .assertContentDescriptionContains(expected(stamp), substring = true)
    }

    @Test
    fun successiveConnErrorNotices_doNotShareAnId() {
        // Pure-reducer teeth for the id fix: two connection-loss notices minted by the VM's counter fold into two
        // rows with their own times. With the old constant "conn-error" id the second is swallowed and the
        // surviving row lies about when the second failure happened.
        val folded = foldEvents(
            listOf(
                AgentEvent.Notice("conn-error-0", "Verbindung zum Agenten verloren", tsMs = stamp),
                AgentEvent.Notice("conn-error-1", "Verbindung zum Agenten verloren", tsMs = stamp + 60_000L),
            )
        )
        assertEquals(2, folded.size, "a second connection loss must be its own row, with its own time")
        assertEquals(listOf(stamp, stamp + 60_000L), folded.map { it.tsMs })
    }
}
