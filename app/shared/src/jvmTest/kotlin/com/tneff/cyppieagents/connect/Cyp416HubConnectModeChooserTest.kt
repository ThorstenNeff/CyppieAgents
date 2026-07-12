package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.NotYetAvailableException
import com.tneff.cyppieagents.net.hub.RemoteHubTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-416 → **CYP-471**: the hubConnect §B3 mode-chooser. Remote was "kommt bald" (disabled, CYP-416); CYP-471 makes
 * it **live** — both modes selectable, and the Connect button routes to the SELECTED mode. The remote path is
 * stub-driven (`RemoteConnectFeed`) until RR5; the real Noise transport engine (`RemoteHubTransport`) stays
 * fail-loud until then. Non-vacuity: routing Connect to the wrong mode, or making the real transport usable, reddens this.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp416HubConnectModeChooserTest {

    @Test
    fun remoteOption_isLive_selectable_andConnectRoutesToRemote() = runComposeUiTest {
        var localCalled = false
        var remoteCalled = false
        setContent {
            MaterialTheme {
                HubConnectModeChooser(onConnectLocal = { localCalled = true }, onConnectRemote = { remoteCalled = true })
            }
        }
        onNodeWithTag(HubConnectTags.MODE_LOCAL, useUnmergedTree = true).assertIsEnabled()
        // CYP-471: Remote is now selectable/enabled (no longer "kommt bald").
        onNodeWithTag(HubConnectTags.MODE_REMOTE, useUnmergedTree = true).assertIsEnabled()

        // Select Remote → the Connect button routes to the remote connect, never falling through to local.
        onNodeWithTag(HubConnectTags.MODE_REMOTE, useUnmergedTree = true).performClick()
        onNodeWithTag(HubConnectTags.MODE_CONNECT, useUnmergedTree = true).performClick()
        assertTrue(remoteCalled, "Connect after selecting Remote routes to onConnectRemote")
        assertFalse(localCalled, "it does NOT fall through to the local connect")
    }

    @Test
    fun realNoiseTransportEngine_staysFailLoud_untilRR5() {
        // The Remote MODE is stub-driven (RemoteConnectFeed); the real HubTransport-over-Noise engine is not built
        // yet (RR5). It stays fail-loud — never a transport that could present a fake connected state.
        assertFailsWith<NotYetAvailableException> { RemoteHubTransport().wsBaseUrl }
    }
}
