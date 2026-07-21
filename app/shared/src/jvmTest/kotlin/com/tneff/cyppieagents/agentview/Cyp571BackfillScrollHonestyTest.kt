package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.TextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-571 (V7 — UIUX2 backfill-render **HONESTY lens**, hung on Dev5's `Cyp571S2ClientRenderProofTest` harness).
 *
 * Dev5's KERN proves the replayed backfill **re-fills non-blank**; the composed teeth already cover no-re-date
 * (`TranscriptTimestampRenderTest`), the reconnecting chip (`AgentReconnectingChipTest`), wire-dedup
 * (`AgentWsReconnectTest`) and store-survival (`AgentEventStoreTest`). **This adds the one honesty facet those
 * don't: the persisted-shell LANDING.** A fresh-open / reconnect-backfill must land on the **NEWEST** turn
 * (auto-scrolled to the live end, CYP-393) — like a persisted shell showing the latest output — NOT strand the
 * user at the oldest. A backfill that renders but leaves you at the top of a long history is non-blank yet fails
 * the "feels like a persisted shell" promise. Reuses Dev5's proven `replaySession` render pattern (real
 * `MappingAgentSession` → `StreamJsonMapper` → `AgentViewModel` → `AgentWindow`).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp571BackfillScrollHonestyTest {

    private val agent = "backend"

    private fun assistant(seq: Long, text: String): StoredAgentEvent = StoredAgentEvent(
        seq, agent, "default", seq * 1_000L,
        AssistantEvent(message = AgentMessage(content = listOf(TextBlock(text))), sessionId = "s1"),
    )

    private fun replaySession(vararg backfill: StoredAgentEvent) = MappingAgentSession(
        source = flowOf(*backfill),
        sink = {},
        connection = MutableStateFlow(ConnectionStatus.LIVE),
        readyNoticeText = "bereit",
        turnErrorLabel = "Turn-Fehler",
    )

    /** V7 — a long replayed backfill LANDS ON THE NEWEST row (shell-like), scrolling PAST the oldest. */
    @Test
    fun replayedBackfill_landsOnNewest_shellLike() = runComposeUiTest {
        val backfill = (1..20).map { assistant(it.toLong(), "reply-$it") }.toTypedArray()
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(replaySession(*backfill), agentId = agent) }
                // Constrained height → 20 rows overflow → the scroll landing becomes observable (not all fit).
                Box(Modifier.height(320.dp)) { AgentWindow(agentId = agent, viewModel = vm) }
            }
        }
        // The NEWEST turn is realized — auto-scrolled to the live end (CYP-393), like a persisted shell.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("reply-20", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("reply-20", useUnmergedTree = true).assertExists()
        // …while the OLDEST is scrolled PAST (off-screen → not composed in the LazyColumn). DISCRIMINATES a
        // backfill that lands at the TOP (would leave reply-1 composed + reply-20 off-screen) = un-shell-like.
        onNodeWithText("reply-1", useUnmergedTree = true).assertDoesNotExist()
    }
}
