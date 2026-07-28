package com.tneff.cyppieagents.multihub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.RemoteConnectTags
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-866 — the connect-progression chrome renders each step under its CYP-827 tag, with the honesty rules:
 * trust-check carries the always-visible PROVISIONAL disclosure; CONNECTED shows the forward action (only when a
 * handler is wired) — non-connected/no-handler steps never leak an enter-workspace affordance.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp866ProgressionChromeRenderTest {

    @Test
    fun dialing_showsStep_noProvisional_noForwardAction() = runComposeUiTest {
        setContent { ConnectProgressionChrome(ProgressionState.DIALING) }
        onNodeWithTag(RemoteConnectTags.RELAY_DIALING).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun trustCheck_carriesTheProvisionalDisclosure() = runComposeUiTest {
        setContent { ConnectProgressionChrome(ProgressionState.TRUST_CHECK) }
        onNodeWithTag(RemoteConnectTags.TRUST_CHECK).assertExists()
        // PROVISIONAL — never reads as "verified"; always-visible in-flight.
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun reconnecting_rendersUnderRelayDropTag() = runComposeUiTest {
        setContent { ConnectProgressionChrome(ProgressionState.RECONNECTING) }
        onNodeWithTag(RemoteConnectTags.RELAY_DROP).assertExists()
    }

    @Test
    fun connected_withHandler_showsForwardAction_andInvokes() = runComposeUiTest {
        var entered = false
        setContent { ConnectProgressionChrome(ProgressionState.CONNECTED, onEnterWorkspace = { entered = true }) }
        onNodeWithTag(RemoteConnectTags.CONNECTED).assertExists()
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE, useUnmergedTree = true).performClick()
        assertTrue(entered, "the CONNECTED forward action invokes onEnterWorkspace")
    }

    @Test
    fun connected_withoutHandler_noForwardAction() = runComposeUiTest {
        // A CONNECTED chrome with no handler must NOT render a dead enter-workspace affordance.
        setContent { ConnectProgressionChrome(ProgressionState.CONNECTED) }
        onNodeWithTag(RemoteConnectTags.CONNECTED).assertExists()
        onNodeWithTag(RemoteConnectTags.TO_WORKSPACE, useUnmergedTree = true).assertDoesNotExist()
    }
}
