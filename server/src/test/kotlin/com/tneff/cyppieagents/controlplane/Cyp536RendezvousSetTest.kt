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
    fun cyp611_dosCapIs16_element0IsControl_dataIdsHaveHeadroom() {
        // CYP-611 — the per-operator DoS cap (WS6 axis 2). Was raised 16→24 (Auftraggeber-authorized LOOSENING) to
        // clear the pre-mux 8-agent pool-exhaustion foot-gun. CYP-611 REVERT (24→16, TIGHTENING): the CYP-840 status-mux
        // consolidates the 4 singleton status feeds → ONE /ws/status, dropping the client WS working set (6 globals → 3)
        // so the foot-gun is relieved and the 24-headroom is no longer needed — tightening back toward the original DoS
        // floor. Data-ids = cap-1 (element 0 = control tunnel) → 15 usable, covering the post-mux ~11-WS working set + 1
        // REST with headroom. Guard pins the CURRENT authorized value; a change (either direction) is a DoS-envelope edit.
        assertEquals(16, RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP, "CYP-611 revert: DoS cap = 16 (post-CYP-840-mux)")
        val dataIds = RelayRendezvous.rendezvousIdSet(hubId, epoch, RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP).drop(1)
        assertEquals(15, dataIds.size, "data-id pool (after the control id) = cap-1 = 15")
        assertTrue(dataIds.size >= 12, "must cover the post-mux ~11-WS working set + 1 REST")
    }
}
