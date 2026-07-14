package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import kotlinx.coroutines.CoroutineScope

/**
 * CYP-513 — the per-connect remote pieces produced together so they SHARE the ①② instances: the live
 * [RemoteHubSession] (its `TofuHubTrust` and this [oobConfirm] coordinator are built from ONE `of(hub)` +
 * ONE `PendingOobConfirmations` — see [buildSharedHubTrustComponents]). The ViewModel drives the session and
 * surfaces the coordinator's OOB state, so what the operator confirms is what gets pinned (display == pinned),
 * end-to-end.
 */
class RemoteConnectComponents(
    val session: RemoteHubSession,
    val oobConfirm: OobConfirmCoordinator,
    /** CYP-525 §2 — the live first-enroll confirmer (the SAME instance injected into the session's
     *  `ClientOperatorAuth`), so the reveal the VM surfaces IS what gates the `SavedAck` the session sends. */
    val enrollConfirm: EnrollConfirmCoordinator = LiveEnrollConfirmCoordinator(),
    /**
     * CYP-537 (M2 Option A, WS2) — the N-tunnel pool: the transport's `TunnelSource` on CONNECTED, so the mode-blind
     * workspace's concurrent WS each ride a **distinct** authenticated tunnel (F-M2-1 fix). `null` ⇒ the transport
     * falls back to the single-flight session tunnel (the 1-connection thru-cut). Closed with the session on a Q5
     * switch/leave (nothing carried across). Shares the session's trust/device seams (pool tunnels ride the pin).
     */
    val tunnelPool: PooledTunnelSource? = null,
)

/**
 * Builds the [RemoteConnectComponents] for a hub within a connecting scope. The jvm activation impl
 * (`liveRemoteConnectComponentsFactory`) composes the real Noise stack (RelayDialer over CP-rendezvous + relay
 * WS, TOFU trust, operator-auth with the live CP hubTicket); tests inject fakes. `null` in the ViewModel ⇒ INERT.
 */
fun interface RemoteConnectComponentsFactory {
    fun create(hub: HubDescriptor, scope: CoroutineScope): RemoteConnectComponents
}
