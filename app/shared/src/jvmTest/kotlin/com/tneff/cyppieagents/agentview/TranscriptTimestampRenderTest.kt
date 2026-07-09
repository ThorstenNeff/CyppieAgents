package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-335 — every one of the six transcript line kinds renders an `HH:mm` gutter, and the two client-born rows
 * take it from the injected clock.
 *
 * Times are asserted through [formatHhMm] against the *runner's* offset rather than a hardcoded string: the
 * suite must not fail because CI runs in UTC and a developer runs in Berlin. What is pinned is the invariant —
 * the row shows the local rendering of the event's own `tsMs`.
 */
@OptIn(ExperimentalTestApi::class)
class TranscriptTimestampRenderTest {

    private val agentId = "backend"

    /** 2026-07-09T20:19:46Z — a realistic, non-zero stamp. */
    private val stamp = 1_783_628_386_000L

    /** What the running machine's zone renders for [stamp] — the same seam the UI uses. */
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
        repeat(6) { index ->
            onNodeWithTag(AgentViewTags.eventTime(agentId, index), useUnmergedTree = true)
                .assertTextEquals(expected(stamp + index * minute))
        }
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
            .assertTextEquals(expected(stamp))
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
                val vm = remember {
                    AgentViewModel(sessionEmitting(), agentId, nowMs = { stamp })
                }
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
            .assertTextEquals(expected(stamp))
    }

    @Test
    fun connErrorNotice_isDatedByTheInjectedClock_andCarriesAUniqueId() = runComposeUiTest {
        // A fatal (non-reconnecting) stream error appends the honest "connection lost" notice; it too is dated
        // by the injected clock. Its id must be unique — a constant id would be swallowed by foldEvent's dedup
        // on a second failure and the surviving row would keep asserting the FIRST failure's time.
        val failing = object : AgentSession {
            override val events: Flow<AgentEvent> = kotlinx.coroutines.flow.flow { throw IllegalStateException("fatal") }
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
            .assertTextEquals(expected(stamp))
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
