package com.tneff.cyppieagents.net.hub.trust

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.HubDescriptorValidity
import com.tneff.cyppieagents.model.HubTrustState
import kotlin.test.Test

/**
 * CYP-808 — the `HubTrustBadge` render contract (jvmTest, hermetic). Two-signal honesty + fail-closed + the present-iff
 * `hubTrust.<hubId>.<state>` anchor; copy asserted in EN (jvmTest locale).
 */
@OptIn(ExperimentalTestApi::class)
class HubTrustBadgeRenderTest {

    @Test
    fun trusted_rendersTrustedPill_noUpstreamMarker() = runComposeUiTest {
        setContent { MaterialTheme { HubTrustBadge(hubId = "h1", state = HubTrustState.TRUSTED) } }
        onNodeWithTag(HubTrustTags.state("h1", "trusted"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("trusted", useUnmergedTree = true).assertExists()
        onNodeWithText("●", useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.upstreamError("h1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun nullTrust_isFailClosedUnknown_neverTrusted() = runComposeUiTest {
        setContent { MaterialTheme { HubTrustBadge(hubId = "h1", state = null) } }
        onNodeWithTag(HubTrustTags.state("h1", "unknown"), useUnmergedTree = true).assertIsDisplayed()
        // fail-closed: an absent signal is NEVER the trusted pill.
        onNodeWithTag(HubTrustTags.state("h1", "trusted"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun malformed_collapsesPillToUnknown_andRaisesSeparateUpstreamMarker() = runComposeUiTest {
        // Even an explicit TRUSTED collapses to UNKNOWN on a malformed descriptor (trust couldn't be evaluated) …
        setContent {
            MaterialTheme { HubTrustBadge(hubId = "h1", state = HubTrustState.TRUSTED, validity = HubDescriptorValidity.MALFORMED) }
        }
        onNodeWithTag(HubTrustTags.state("h1", "unknown"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(HubTrustTags.state("h1", "trusted"), useUnmergedTree = true).assertDoesNotExist()
        // … and the SEPARATE ⚠ upstream marker (own namespace) is mandatory + present.
        onNodeWithTag(HubTrustTags.upstreamError("h1"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("⚠", useUnmergedTree = true).assertExists()
    }

    @Test
    fun rejected_rendersRejectedPill() = runComposeUiTest {
        setContent { MaterialTheme { HubTrustBadge(hubId = "h1", state = HubTrustState.REJECTED) } }
        onNodeWithTag(HubTrustTags.state("h1", "rejected"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("rejected", useUnmergedTree = true).assertExists()
    }

    @Test
    fun twoTrustedHubs_carryDistinctPerHubAnchors_multiHub() = runComposeUiTest {
        // CYP-747 tooth 9 (multi-hub, CYP-832): two TRUSTED hubs get DISTINCT present-iff anchors keyed by hubId.
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    HubTrustBadge(hubId = "hubA", state = HubTrustState.TRUSTED)
                    HubTrustBadge(hubId = "hubB", state = HubTrustState.TRUSTED)
                }
            }
        }
        onNodeWithTag(HubTrustTags.state("hubA", "trusted"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(HubTrustTags.state("hubB", "trusted"), useUnmergedTree = true).assertIsDisplayed()
    }
}
