package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-486 live-wiring — the INERT composition-root tooth: **flag OFF ⇒ the hub-connect VM is NEVER constructed**
 * (byte-identical to today), the workspace renders directly; flag ON ⇒ the VM is constructed once and the
 * hub-connect flow shows instead of the workspace. Pins the CYP-458/459 opt-in-off discipline on the client.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteHubConnectGateTest {

    private fun hubVm() = HubConnectViewModel(
        controlPlane = StubControlPlaneClient(),
        credentials = StubHubCredentialRepository(),
        connectFeed = StubLocalConnectFeed(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun disabled_neverConstructsViewModel_rendersWorkspace() = runComposeUiTest {
        var built = 0
        setContent {
            MaterialTheme {
                RemoteHubConnectGate(enabled = false, createViewModel = { built++; hubVm() }) {
                    Text("WS", modifier = Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithTag("workspace", useUnmergedTree = true).assertExists()
        assertEquals(0, built, "flag OFF ⇒ the hub-connect VM is NEVER constructed (byte-identical to today)")
    }

    @Test
    fun enabled_constructsViewModelOnce_showsFlowNotWorkspace() = runComposeUiTest {
        var built = 0
        setContent {
            MaterialTheme {
                RemoteHubConnectGate(enabled = true, createViewModel = { built++; hubVm() }) {
                    Text("WS", modifier = Modifier.testTag("workspace"))
                }
            }
        }
        assertEquals(1, built, "flag ON ⇒ the hub-connect VM is constructed once")
        onNodeWithTag("workspace", useUnmergedTree = true).assertDoesNotExist() // the hub-connect flow shows instead
    }
}
