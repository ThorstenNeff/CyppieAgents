package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test

/**
 * CYP-747 §5-C2 — the `IssuerNotTrusted` trust-stop renders as a **fail-closed HARD BLOCK**: the WARN alarm + the
 * OOB text hint are shown, but there is **NO CONNECTED** and **NO retry/grant** path (an owned hub with an
 * untrusted CP-JWT issuer grants no operator authority; recovery is OOB-only). Fixture-driven — a
 * [RemoteSessionState] carrying `IssuerNotTrusted` at `conn = LOST` (the surface the S1 wiring will later emit).
 *
 * Mutation proofs: render a retry button (or let CONNECTED slip through) → the fail-closed assertions RED; drop
 * the OOB hint or the error tag → the presence assertions RED; drop `liveRegion = Assertive` → the a11y RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp747IssuerNotTrustedRenderTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun rs(conn: RemoteConnState, failure: RemoteFailure? = null) = RemoteSessionState("hub-a", conn, failure)

    @Test
    fun issuerNotTrusted_isFailClosedHardBlock_warnAlarm_oobHint_noConnected_noRetry() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RemoteConnectingView(hub, rs(RemoteConnState.LOST, RemoteFailure.IssuerNotTrusted("acme-cp-issuer")), vm())
            }
        }

        // The WARN alarm node + the OOB text hint are shown...
        onNodeWithTag(RemoteConnectTags.error("issuerNotTrusted"), useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.ISSUER_OOB, useUnmergedTree = true).assertExists()
        // ...it is FAIL-CLOSED: never CONNECTED, and NO retry/grant path (recovery is OOB-only)...
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RemoteConnectTags.RETRY, useUnmergedTree = true).assertDoesNotExist()
        // ...and the a11y is Assertive (an unsolicited terminal trust stop the operator must hear).
        onNodeWithTag(RemoteConnectTags.error("issuerNotTrusted"), useUnmergedTree = true).assert(
            SemanticsMatcher("a11y liveRegion is Assertive") { node ->
                node.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Assertive
            },
        )
    }
}
