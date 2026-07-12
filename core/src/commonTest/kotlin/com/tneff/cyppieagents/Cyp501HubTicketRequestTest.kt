package com.tneff.cyppieagents

import com.tneff.cyppieagents.controlplane.HubTicketFailure
import com.tneff.cyppieagents.controlplane.HubTicketRequest
import com.tneff.cyppieagents.controlplane.HubTicketResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-501 — the `:core` activation wire contracts ([HubTicketRequest] / [HubTicketResponse] / [HubTicketFailure])
 * round-trip through the shared [CommJson] on every target (jvm/js/wasmJs/android), so the desktop client (Kotlin)
 * and the regenerated TS type (web) decode the same bytes — incl. the **typed operator-facing failure** distinction.
 */
class Cyp501HubTicketRequestTest {
    @Test
    fun hubTicketRequest_roundTrips_allTargets() {
        val r = HubTicketRequest(hubId = "hub_0011223344556677", cb = "Y2ItYmFzZTY0dXJsLXNoYTI1Ng")
        assertEquals(r, CommJson.decodeFromString<HubTicketRequest>(CommJson.encodeToString(r)))
    }

    @Test
    fun hubTicketResponse_minted_roundTrips() {
        val r = HubTicketResponse(cpJwt = "eyJhbGciOiJFZERTQSJ9.payload.sig")
        val rt = CommJson.decodeFromString<HubTicketResponse>(CommJson.encodeToString(r))
        assertEquals("eyJhbGciOiJFZERTQSJ9.payload.sig", rt.cpJwt)
        assertNull(rt.failure, "a minted response carries no failure")
    }

    @Test
    fun hubTicketResponse_typedFailure_roundTrips_everyCause() {
        for (f in HubTicketFailure.entries) {
            val rt = CommJson.decodeFromString<HubTicketResponse>(CommJson.encodeToString(HubTicketResponse(failure = f)))
            assertEquals(f, rt.failure, "the typed operator-facing cause survives the wire")
            assertNull(rt.cpJwt, "a failure response carries no cpJwt")
        }
    }
}
