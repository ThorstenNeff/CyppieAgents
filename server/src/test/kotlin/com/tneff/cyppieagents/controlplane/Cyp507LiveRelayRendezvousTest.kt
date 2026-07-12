package com.tneff.cyppieagents.controlplane

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * CYP-507 — the LIVE CP-rendezvous seam ([LiveRelayRendezvous]). Gate criterion "opake Id am Objekt · epoch
 * CP-secret".
 *
 * ★ Mutation map (revert the guard → the named tooth reds):
 *  - reuse the epoch across registrations (`getOrPut`) → [register_perRegistration_rotatesTheId] reds.
 *  - return a non-opaque id (the hubId) → [register_returnsOpaqueId] reds.
 *  - drop the `relayUrl == null` INERT guard → [inert_whenNoRelayUrl_bothNull] reds.
 *  - derive resolve from a fresh epoch → [registerThenResolve_roundTrips] reds.
 */
class Cyp507LiveRelayRendezvousTest {

    private val relayUrl = "wss://relay.oakhost.test/relay"

    @Test fun register_returnsOpaqueId_neverContainingHubId() {
        val hubId = "hub_abcdef0123456789"
        val binding = LiveRelayRendezvous(relayUrl).register(hubId)!!
        assertEquals(relayUrl, binding.relayUrl)
        assertNotEquals(hubId, binding.rendezvousId, "the rendezvous id is NOT the hubId")
        assertFalse(binding.rendezvousId.contains(hubId), "the opaque id never contains the hubId (RR4)")
        // [RendezvousBinding] exposes ONLY (rendezvousId, relayUrl) — there is no epoch field, so the CP-secret epoch
        // cannot leak on the wire (a compile-time guarantee).
    }

    @Test fun register_perRegistration_rotatesTheId() {
        val r = LiveRelayRendezvous(relayUrl)
        val id1 = r.register("hub_x")!!.rendezvousId
        val id2 = r.register("hub_x")!!.rendezvousId
        assertNotEquals(id1, id2, "a NEW registration mints a fresh epoch → a fresh id (unlinkable across registrations)")
    }

    @Test fun registerThenResolve_roundTrips_sameId() {
        val r = LiveRelayRendezvous(relayUrl)
        val registered = r.register("hub_y")!!.rendezvousId
        assertEquals(registered, r.resolve("hub_y")!!.rendezvousId, "resolve returns the LAST-registered rendezvous")
    }

    @Test fun resolve_unknownHub_isNull() {
        assertNull(LiveRelayRendezvous(relayUrl).resolve("never-registered"), "a hub that never registered has no rendezvous")
    }

    @Test fun inert_whenNoRelayUrl_bothNull() {
        val r = LiveRelayRendezvous(relayUrl = null)
        assertNull(r.register("hub_z"), "INERT: no relay configured → no registration")
        assertNull(r.resolve("hub_z"), "INERT: no relay configured → no resolution")
    }

    @Test fun epoch_is128BitCpSecret_andDrivesTheId() {
        var requestedBytes = -1
        val fixedEpoch = ByteArray(16) { 0x5A }
        val r = LiveRelayRendezvous(relayUrl, randomBytes = { n -> requestedBytes = n; fixedEpoch })
        val binding = r.register("hub_q")!!
        assertEquals(16, requestedBytes, "the epoch is 128-bit (16 bytes) of CP randomness")
        assertContentEquals(RelayRendezvous.rendezvousId("hub_q", fixedEpoch).encodeToByteArray(), binding.rendezvousId.encodeToByteArray(),
            "the id is base64url(SHA-256(hubId ‖ epoch)) — derived from the CP-secret epoch, which itself never leaves the CP")
    }
}
