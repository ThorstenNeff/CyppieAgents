package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.TextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-571 (Dev5 client-render axis) — the **S2 client-render KERN**: when the persisted transcript is replayed
 * to the window (fresh open / reconnect backfill), the window **RE-FILLS with non-blank rows** — the
 * persisted-agent-window promise, not the old blank screen.
 *
 * This drives the REAL replay path — a stream of durable [StoredAgentEvent]s through the real
 * [MappingAgentSession] → [StreamJsonMapper] → [AgentViewModel] → [AgentWindow] (the `flowOf` source is the
 * proven, deterministic render pattern; the wire/reconnect layer is covered separately). It is the render-side
 * complement of the already-green store/wire teeth — **compose these for the full S2 kern**:
 *  - `AgentEventStoreTest` (`:server`) — the durable store **survives a real SQLite reopen**; whole-replay; `?since=`.
 *  - `AgentWsReconnectTest` — the real `AgentWsClient` reconnect/replay/cursor-dedup + **no-re-date** (CYP-335).
 *  - `AgentReconnectingChipTest` — ① `agent.<X>.reconnecting` present ⇔ ≠LIVE (the honest not-live signal).
 *  - `TranscriptTimestampRenderTest` — ② every row renders its OWN `tsMs` gutter (no shared "now"/re-date).
 *  - **this** — the replayed backfill actually RENDERS non-blank, and (the CYP-571 finding as a tooth) only for
 *    transcript-visible event types.
 *
 * **The seed-type tooth (object-verified — CYP-571's core finding).** `RateLimitEvent`/success `ResultEvent` map
 * to ZERO transcript rows ([StreamJsonMapper] `:76`/`:154`, "never part of the transcript"). So a transcript of
 * only those is *legitimately blank* — a naive "re-fills non-blank" check seeded with `rate_limit_event` would
 * false-FAIL, or worse lock a phantom "blank bug" green. [replayedRateLimitOnly_rendersBlank_notABug] pins this.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp571S2ClientRenderProofTest {

    private val agent = "backend"

    private fun assistant(seq: Long, text: String, tsMs: Long = seq * 1_000L): StoredAgentEvent =
        StoredAgentEvent(seq, agent, "default", tsMs, AssistantEvent(message = AgentMessage(content = listOf(TextBlock(text))), sessionId = "s1"))

    private fun rateLimit(seq: Long): StoredAgentEvent =
        StoredAgentEvent(seq, agent, "default", seq * 1_000L, RateLimitEvent(sessionId = "s1", uuid = "u$seq"))

    /** A session that replays a fixed durable backfill through the REAL mapper, LIVE (as a completed reconnect). */
    private fun replaySession(vararg backfill: StoredAgentEvent) = MappingAgentSession(
        source = flowOf(*backfill),
        sink = {},
        connection = MutableStateFlow(ConnectionStatus.LIVE),
        readyNoticeText = "bereit",
    )

    /**
     * KERN — a replayed backfill of transcript-visible events (assistant turns) RE-FILLS the window with the
     * corresponding non-blank rows (not the blank screen), each carrying its own time gutter (② server `tsMs`).
     */
    @Test
    fun replayedAssistantBackfill_reFillsNonBlankRows() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentViewModel(
                        replaySession(
                            assistant(1, "reply-1"),
                            assistant(2, "reply-2"),
                            assistant(3, "reply-3"),
                            assistant(4, "reply-4"),
                        ),
                        agentId = agent,
                    )
                }
                AgentWindow(agentId = agent, viewModel = vm)
            }
        }
        // The backfill renders — the 4th (last) row's time cell lands, so the whole replayed history is present,
        // NON-BLANK. (Rows are tagged per-kind `event.<i>.assistantText`; the bare per-row cell is `event.<i>.time`.)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.eventTime(agent, 3), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // ② each of the 4 replayed rows carries its OWN time gutter (server's original tsMs — no re-date/no shared "now").
        for (i in 0 until 4) onNodeWithTag(AgentViewTags.eventTime(agent, i), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.event(agent, 0, EventKind.ASSISTANT_TEXT), useUnmergedTree = true).assertExists()
        onNodeWithText("reply-1", useUnmergedTree = true).assertExists() // genuinely non-blank content
        onNodeWithText("reply-4", useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.content(agent), useUnmergedTree = true).assertExists()
    }

    /**
     * The seed-type tooth: a backfill of ONLY `rate_limit_event`s survives in the store but renders NO transcript
     * row — a *legitimately* blank window, not a bug. Guards against reading a derived-blank as a "blank bug" (and
     * against seeding a verification run with non-rendering event types). Mutation: change the seed to [assistant]
     * → a row appears → this `assertDoesNotExist` reddens, proving it discriminates.
     */
    @Test
    fun replayedRateLimitOnly_rendersBlank_notABug() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(replaySession(rateLimit(1), rateLimit(2)), agentId = agent) }
                AgentWindow(agentId = agent, viewModel = vm)
            }
        }
        // The window frame mounts (the transcript container is there)…
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.content(agent), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        // …but the rate-limit events produce NO transcript row (StreamJsonMapper:76) — correctly blank. Non-vacuous:
        // the SAME MappingAgentSession path renders `event.0.time` for a visible event (assistant test above), so
        // its ABSENCE here is the mapper dropping rate-limit, not the collection failing to run.
        onNodeWithTag(AgentViewTags.eventTime(agent, 0), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.event(agent, 0, EventKind.ASSISTANT_TEXT), useUnmergedTree = true).assertDoesNotExist()
    }
}
