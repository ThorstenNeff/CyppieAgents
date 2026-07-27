package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * CYP-819 (A1) — the ONE covering, session-wide **access-revoked** banner on [RemoteOperatingChrome]:
 *  - a terminal 1008 revoke renders the ERROR-red `✕` banner ([WorkspaceTags.ACCESS_REVOKED], copy reuses
 *    `acl_access_revoked`), and
 *  - it **SUPERSEDES** the transient session banners — even while the session flow says CONNECTED, the
 *    [WorkspaceTags.REMOTE_CONTEXT] banner is absent (a revoke outranks "connected").
 *
 * Non-vacuity (mutation): dropping the `if (accessRevoked) { … return }` supersede branch → the covering banner
 * vanishes AND the CONNECTED context banner re-appears → both assertions RED. Copy asserted in EN (jvmTest locale).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp819CoveringBannerRenderTest {

    private fun connected() = MutableStateFlow(RemoteSessionState(hubId = "h1", conn = RemoteConnState.CONNECTED))

    @Test
    fun revoke_showsCoveringErrorBanner_andSupersedesConnected() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(
                    hubName = "My Hub",
                    sessionState = connected(), // the session flow still says CONNECTED …
                    onEndSession = {},
                    accessRevoked = true,        // … but a revoke supersedes it.
                )
            }
        }
        // The covering ERROR banner is present + its ✕ glyph (a separate node, WCAG 1.4.1) + the shared copy.
        onNodeWithTag(WorkspaceTags.ACCESS_REVOKED, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("✕", useUnmergedTree = true).assertExists()
        onNodeWithText("Access revoked", substring = true, useUnmergedTree = true).assertExists()
        // Supersede: the CONNECTED context banner does NOT render underneath (one banner, not stacked).
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun noRevoke_showsConnected_notTheCoveringBanner() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteOperatingChrome(hubName = "My Hub", sessionState = connected(), onEndSession = {}, accessRevoked = false)
            }
        }
        // Control: without a revoke the covering banner is absent and the normal CONNECTED banner shows.
        onNodeWithTag(WorkspaceTags.ACCESS_REVOKED, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
    }
}
