package com.tneff.cyppieagents

import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-459 — the RR3 tunnel-auth `:core` wire contract round-trips through the shared [CommJson] on every target
 * (commonTest → jvm/js/wasmJs/android), so the hub server gate and the client `authenticate()` (CYP-486) decode the
 * same bytes. ByteArray fields need [assertContentEquals] (data-class equality is identity-based for arrays); the
 * key tooth is that the **discriminated [OperatorPoPWire] branch survives the wire** (Raw vs Fido2).
 */
class Cyp459TunnelAuthWireTest {

    private inline fun <reified T> roundTrip(value: T): T =
        CommJson.decodeFromString<T>(CommJson.encodeToString(value))

    @Test
    fun requestWithRawPoP_roundTrips_byteExact() {
        val req = TunnelAuthRequest(
            cpJwt = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJvcCJ9.sig",
            pop = OperatorPoPWire.Raw(signature = byteArrayOf(1, 2, 3, 4)),
            nonce = byteArrayOf(9, 8, 7),
        )
        val rt = roundTrip(req)
        assertEquals(req.cpJwt, rt.cpJwt)
        assertContentEquals(req.nonce, rt.nonce, "the freshness nonce survives byte-exact")
        val pop = assertIs<OperatorPoPWire.Raw>(rt.pop)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), pop.signature, "the Raw PoP signature survives byte-exact")
    }

    @Test
    fun requestWithFido2PoP_roundTrips_preservesDiscriminatedBranch() {
        val req = TunnelAuthRequest(
            cpJwt = "jwt",
            pop = OperatorPoPWire.Fido2(
                credentialId = byteArrayOf(5),
                authenticatorData = byteArrayOf(6, 7),
                signature = byteArrayOf(8, 9, 10),
            ),
            nonce = byteArrayOf(0),
        )
        val rt = roundTrip(req)
        val pop = assertIs<OperatorPoPWire.Fido2>(rt.pop) // ★ the discriminated branch survives the wire
        assertContentEquals(byteArrayOf(5), pop.credentialId)
        assertContentEquals(byteArrayOf(6, 7), pop.authenticatorData)
        assertContentEquals(byteArrayOf(8, 9, 10), pop.signature)
    }

    @Test
    fun grantRoundTrips_grantedAndReject_withStableReason() {
        val ok = roundTrip(TunnelAuthGrant(granted = true))
        assertTrue(ok.granted)
        assertNull(ok.reason, "a grant carries no reason")
        val no = roundTrip(TunnelAuthGrant(granted = false, reason = "auth_failed"))
        assertEquals(false, no.granted)
        assertEquals("auth_failed", no.reason, "the stable non-secret reject code survives")
    }
}
