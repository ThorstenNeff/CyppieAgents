package com.tneff.cyppieagents.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * CYP-486 live-wiring — the **INERT composition-root gate** for the remote hub-connect flow. When [enabled] is
 * `false` (the hard [remoteHubEnabled] default) the flow is **not constructed at all**: [createViewModel] is
 * never invoked and [workspace] renders directly → **byte-identical to today** (0 UI affordance, 0 behaviour
 * change). This is the CYP-458/459 opt-in-off discipline on the client; a tooth pins "off ⇒ VM not constructed".
 *
 * When enabled, the hub-connect flow runs until the operator enters a workspace, then [workspace]. Flipping the
 * flag makes the wiring *present*; the user-reachable activation (a real relay server + a deploy) stays an
 * Auftraggeber GO.
 */
@Composable
fun RemoteHubConnectGate(
    enabled: Boolean,
    createViewModel: () -> HubConnectViewModel,
    workspace: @Composable () -> Unit,
) {
    if (!enabled) {
        // OFF (default): construct nothing — byte-identical to a build without the hub-connect flow.
        workspace()
        return
    }
    val viewModel = remember { createViewModel() }
    var entered by remember { mutableStateOf(false) }
    if (entered) workspace() else HubConnectFlow(viewModel, onEnterWorkspace = { entered = true })
}
