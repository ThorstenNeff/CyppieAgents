package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.HubTransport
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * M2 Seam-3 (a)+(b) — the CONNECTED remote-workspace hand-off the [RemoteHubConnectGate] passes to the workspace:
 *  - **(a) [transport]** — the tunnel-backed [HubTransport]; `AgentShell` runs **mode-blind** over it (CYP-411), so
 *    the whole workspace operates over the Noise tunnel with zero per-consumer changes. `null` on non-Desktop (Path-A).
 *  - **(b) [sessionState]** — the live [RemoteSessionState] flow (Seam-6 chrome enabler: RECONNECTING / inFlightUncertain).
 *  - **[hubName]** — the CYP-527 remote-operating context banner.
 *  - **[onEndSession]** — Seam-8 revoke → `backToHubList()` / `RemoteHubSession.close()`.
 *
 * `null` from the gate ⇒ Local / not-yet-CONNECTED ⇒ `AgentShell`'s LOCAL default (the INERT invariant: remote OFF or
 * a local connect ⇒ byte-identical to today).
 */
class RemoteWorkspaceHandoff(
    val hubName: String,
    val transport: HubTransport?,
    val sessionState: StateFlow<RemoteSessionState>,
    val onEndSession: () -> Unit,
)
