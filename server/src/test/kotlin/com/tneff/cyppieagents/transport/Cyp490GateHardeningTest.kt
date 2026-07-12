package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.IdentityToken
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-490 (CYP-459 Reviewer FU-2 gate-hardening):
 *  - **CB-and-b** — a failed CpJwt short-circuits BEFORE the operator PoP verify, so a bad-CpJwt + valid-PoP never
 *    pre-burns the operator's single-use nonce (CYP-477-class grief); the operator's legit retry with that nonce
 *    still grants.
 *  - **CB-d1 short-circuit spy** — a malformed (non-32B) `h` is rejected fail-early: the CpJwt verify is **never
 *    reached** (proven by a spy verifier), so the "fail-early" guard is load-bearing, not vacuously redundant.
 */
class Cyp490GateHardeningTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_test"
    private val operatorId = "op-1"
    private val issuer = "cp-issuer"
    private val kid = "kid1"
    private val nowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519()
    private val device = RawKeys.generateEd25519()
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)
    private val deviceStore = InMemoryOperatorDeviceStore().apply {
        save(EnrolledOperatorDevice("dev1", DeviceKeyAlg.ED25519, device.publicRaw))
    }

    private fun config() = Rr3Config(hubId, operatorId, issuer, { k -> if (k == kid) cp.publicRaw else null }, "hub.example")
    private fun gate(verifier: IdentityToken = CpJwtVerifier()) =
        Rr3TunnelGate(verifier, OperatorAssertionVerifier(), deviceStore, config(), now = { nowMs })

    private fun validCpJwt() = minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), nowMs, 300_000)
    private fun validPopSig(nonce: ByteArray) = RawKeys.ed25519Sign(device.privateRaw, operatorAuthChallenge(h, hubId, nonce))
    private fun request(cpJwt: String, popSig: ByteArray, nonce: ByteArray) =
        CommJson.encodeToString(TunnelAuthRequest(cpJwt, OperatorPoPWire.Raw(popSig), nonce)).encodeToByteArray()

    private class GateTunnel(private val tunnelH: ByteArray, request: ByteArray) : ServerNoiseTunnel {
        private val inbound = ArrayDeque(listOf(request))
        val sent = mutableListOf<ByteArray>()
        override val handshakeHash: ByteArray get() = tunnelH.copyOf()
        override suspend fun receive(): ByteArray? = inbound.removeFirstOrNull()
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun close() {}
    }

    private fun run(gate: Rr3TunnelGate, tunnelH: ByteArray, request: ByteArray): Pair<Boolean, TunnelAuthGrant?> =
        runBlocking {
            val t = GateTunnel(tunnelH, request)
            val ok = gate.authorize(t)
            ok to t.sent.firstOrNull()?.let { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }
        }

    @Test
    fun cbAndB_badCpJwt_withValidPoP_doesNotBurnNonce_legitRetryGrants() {
        val g = gate() // one gate → one shared nonce ledger across the two attempts
        val nonce = byteArrayOf(20)
        // 1. a bad CpJwt with a valid PoP (nonce N) → reject; the short-circuit must NOT reach the PoP verify → N unspent.
        assertFalse(run(g, h, request("not.a.jwt", validPopSig(nonce), nonce)).first, "bad CpJwt → reject")
        // 2. the operator's legit attempt — good CpJwt + valid PoP with the SAME nonce N → grant (N was not pre-burned).
        assertTrue(
            run(g, h, request(validCpJwt(), validPopSig(nonce), nonce)).first,
            "the legit retry with the same nonce still grants — a bad-CpJwt attempt did NOT burn the nonce (CB-and-b)",
        )
    }

    @Test
    fun cbD1_malformedH_shortCircuits_cpJwtVerifyNeverReached() {
        val spy = SpyVerifier()
        val (ok, grant) = run(gate(spy), ByteArray(16), request(validCpJwt(), validPopSig(byteArrayOf(21)), byteArrayOf(21)))
        assertFalse(ok, "a non-32B h → reject")
        assertEquals("auth_failed", grant?.reason)
        assertEquals(0, spy.verifyCalls.get(), "CB-d1 is a load-bearing short-circuit: the CpJwt verify is NEVER reached for a malformed h")
    }

    @Test
    fun cbD1_validH_reachesCpJwtVerify_control() {
        // Non-vacuity control: a 32B h DOES reach the CpJwt verify (so the spy isn't just always-zero).
        val spy = SpyVerifier()
        run(gate(spy), h, request(validCpJwt(), validPopSig(byteArrayOf(22)), byteArrayOf(22)))
        assertEquals(1, spy.verifyCalls.get(), "a well-formed 32B h reaches the CpJwt verify")
    }

    /** A spy [IdentityToken] that records how often verify() is called (and always rejects). */
    private class SpyVerifier : IdentityToken {
        val verifyCalls = AtomicInteger(0)
        override fun verify(token: String, ctx: VerifierContext): AuthPrincipal? {
            verifyCalls.incrementAndGet()
            return null
        }
    }
}
