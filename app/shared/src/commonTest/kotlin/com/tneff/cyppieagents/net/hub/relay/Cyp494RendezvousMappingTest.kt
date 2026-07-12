package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.controlplane.RendezvousBinding
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.tneff.cyppieagents.controlplane.RendezvousFailure as CoreRendezvousFailure

/**
 * CYP-494 — the fail-closed mapping of the CYP-507 `:core` [RendezvousResolveResponse] to [RendezvousResolution].
 * The wire type has **no exactly-one invariant** (both fields nullable) → the mapper MUST treat both-null AND
 * both-set as failures (never a half [RendezvousResolution.Bound] built from a binding that arrived with a
 * failure). Clean either/or maps straight through.
 */
class Cyp494RendezvousMappingTest {

    @Test
    fun bindingOnly_mapsToBound() {
        val r = RendezvousResolveResponse(binding = RendezvousBinding("rzv-1", "ws://relay/x")).toResolution()
        val bound = assertIs<RendezvousResolution.Bound>(r)
        assertEquals("rzv-1", bound.rendezvousId)
        assertEquals("ws://relay/x", bound.relayUrl)
    }

    @Test
    fun failureOnly_notRegistered_mapsToFailed() {
        val r = RendezvousResolveResponse(failure = CoreRendezvousFailure.NOT_REGISTERED).toResolution()
        assertEquals(RendezvousResolution.Failed(RendezvousUnavailable.NOT_REGISTERED), r)
    }

    @Test
    fun failureOnly_relayUnavailable_mapsToFailed() {
        val r = RendezvousResolveResponse(failure = CoreRendezvousFailure.RELAY_UNAVAILABLE).toResolution()
        assertEquals(RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE), r)
    }

    @Test
    fun bothNull_failsClosed_neverBound() {
        val r = RendezvousResolveResponse(binding = null, failure = null).toResolution()
        assertIs<RendezvousResolution.Failed>(r) // never a Bound out of an empty response
        assertEquals(RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE), r)
    }

    @Test
    fun bothSet_failsClosed_neverUsesTheBinding() {
        // A binding that arrives ALONGSIDE a failure must never be dialed (no exactly-one guarantee on the wire).
        val r = RendezvousResolveResponse(
            binding = RendezvousBinding("rzv-evil", "ws://attacker/x"),
            failure = CoreRendezvousFailure.NOT_REGISTERED,
        ).toResolution()
        assertIs<RendezvousResolution.Failed>(r)
        assertEquals(RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE), r)
    }
}
