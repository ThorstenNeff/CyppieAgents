package com.tneff.cyppieagents.connect

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
)

/**
 * Builds the [RemoteConnectComponents] for a hub within a connecting scope. The jvm activation impl
 * (`liveRemoteConnectComponentsFactory`) composes the real Noise stack (RelayDialer over CP-rendezvous + relay
 * WS, TOFU trust, operator-auth with the live CP hubTicket); tests inject fakes. `null` in the ViewModel ⇒ INERT.
 */
fun interface RemoteConnectComponentsFactory {
    fun create(hub: HubDescriptor, scope: CoroutineScope): RemoteConnectComponents
}
