package com.tneff.cyppieagents.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState

/**
 * CYP-486 live-wiring — the **INERT composition-root gate** for the remote hub-connect flow. When [enabled] is
 * `false` (the hard [remoteHubEnabled] default) the flow is **not constructed at all**: [createViewModel] is
 * never invoked and [workspace] renders directly → **byte-identical to today** (0 UI affordance, 0 behaviour
 * change). This is the CYP-458/459 opt-in-off discipline on the client; a tooth pins "off ⇒ VM not constructed".
 *
 * When enabled, the hub-connect flow runs until the operator enters a workspace, then [workspace]. Flipping the
 * flag makes the wiring *present*; the user-reachable activation (a real relay server + a deploy) stays an
 * Auftraggeber GO.
 *
 * CYP-527: [workspace] receives the **remote-operating context** — the connected remote hub's display name, or
 * `null` when local / not yet CONNECTED / torn down. It is derived from the VM's live state via
 * [remoteContextHubName] (bound to `RemoteSessionState.conn == CONNECTED`, NOT [entered], which also fires on a
 * local connect). Flag OFF ⇒ always `null` (there is no remote path), preserving the byte-identical default.
 */
@Composable
fun RemoteHubConnectGate(
    enabled: Boolean,
    createViewModel: () -> HubConnectViewModel,
    workspace: @Composable (handoff: RemoteWorkspaceHandoff?) -> Unit,
) {
    if (!enabled) {
        // OFF (default): construct nothing — byte-identical to a build without the hub-connect flow (no remote hand-off).
        workspace(null)
        return
    }
    val viewModel = remember { createViewModel() }
    var entered by remember { mutableStateOf(false) }
    if (entered) {
        // M2 Seam-3 (a)+(b): recompute the hand-off when the live session state changes. Non-null only on a REMOTE
        // CONNECTED (transport + live RemoteSessionState + endSession); `null` for Local / pre-CONNECTED ⇒ AgentShell's
        // LOCAL default (the INERT invariant). CYP-527's banner name rides inside the hand-off ([RemoteWorkspaceHandoff]).
        val state by viewModel.state.collectAsState()
        val handoff = remember(state) { viewModel.remoteHandoff() }
        workspace(handoff)
    } else {
        HubConnectFlow(viewModel, onEnterWorkspace = { entered = true })
    }
}

/**
 * CYP-527 — the workspace remote-operating context signal: the **connected remote hub's display name**, or `null`.
 * Non-null **iff** the current state is a REMOTE connect whose session is `CONNECTED` — so the banner is absent in
 * Local mode (a [HubConnectUiState.Connecting], never `RemoteConnecting`), absent before CONNECTED (dialing /
 * handshake / trust-check / authenticating), and cleared on teardown (`LOST`) or `backToHubList` (`HubList` /
 * `LoadingHubs`). Pure over the state so it is unit-testable against a mocked CONNECTED state (live-verify follows
 * once CONNECTED is reachable, CYP-525).
 */
internal fun remoteContextHubName(state: HubConnectUiState): String? =
    (state as? HubConnectUiState.RemoteConnecting)
        ?.takeIf { it.remote.conn == RemoteConnState.CONNECTED }
        ?.hub?.name
