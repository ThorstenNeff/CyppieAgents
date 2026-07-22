package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.transport.RemoteRelayWiring.RemoteIssuerTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CYP-804 ① — the `RemoteIssuerTrustState → HubIssuerTrust` wire map ([toWire]). The ONE place the server-internal
 * `ISSUER_`-prefixed names cross to the FROZEN wire vocabulary (PL-0107). Pins each arm's exact target + the
 * no-`ISSUER_`-leak property over ALL states. A MISSING arm = a COMPILE error (exhaustive `when`, no `else`); a
 * WRONG arm (mutation: swap a target) reds the per-arm assert here.
 */
class Cyp804IssuerTrustMappingTest {

    @Test
    fun eachStateMapsToTheFrozenWireName() {
        assertEquals(HubIssuerTrust.TRUSTED, RemoteIssuerTrustState.ISSUER_TRUSTED.toWire())
        assertEquals(HubIssuerTrust.NOT_TRUSTED, RemoteIssuerTrustState.ISSUER_NOT_TRUSTED.toWire())
        assertEquals(HubIssuerTrust.REMOTE_NOT_CONFIGURED, RemoteIssuerTrustState.REMOTE_NOT_CONFIGURED.toWire())
    }

    @Test
    fun mapIsTotal_andNeverLeaksTheIssuerPrefixOntoTheWire() {
        // Totality: every server state maps (no throw). No-leak: no wire value carries the server-internal ISSUER_ prefix
        // — the whole point of the frozen PL-0107 vocabulary.
        for (s in RemoteIssuerTrustState.values()) {
            val wire = s.toWire()
            assertFalse(wire.name.startsWith("ISSUER_"), "wire value ${wire.name} leaked the server ISSUER_ prefix (state $s)")
        }
    }
}
