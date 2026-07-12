package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.trust.PendingOobConfirmations
import com.tneff.cyppieagents.net.hub.trust.PinnedHubStore
import com.tneff.cyppieagents.net.hub.trust.RegistryPresentedHubKeySource
import com.tneff.cyppieagents.net.hub.trust.TofuHubTrust

/**
 * CYP-494/510 composition-root ①② — a per-session TOFU [trust] and OOB [oobConfirm] coordinator built from ONE
 * shared presented-key source + ONE shared confirmer, so the two consumers can never diverge.
 */
class SharedHubTrustComponents(
    val trust: HubTrust,
    val oobConfirm: OobConfirmCoordinator,
)

/**
 * Build the [SharedHubTrustComponents] for [hub] over [store] — **①② at the object** (Doc 19 §2.1, the condition
 * the Reviewer re-verifies at the gate):
 *
 *  - **① instance-sameness:** exactly ONE [RegistryPresentedHubKeySource] and ONE [PendingOobConfirmations] are
 *    constructed here and injected into BOTH [TofuHubTrust] and [LiveOobConfirmCoordinator] (never re-derived per
 *    consumer). The confirmer sharing is **behaviourally load-bearing**: it is stateful, so `approve`/`reject`
 *    must wake the trust's OWN FirstUse waiter — a second `PendingOobConfirmations` would leave the trust hung.
 *    The presented-source is shared too (per ①); its instance-sameness is belt-and-suspenders because ② makes two
 *    `of(hub)` reads deterministically equal.
 *  - **② `of(hub)`, NOT `fromControlPlane`:** the presented source is bound to THIS hub descriptor (captured), so
 *    `TofuHubTrust.resolve` (fingerprint + pin) and `presentedStatic` (the displayed key) are the SAME
 *    deterministic read — no re-query, no registry-change TOCTOU that could make display ≠ pinned.
 *
 * Together: the fingerprint the operator confirms IS the key that gets pinned (display == pinned), and the
 * confirm/reject drive the real pin/teardown. The relay dialer / cpJwt seams stay gated until S-J + activation.
 */
fun buildSharedHubTrustComponents(hub: HubDescriptor, store: PinnedHubStore): SharedHubTrustComponents {
    val presented = RegistryPresentedHubKeySource.of(hub) // ONE, of(hub) — deterministic-per-session (② no TOCTOU)
    val pending = PendingOobConfirmations()               // ONE, stateful (① the load-bearing shared instance)
    return SharedHubTrustComponents(
        trust = TofuHubTrust(presentedKeys = presented, store = store, confirmer = pending),
        oobConfirm = LiveOobConfirmCoordinator(pending = pending, presentedKeys = presented),
    )
}
