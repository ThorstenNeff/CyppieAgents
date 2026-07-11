package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.NotYetAvailableException
import com.tneff.cyppieagents.net.hub.RemoteHubTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * CYP-416 (S-M) — the hubConnect §B3 mode-chooser gate. Proves Remote is **honestly disabled** (visible but
 * non-interactive, so it can never be clicked into a fake half-connected state), Local is the actionable option,
 * and the transport **backstop fails loud**. Together these are the AC's non-vacuity check: making Remote
 * interactive (`enabled = true`) OR removing the [RemoteHubTransport] fail-loud reddens this.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp416HubConnectModeChooserTest {

    @Test
    fun remoteOption_isHonestlyDisabled_soNoFakeConnectedStateCanArise() = runComposeUiTest {
        setContent { MaterialTheme { HubConnectModeChooser(onConnectLocal = {}) } }

        // Local: the only actionable option in Phase 1 (loopback-only, R5).
        onNodeWithTag(HubConnectTags.MODE_LOCAL, useUnmergedTree = true).assertIsEnabled()

        // Remote (H2): visibly present but DISABLED — not clickable, so no fake click / half-connected state.
        onNodeWithTag(HubConnectTags.MODE_REMOTE, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.MODE_REMOTE, useUnmergedTree = true).assertIsNotEnabled()

        // The honest "kommt bald" hint is rendered (the GATED explanation); Local's connect stays actionable.
        onNodeWithTag(HubConnectTags.MODE_REMOTE_SOON, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.MODE_CONNECT, useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun remoteTransportPath_failsLoud_neverAUsableTransport() {
        // The backstop behind the UI gate: if any code path reaches Remote, it is fail-loud (NotYetAvailable),
        // never a transport that could present a connected state. Removing the fail-loud reddens this.
        assertFailsWith<NotYetAvailableException> { RemoteHubTransport().wsBaseUrl }
    }
}
