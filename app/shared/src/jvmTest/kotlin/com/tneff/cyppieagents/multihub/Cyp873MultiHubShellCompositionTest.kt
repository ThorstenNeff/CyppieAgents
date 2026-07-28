package com.tneff.cyppieagents.multihub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-873 (Compose Multi-Hub M4) — the **composition seam** tooth: the [MultiHubShell] host actually WIRES M1→M2→M3
 * end-to-end (mirror of the web-ts `multiHubComposition` selection + ONE-ACTIVE teeth). A recording connector proves:
 *
 *  • **selection → switchTo** (M1 list → M2 switcher click → M3 connection): clicking a switcher entry drives the
 *    host's onSwitch → active pointer → `switchTo` on the connection controller.
 *  • **ONE-ACTIVE teardown**: switching TEARS DOWN the prior hub's connection BEFORE opening the new one
 *    (`open:a → close:a → open:b`) — no background connection survives to an inactive hub.
 *  • **unmount teardown**: leaving the composition closes the active connection (the DisposableEffect), no leak.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp873MultiHubShellCompositionTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    /** Records open/close per hubId so the host's ONE-ACTIVE lifecycle is observable end-to-end. */
    private class RecordingConnector : HubConnector<RemoteConnState?> {
        val events = mutableListOf<String>()
        override fun open(hubId: String): HubConnectionHandle<RemoteConnState?> {
            events += "open:$hubId"
            return object : HubConnectionHandle<RemoteConnState?> {
                override val machine: RemoteConnState? = null
                override fun close() {
                    events += "close:$hubId"
                }
            }
        }
    }

    @Test
    fun clickingEntry_drivesSwitchTo_andTearsDownPriorConnection_oneActive() = runComposeUiTest {
        val rec = RecordingConnector()
        setContent {
            MultiHubShell(
                hubListState = loadHubList(listOf(hub("a"), hub("b"))),
                onRetryHubs = {},
                initialActiveHubId = "a", // opens A on mount
                connector = rec,
            )
        }
        waitForIdle()
        assertEquals(listOf("open:a"), rec.events, "mount opens the initial active hub")

        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).performClick()
        waitForIdle()

        // Selection reached the connection controller AND ONE-ACTIVE held: A torn down BEFORE B opened.
        assertEquals(listOf("open:a", "close:a", "open:b"), rec.events)
    }

    @Test
    fun unmount_tearsDownActiveConnection_noLeak() = runComposeUiTest {
        val rec = RecordingConnector()
        var mounted by mutableStateOf(true)
        setContent {
            if (mounted) {
                MultiHubShell(
                    hubListState = loadHubList(listOf(hub("a"))),
                    onRetryHubs = {},
                    initialActiveHubId = "a",
                    connector = rec,
                )
            }
        }
        waitForIdle()
        assertEquals(listOf("open:a"), rec.events)

        mounted = false // leave the composition
        waitForIdle()
        assertTrue(rec.events.contains("close:a"), "unmount must tear down the active connection (DisposableEffect close)")
    }
}
