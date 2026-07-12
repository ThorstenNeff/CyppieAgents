package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlin.test.Test

/**
 * CYP-505 (activation criterion, §5/Q3) — the OOB-confirm provisional disclosure RETIRES once pinning is real.
 * `OobConfirmMount.provisional` threads into `OobFingerprintConfirmScreen`: a stub / pre-live mount (`true`)
 * still shows "vorläufig — echtes Pinnen folgt"; a LIVE mount (`false`, set by the activation feed wiring where
 * `TofuHubTrust` does the real pin @ approve) drops it — so the live screen never over-says "provisional" while
 * the pin is real (exactly the §5/Q3 fail-direction of the UIUX-QA checklist).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp505ProvisionalRetirementTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun rs(conn: RemoteConnState) = RemoteSessionState("hub-a", conn)
    private val dhPubKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun oobMount_live_provisionalFalse_retiresDisclosure() = runComposeUiTest {
        val liveMount = OobConfirmMount(dhPubKey, provisional = false, onConfirm = {}, onReject = {})
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm(), oobConfirm = liveMount) } }
        onNodeWithTag(RemoteConnectTags.TRUST_FIRST, useUnmergedTree = true).assertExists() // the OOB screen still renders
        onNodeWithTag(RemoteConnectTags.TRUST_CONFIRM, useUnmergedTree = true).assertExists() // confirm/reject still present
        // §5/Q3: LIVE ⇒ real pinning ⇒ the "provisional — real pinning follows" disclosure MUST be gone (no over-say).
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun oobMount_stub_provisionalTrue_stillDiscloses() = runComposeUiTest {
        val stubMount = OobConfirmMount(dhPubKey, provisional = true, onConfirm = {}, onReject = {})
        setContent { MaterialTheme { RemoteConnectingView(hub, rs(RemoteConnState.TRUST_CHECK), vm(), oobConfirm = stubMount) } }
        onNodeWithTag(RemoteConnectTags.TRUST_FIRST, useUnmergedTree = true).assertExists()
        // control: the disclosure CAN appear (makes the retirement tooth non-vacuous) — a pre-live mount still discloses.
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertExists()
    }
}
