package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test

/**
 * CYP-656 (busy-`*` freshness gate) — the BEHAVIOUR tooth. The busy flag is held last-known across a WS drop, so a
 * `*` could keep asserting "running a turn" for an already-stopped agent through the reconnect gap. The `*` is a
 * DISCRETE state claim — it lies, exactly like the CYP-573 dot did. Fix: render the `*` only while the feed is
 * [ConnectionStatus.LIVE]; suppress it on any non-LIVE (or absent) feed.
 *
 * Discriminating (names the wrong impl it rejects): an agent that is `busy = true` but behind a DISCONNECTED feed
 * must show NO `*`. Reddening mutation: drop the `&& connection == ConnectionStatus.LIVE` guard → the stale `*`
 * renders on the dropped feed → RED. The LIVE window proves the guard didn't just delete the marker outright.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp656BusyConnectionGateTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("live", "Live", 0f, 0f, 380f, 240f),
            WindowState("dropped", "Dropped", 400f, 0f, 380f, 240f),
        ),
    )

    @Test
    fun busyStar_shownOnLiveFeed_suppressedOnDroppedFeed() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) { // both axes ≥ Medium → canvas
                    WindowHost(
                        state = state,
                        // BOTH agents are busy=true (a turn was in flight). The only difference is feed freshness.
                        busyFor = { true },
                        connectionFor = { id ->
                            if (id == "live") ConnectionStatus.LIVE else ConnectionStatus.DISCONNECTED
                        },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // LIVE feed + busy → the `*` is an honest, confirmable claim → shown.
        onNodeWithTag(WindowTestTags.busy("live")).assertExists()
        // DROPPED feed + busy → the busy flag is stale/unconfirmable → suppressed, never a lying `*`.
        onNodeWithTag(WindowTestTags.busy("dropped")).assertDoesNotExist()
    }

    @Test
    fun busyStar_suppressedOnConnectingFeed() = runComposeUiTest {
        // CONNECTING (reconnect in flight) is also non-LIVE → suppress, in lockstep with the CYP-573 dot.
        val state = WindowManagerState(listOf(WindowState("reconnecting", "Reconnecting", 0f, 0f, 380f, 240f)))
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) {
                    WindowHost(
                        state = state,
                        busyFor = { true },
                        connectionFor = { ConnectionStatus.CONNECTING },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }
        onNodeWithTag(WindowTestTags.busy("reconnecting")).assertDoesNotExist()
    }
}
