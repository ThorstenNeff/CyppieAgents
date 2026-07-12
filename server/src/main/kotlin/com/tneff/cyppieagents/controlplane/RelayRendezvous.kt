package com.tneff.cyppieagents.controlplane

import java.security.MessageDigest
import java.util.Base64

/**
 * CYP-501 (activation, INERT) — the CP-side **rendezvous registry/resolver** for the untrusted relay (RR4 opaque, RR5
 * outbound-only). The relay never learns `hubId`: both ends connect under an **opaque** rendezvous id derived from the
 * `hubId` + a CP-issued per-registration epoch; the CP is the sole `hubId`↔rendezvous resolver. The hub [register]s
 * (outbound, `WebSocketRelayDialer`); the client [resolve]s by `hubId` (CYP-494 `RelayDialer`). **INERT** — no live
 * relay until the Phase-2-Remote-GO. The relay SERVER (a process) is NOT built here — its operation is Auftraggeber-GO.
 */
interface RelayRendezvous {
    /** Hub side (outbound): publish this hub's current rendezvous; null when no live relay is configured. */
    fun register(hubId: String): RendezvousBinding?

    /** Client side (by `hubId`): resolve this hub's current rendezvous; null when there is none. */
    fun resolve(hubId: String): RendezvousBinding?

    companion object {
        /** RR4 opaque id: `base64url(SHA-256(hubId ‖ epoch))` — the relay sees only this, never `hubId`. The
         *  [rendezvousEpoch] is **per-registration**: a new registration rotates the id (unlinkable across
         *  REGISTRATIONS). Within one registration the id is stable → the relay can correlate that hub's sessions =
         *  an accepted RR4 metadata residual (relay already sees pairing/timing/sizes), not per-session unlinkability. */
        fun rendezvousId(hubId: String, rendezvousEpoch: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(hubId.encodeToByteArray())
            md.update(rendezvousEpoch)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
        }
    }
}

/** The opaque rendezvous binding both ends receive from the CP (the relay never sees [RelayRendezvous]'s `hubId`). */
data class RendezvousBinding(val rendezvousId: String, val relayUrl: String)

/** INERT default: no live rendezvous (register/resolve yield null). The current server is unchanged. */
object InertRelayRendezvous : RelayRendezvous {
    override fun register(hubId: String): RendezvousBinding? = null
    override fun resolve(hubId: String): RendezvousBinding? = null
}
