package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test

/**
 * CYP-789 — the phone-pager context chip carries the CYP-656 staleness marker (closing the CYP-65 follow-up). A
 * non-LIVE / absent feed cannot refresh the count, so the chip marks itself with a leading `~` ("last known") +
 * `stateDescription` "stale" instead of rendering in the fresh LIVE look — the safe-but-silent-default family: an
 * unknown/non-LIVE freshness must not be shown as fresh. Mirrors the canvas [Cyp656TokenStaleRenderTest].
 *
 * The pager opens on `focusedId` = the LAST window ([WindowManagerState.focusedId]), so the intended current page
 * is placed last. **DoD tooth:** non-LIVE feed → the chip TRÄGT the marker.
 * Reddening mutation: drop the `~`/stale (render fresh regardless of feed) → the non-LIVE chip shows "137k" → RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp789PagerChipStalenessRenderTest {

    private fun stateWithCurrent(currentId: String): WindowManagerState {
        val other = if (currentId == "backend") "frontend" else "backend"
        return WindowManagerState(
            listOf(
                WindowState(other, other, 0f, 0f, 200f, 150f),
                WindowState(currentId, currentId, 0f, 0f, 200f, 150f),
            ),
        )
    }

    @Test
    fun pagerChip_nonLiveFeed_carriesStaleMarker() = runComposeUiTest {
        val state = stateWithCurrent("backend")
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) { // width Compact → pager
                    WindowHost(
                        state = state,
                        contextTokensFor = { 137_214 },
                        connectionFor = { ConnectionStatus.DISCONNECTED }, // non-LIVE → cannot refresh → stale
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }
        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        // DoD: the chip is MARKED `~` (still shown, not hidden) AND the a11y state hook says "stale".
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT).assertTextEquals("~137k")
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "stale"))
    }

    @Test
    fun pagerChip_liveFeed_noMarker() = runComposeUiTest {
        val state = stateWithCurrent("backend")
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) {
                    WindowHost(
                        state = state,
                        contextTokensFor = { 137_214 },
                        connectionFor = { ConnectionStatus.LIVE }, // LIVE → fresh, no marker (proves it marks, not always)
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }
        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT).assertTextEquals("137k")
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "live"))
    }
}
