package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import com.tneff.cyppieagents.net.hub.trust.PendingOobConfirmations
import com.tneff.cyppieagents.net.hub.trust.PresentedHubKeySource
import kotlinx.coroutines.flow.StateFlow

/**
 * CYP-510 — the live OOB-confirm source the hubConnect flow observes to mount the FirstUse confirm screen
 * (CYP-482 §3) with REAL data instead of the INERT null/stub. It surfaces:
 *  - [state] — the trust layer's OOB confirmation state (Awaiting/Idle/Rejected), from `PendingOobConfirmations`;
 *  - [approve] / [reject] — the operator's two mandatory exits (approve → `TofuHubTrust` pins the key; reject →
 *    fail-closed teardown via `TrustConfirmationRejectedException`);
 *  - [presentedStatic] — the presented dhPubKey **bytes** for the fingerprint, from the **same**
 *    [PresentedHubKeySource] `TofuHubTrust` resolves and pins from (CYP-495). That sameness is the **CI-1 binding**:
 *    the fingerprint the operator confirms IS the key that gets pinned — never a display key decoupled from the
 *    pinned one (bind the action to exactly what was verified).
 *
 * `null` in the ViewModel ⇒ INERT (no live OOB; the provisional spinner stays), byte-identical to today.
 */
interface OobConfirmCoordinator {
    val state: StateFlow<OobConfirmState>
    fun approve()
    fun reject()
    suspend fun presentedStatic(hubId: String): ByteArray?
}

/**
 * The production [OobConfirmCoordinator]: [pending] (the trust layer's confirmer) + [presentedKeys] (the SAME
 * source `TofuHubTrust` resolves/pins from). The activation wiring MUST pass the same instances into both the
 * session's `TofuHubTrust` and here, so what the operator sees is what gets pinned (CI-1).
 */
class LiveOobConfirmCoordinator(
    private val pending: PendingOobConfirmations,
    private val presentedKeys: PresentedHubKeySource,
) : OobConfirmCoordinator {
    override val state: StateFlow<OobConfirmState> = pending.state
    override fun approve() = pending.approve()
    override fun reject() = pending.reject()
    override suspend fun presentedStatic(hubId: String): ByteArray? = presentedKeys.presentedStatic(hubId)
}

/**
 * Derive the live [OobConfirmMount] for [hub] from the current OOB [state]. Only an [OobConfirmState.Awaiting]
 * for THIS hub (Q5 exactly-one-active) yields a mount; the presented bytes come from [coordinator] and fail
 * **closed** to `null` if the registry has no usable key (never a fabricated fingerprint). `provisional = false`:
 * a live mount means real pinning follows, so the CYP-505 provisional disclosure retires (§5/Q3). `onConfirm`/
 * `onReject` are wired straight to the coordinator's approve/reject — the operator's confirm drives the real pin.
 */
internal suspend fun buildLiveOobMount(
    coordinator: OobConfirmCoordinator,
    hub: HubDescriptor,
    state: OobConfirmState,
): OobConfirmMount? {
    val awaiting = state as? OobConfirmState.Awaiting ?: return null
    if (awaiting.hubId != hub.hubId) return null
    val dhPubKey = coordinator.presentedStatic(awaiting.hubId) ?: return null
    return OobConfirmMount(
        hubDhPubKey = dhPubKey,
        provisional = false,
        onConfirm = { coordinator.approve() },
        onReject = { coordinator.reject() },
    )
}
