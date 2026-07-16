package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test

/**
 * CYP-656 (token freshness) — the "marked, NOT hidden" render tooth. This pins the product/honesty call: on a
 * dropped feed the frozen count is MARKED with a leading `~` ("last known") but STILL SHOWN — showing "not sure
 * this is current" beats blanking it. Per the UIUX spec (titlebar-token-staleness-spec.md) the marker is a
 * NON-colour glyph (the number keeps full `barContent` — no AA-safe dim exists on the per-agent title bar), so this
 * asserts the `~` prefix + node presence, not a painted colour.
 *
 * Reddening mutation: switch the render to HIDE on stale (e.g. `takeIf { !tokenStale }`) → the token node
 * disappears on the dropped feed → RED. So this tooth guards against a future "hide" regression (the one that matters).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp656TokenStaleRenderTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("live", "Live", 0f, 0f, 380f, 240f),
            WindowState("dropped", "Dropped", 400f, 0f, 380f, 240f),
        ),
    )

    @Test
    fun tokenCount_markedButStillShown_onDroppedFeed_notHidden() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) { // both axes ≥ Medium → canvas
                    WindowHost(
                        state = state,
                        contextTokensFor = { 137_214 }, // BOTH have a real value; only feed freshness differs
                        connectionFor = { id ->
                            if (id == "live") ConnectionStatus.LIVE else ConnectionStatus.DISCONNECTED
                        },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // Dropped feed → the count is MARKED (leading `~` = "last known") but the node STILL EXISTS and STILL SHOWS
        // the value (not hidden, not 0). The `~` is the "marked, not hidden" render; hide-on-stale would fail assertExists.
        onNodeWithTag(WindowTestTags.contextTokens("dropped")).assertExists()
        onNodeWithTag(WindowTestTags.contextTokens("dropped")).assertTextEquals("~137k")
        // LIVE control → shown normally, NO marker (proves the gate marks, it did not blank the node nor always-mark).
        onNodeWithTag(WindowTestTags.contextTokens("live")).assertExists()
        onNodeWithTag(WindowTestTags.contextTokens("live")).assertTextEquals("137k")
    }
}
