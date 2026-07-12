package com.tneff.cyppieagents.controlplane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * CYP-501 — the activation-contract INERT seams: the RR4 **opaque** rendezvous-id derivation (opaque, deterministic,
 * epoch-rotating) and the Inert stubs (no live relay / mint → the current server is unchanged).
 */
class Cyp501ActivationSeamsTest {

    @Test
    fun rendezvousId_isOpaque_deterministic_andEpochRotates() {
        val hubId = "hub_abc"
        val epoch1 = ByteArray(16) { 1 }
        val epoch2 = ByteArray(16) { 2 }
        val id1 = RelayRendezvous.rendezvousId(hubId, epoch1)

        assertEquals(id1, RelayRendezvous.rendezvousId(hubId, epoch1), "deterministic for the same hubId+epoch")
        assertNotEquals(hubId, id1, "opaque — the id is never the hubId (RR4)")
        assertFalse(id1.contains(hubId), "the hubId does not appear in the opaque rendezvous id")
        // A new REGISTRATION (new epoch) rotates the id → unlinkable ACROSS REGISTRATIONS. Within one registration the
        // id is STABLE (the `deterministic` assert above) → the relay can link that hub's sessions = accepted RR4
        // metadata residual, NOT per-session unlinkability.
        assertNotEquals(id1, RelayRendezvous.rendezvousId(hubId, epoch2), "a new epoch (registration) rotates the id")
    }

    @Test
    fun inertSeams_failClosed_serverUnchanged() {
        assertNull(InertRelayRendezvous.register("hub_abc"), "INERT: no live rendezvous registration")
        assertNull(InertRelayRendezvous.resolve("hub_abc"), "INERT: no live rendezvous resolution")
        // INERT mint = a typed fail-closed deny (not a live cpJwt, not a generic null).
        val r = InertHubTicketMinter.mint(HubTicketRequest("hub_abc", "cb"), CpOperatorSession("dead"))
        assertNull(r.cpJwt, "INERT: no live hubTicket minted")
        assertEquals(HubTicketFailure.NOT_AUTHORIZED_FOR_HUB, r.failure, "INERT: fail-closed to the terminal deny")
    }
}
