package com.tneff.cyppieagents.controlplane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-536 (M2 Option A, WS1) — the epoch-derived rendezvous **N-set** ([RelayRendezvous.rendezvousId] indexed +
 * [RelayRendezvous.rendezvousIdSet]). Pure-logic teeth: determinism, per-index + per-epoch distinctness, set size.
 * The set is the C4 (develop `889e6919`) `id_i = base64url(SHA-256(hubId‖epoch‖i))`.
 */
class Cyp536RendezvousSetTest {
    private val hubId = "hub-abc"
    private val epoch = ByteArray(16) { it.toByte() }

    @Test
    fun indexedId_isDeterministic_forSameInputs() {
        assertEquals(
            RelayRendezvous.rendezvousId(hubId, epoch, 3),
            RelayRendezvous.rendezvousId(hubId, epoch, 3),
            "same (hubId, epoch, index) ⇒ same id (both ends derive-from-CP consistently)",
        )
    }

    @Test
    fun distinctIndices_yieldDistinctIds() {
        val ids = (0 until 8).map { RelayRendezvous.rendezvousId(hubId, epoch, it) }
        assertEquals(ids.size, ids.toSet().size, "each tunnel index gets a DISTINCT rendezvous-id → N independent 1↔1 relay pairings")
    }

    @Test
    fun indexedId_differsFromUnindexedBaseId() {
        // The indexed id_0 (hubId‖epoch‖BE32(0)) is a different preimage than the legacy 2-arg id (hubId‖epoch) — the
        // N-set is its own indexed family; both derive from the SAME CP so pairing is unaffected either way.
        assertNotEquals(
            RelayRendezvous.rendezvousId(hubId, epoch),
            RelayRendezvous.rendezvousId(hubId, epoch, 0),
            "indexed id_0 is a distinct preimage from the unindexed base id",
        )
    }

    @Test
    fun distinctEpochs_yieldDistinctSets_unlinkability() {
        val setA = RelayRendezvous.rendezvousIdSet(hubId, epoch, 4)
        val epochB = ByteArray(16) { (it + 100).toByte() }
        val setB = RelayRendezvous.rendezvousIdSet(hubId, epochB, 4)
        assertTrue(setA.intersect(setB.toSet()).isEmpty(), "a fresh epoch rotates the WHOLE set (unlinkable across registrations)")
    }

    @Test
    fun liveRendezvous_register_populatesTheNSet_element0EqualsBaseId() {
        val r = LiveRelayRendezvous("wss://relay.example", cap = 5)
        val binding = r.register("hub-q")!!
        assertEquals(5, binding.rendezvousIds.size, "register returns the full N-set (cap ids)")
        assertEquals(binding.rendezvousId, binding.rendezvousIds.first(), "rendezvousId == rendezvousIds.first() (C4 invariant)")
        assertEquals(binding.rendezvousIds.size, binding.rendezvousIds.toSet().size, "the N ids are distinct → N independent pairings")
        // resolve returns the SAME set (same stored epoch) so the hub-register set and the client-resolve set match.
        assertEquals(binding.rendezvousIds, r.resolve("hub-q")!!.rendezvousIds, "resolve returns the same N-set the hub registered")
    }

    @Test
    fun rendezvousIdSet_hasCapDistinctIds_element0IsLegacyBase() {
        val set = RelayRendezvous.rendezvousIdSet(hubId, epoch, RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP)
        assertEquals(RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP, set.size, "the set has exactly `cap` ids")
        assertEquals(set.size, set.toSet().size, "all `cap` ids are distinct")
        // element 0 == the legacy UNINDEXED base id (== RendezvousBinding.rendezvousId) → single-tunnel pairing unchanged.
        assertEquals(set[0], RelayRendezvous.rendezvousId(hubId, epoch), "set.first() == the legacy single-tunnel base id")
        assertEquals(set[1], RelayRendezvous.rendezvousId(hubId, epoch, 1), "set[i>=1] == the indexed id for i")
    }

    @Test
    fun cyp611_dosCapIs24_authorizedFloor_element0IsControl_dataIdsHaveHeadroom() {
        // CYP-611 — the per-operator DoS cap (WS6 axis 2) was raised 16→24 with the Auftraggeber's out-of-band GO. This
        // pins the AUTHORIZED value as a regression guard: reverting to 16 (or any drop below the workload) re-introduces
        // the 8-agent pool-exhaustion foot-gun and would be an UNauthorized DoS-envelope change. Data-ids = cap-1 (element
        // 0 is the control tunnel) → 23 usable, headroom to ~16 agents (7 singleton-WS + N agent-WS + 1 REST).
        assertEquals(24, RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP, "CYP-611: Auftraggeber-authorized DoS cap = 24")
        val dataIds = RelayRendezvous.rendezvousIdSet(hubId, epoch, RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP).drop(1)
        assertEquals(23, dataIds.size, "data-id pool (after the control id) = cap-1 = 23")
        assertTrue(dataIds.size >= 15, "must cover the 7-agent default (14 WS + 1 REST) with headroom")
    }
}
