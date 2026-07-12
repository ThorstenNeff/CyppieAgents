package com.tneff.cyppieagents.controlplane

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-507 (Epic CYP-427 Phase-2, activation) — the **LIVE** CP-side rendezvous registry/resolver, the drop-in for
 * [InertRelayRendezvous] at the Phase-2-Remote-GO. It assigns each hub a **per-registration CP-secret epoch** (128-bit
 * from [SecureRandom]) and derives the **opaque** rendezvous id `base64url(SHA-256(hubId ‖ epoch))`
 * ([RelayRendezvous.rendezvousId]).
 *
 * Security posture (gate criterion "opake Id am Objekt · epoch CP-secret", teeth in `Cyp507LiveRelayRendezvousTest`):
 *  - **epoch is a CP-secret** — a 128-bit random value that **never leaves the CP**: neither [register] nor [resolve]
 *    returns it; the relay and the requester see ONLY the derived id. So the relay cannot invert the id to a `hubId`
 *    (SHA-256 preimage over a 128-bit secret).
 *  - **opaque id at the object** — the returned [RendezvousBinding.rendezvousId] never contains the `hubId`.
 *  - **per-registration rotation** — each [register] mints a FRESH epoch (overwriting the prior), so a re-registration
 *    yields a different id (unlinkable across registrations, CYP-501 §2).
 *  - **fail-closed / INERT** — with no [relayUrl] (the `CYPPIE_REMOTE_RELAY_URL` gate unset) both calls yield `null`;
 *    [resolve] of a hub that never registered yields `null`.
 *
 * Zero payload knowledge (the CP is the sole `hubId`↔rendezvous resolver; it stores only `hubId → epoch`).
 */
class LiveRelayRendezvous(
    private val relayUrl: String?,
    private val epochBytes: Int = 16, // 128-bit CP-secret epoch
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) : RelayRendezvous {

    /** `hubId → current per-registration epoch` (the CP-secret; never exposed on the wire). */
    private val epochs = ConcurrentHashMap<String, ByteArray>()

    override fun register(hubId: String): RendezvousBinding? {
        val url = relayUrl ?: return null // INERT: no live relay configured
        val epoch = randomBytes(epochBytes) // fresh per-registration epoch → rotates the id (unlinkable across regs)
        epochs[hubId] = epoch
        return RendezvousBinding(RelayRendezvous.rendezvousId(hubId, epoch), url)
    }

    override fun resolve(hubId: String): RendezvousBinding? {
        val url = relayUrl ?: return null // INERT
        val epoch = epochs[hubId] ?: return null // never registered → no rendezvous
        return RendezvousBinding(RelayRendezvous.rendezvousId(hubId, epoch), url)
    }

    override fun isLive(): Boolean = relayUrl != null
}
