package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.model.HubTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-865 — the switch-first trust-degradation honesty matrix ([displayedTrust]): the ACTIVE hub shows its live
 * observed trust (UNKNOWN until observed); an INACTIVE hub is not observed → last-known lapses (was-TRUSTED → STALE,
 * NEVER cached-trusted; was-REJECTED preserved; anything else → UNKNOWN). Fail-closed throughout.
 */
class HubTrustProvenanceTest {

    @Test
    fun active_liveObserved_returnsTheObservedTrust() {
        val p = recordObservation(emptyTrustProvenance, "a", HubTrustState.TRUSTED, observedAt = 1L)
        assertEquals(HubTrustState.TRUSTED, displayedTrust(p, hubId = "a", activeHubId = "a"))
    }

    @Test
    fun active_neverObserved_isUnknown() {
        assertEquals(HubTrustState.UNKNOWN, displayedTrust(emptyTrustProvenance, hubId = "a", activeHubId = "a"))
    }

    @Test
    fun inactive_wasTrusted_isStale_neverCachedTrusted() {
        // The load-bearing honesty rule: a switched-away hub that WAS trusted lapses to STALE — never keep showing
        // a cached TRUSTED (that would be derivation-masquerading-as-observation). Mutation TRUSTED→TRUSTED → RED.
        val p = recordObservation(emptyTrustProvenance, "b", HubTrustState.TRUSTED, observedAt = 1L)
        assertEquals(HubTrustState.STALE, displayedTrust(p, hubId = "b", activeHubId = "a"))
    }

    @Test
    fun inactive_wasRejected_staysRejected_failClosedNegativePreserved() {
        val p = recordObservation(emptyTrustProvenance, "b", HubTrustState.REJECTED, observedAt = 1L)
        assertEquals(HubTrustState.REJECTED, displayedTrust(p, hubId = "b", activeHubId = "a"))
    }

    @Test
    fun inactive_neverObserved_isUnknown() {
        assertEquals(HubTrustState.UNKNOWN, displayedTrust(emptyTrustProvenance, hubId = "b", activeHubId = "a"))
    }

    @Test
    fun inactive_wasPendingOrUnknownOrStale_isUnknown_nothingFreshToAffirm() {
        for (t in listOf(HubTrustState.PENDING, HubTrustState.UNKNOWN, HubTrustState.STALE)) {
            val p = recordObservation(emptyTrustProvenance, "b", t, observedAt = 1L)
            assertEquals(HubTrustState.UNKNOWN, displayedTrust(p, hubId = "b", activeHubId = "a"), "inactive was $t → UNKNOWN")
        }
    }

    @Test
    fun recordObservation_isPure_immutable_carriesProvenanceTime() {
        val p1 = emptyTrustProvenance
        val p2 = recordObservation(p1, "a", HubTrustState.TRUSTED, observedAt = 42L)
        assertTrue(p1.isEmpty(), "recordObservation is pure — the prior map is untouched")
        assertEquals(HubTrustState.TRUSTED, p2["a"]?.trust)
        assertEquals(42L, p2["a"]?.observedAt, "the observation time is carried as honest provenance")
    }

    @Test
    fun recordObservation_overwrites_withLatestObservation() {
        val p = recordObservation(
            recordObservation(emptyTrustProvenance, "a", HubTrustState.TRUSTED, observedAt = 1L),
            "a", HubTrustState.REJECTED, observedAt = 2L,
        )
        assertEquals(HubTrustState.REJECTED, p["a"]?.trust)
        assertEquals(2L, p["a"]?.observedAt)
    }
}
