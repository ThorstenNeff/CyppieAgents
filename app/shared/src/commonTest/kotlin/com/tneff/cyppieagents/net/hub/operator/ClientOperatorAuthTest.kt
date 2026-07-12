package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-486 — the RR3 tunnel-auth honesty teeth. `authenticate` proves possession over the LIVE handshake hash,
 * sends exactly one bound [TunnelAuthRequest], and returns the hub's [TunnelAuthGrant] verdict — **fail-closed**
 * on a missing ticket, a local PoP failure (sends **nothing**), a closed tunnel, or a non-granting reply.
 */
class ClientOperatorAuthTest {

    private val h = ByteArray(32) { 0x11 } // matches FakeTunnel.handshakeHash
    private val nonce = ByteArray(16) { 0x22 }
    private val sig = ByteArray(64) { 0x33 }
    private val hubId = "hub-1"

    private class FakeTunnel(private val reply: ByteArray?) : NoiseTunnel {
        var sent: ByteArray? = null
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) { sent = plaintext }
        override suspend fun receive(): ByteArray? = reply
        override suspend fun close() {}
    }

    private class FakeStore(
        private val result: PopResult,
        private val challengeSink: (ByteArray) -> Unit = {},
    ) : OperatorDeviceKeyStore {
        override fun isEnrolled(): Boolean = true
        override fun devicePublicKey(): ByteArray? = ByteArray(32)
        override suspend fun sign(challenge: ByteArray): PopResult { challengeSink(challenge); return result }
    }

    private fun grantBytes(granted: Boolean, reason: String? = null): ByteArray =
        CommJson.encodeToString(TunnelAuthGrant.serializer(), TunnelAuthGrant(granted, reason)).encodeToByteArray()

    private fun builder(result: PopResult, sink: (ByteArray) -> Unit = {}) =
        OperatorPopBuilder(FakeStore(result, sink), NonceGenerator { nonce })

    private fun auth(result: PopResult, jwt: String?, sink: (ByteArray) -> Unit = {}) =
        ClientOperatorAuth(builder(result, sink), CpJwtProvider { _, _ -> jwt })

    @Test
    fun granted_returnsTrue_andSendsChannelBoundRequest() = runTest {
        var signedChallenge: ByteArray? = null
        val tunnel = FakeTunnel(grantBytes(true))
        val a = auth(PopResult.Signed(DevicePoP.Raw(sig)), jwt = "jwt-abc") { signedChallenge = it }

        assertTrue(a.authenticate(tunnel, hubId))
        // channel-binding: the PoP was signed over the LIVE handshake hash + hubId + nonce.
        assertContentEquals(operatorAuthChallenge(tunnel.handshakeHash, hubId, nonce), signedChallenge)
        // the request binds exactly what we built (same jwt, same nonce, mapped pop).
        val req = CommJson.decodeFromString(TunnelAuthRequest.serializer(), tunnel.sent!!.decodeToString())
        assertEquals("jwt-abc", req.cpJwt)
        assertContentEquals(nonce, req.nonce)
        assertContentEquals(sig, assertIs<OperatorPoPWire.Raw>(req.pop).signature)
    }

    @Test
    fun fido2Pop_mapsToWireFido2() = runTest {
        val cid = ByteArray(8) { 0x44 }
        val ad = ByteArray(37) { 0x55 }
        val tunnel = FakeTunnel(grantBytes(true))
        val a = auth(PopResult.Signed(DevicePoP.Fido2(cid, ad, sig)), jwt = "j")
        assertTrue(a.authenticate(tunnel, hubId))
        val req = CommJson.decodeFromString(TunnelAuthRequest.serializer(), tunnel.sent!!.decodeToString())
        val f = assertIs<OperatorPoPWire.Fido2>(req.pop)
        assertContentEquals(cid, f.credentialId)
        assertContentEquals(ad, f.authenticatorData)
        assertContentEquals(sig, f.signature)
    }

    @Test
    fun grantedFalse_returnsFalse() = runTest {
        val a = auth(PopResult.Signed(DevicePoP.Raw(sig)), jwt = "j")
        assertFalse(a.authenticate(FakeTunnel(grantBytes(false, reason = "denied")), hubId))
    }

    @Test
    fun closedTunnel_receiveNull_failsClosed() = runTest {
        val a = auth(PopResult.Signed(DevicePoP.Raw(sig)), jwt = "j")
        assertFalse(a.authenticate(FakeTunnel(reply = null), hubId))
    }

    @Test
    fun localPopFailure_failsClosed_sendsNothing() = runTest {
        val tunnel = FakeTunnel(grantBytes(true))
        val a = auth(PopResult.NotEnrolled, jwt = "j")
        assertFalse(a.authenticate(tunnel, hubId))
        assertNull(tunnel.sent, "a local PoP failure must NOT leak a request onto the tunnel")
    }

    @Test
    fun missingTicket_failsClosed_sendsNothing_andDoesNotSign() = runTest {
        var signed = false
        val tunnel = FakeTunnel(grantBytes(true))
        val a = auth(PopResult.Signed(DevicePoP.Raw(sig)), jwt = null) { signed = true }
        assertFalse(a.authenticate(tunnel, hubId))
        assertNull(tunnel.sent, "no ticket ⇒ nothing sent")
        assertFalse(signed, "no ticket ⇒ never prompt the operator to sign")
    }
}
