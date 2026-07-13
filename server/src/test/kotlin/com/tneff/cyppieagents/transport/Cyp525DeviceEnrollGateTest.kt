package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.ed25519RawToSpki
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-525 — TOFU first-enroll under the RR3 gate. On an EMPTY operator device store, the first PoP that proves
 * possession of its presented raw-32B Ed25519 key, under a CpJwt-authenticated operator, anchors that key (owner =
 * the authenticated operator, CT-2b). A subsequent connect then verifies against the enrolled anchor (CONNECTED
 * eligible). Real [CpJwtVerifier] + [OperatorAssertionVerifier] + a real CP JWT + a real Ed25519 device signature,
 * over the REAL `CommJson` [TunnelAuthRequest] wire (not a same-byte fixture) — Tester Axis-1's encode→verify boundary.
 */
class Cyp525DeviceEnrollGateTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_test"
    private val operatorId = "op-1"
    private val issuer = "cp-issuer"
    private val kid = "kid1"
    private val nowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519()
    private val device = RawKeys.generateEd25519()
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)

    private fun config() = Rr3Config(
        hubId = hubId, pinnedOperatorId = operatorId, expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) cp.publicRaw else null }, expectedRpId = "hub.example",
    )
    private fun gate(store: InMemoryOperatorDeviceStore) =
        Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), store, config(), now = { nowMs })

    private fun validCpJwt() = minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), nowMs, ttlMs = 300_000)
    private fun sigBy(priv: ByteArray, nonce: ByteArray) = RawKeys.ed25519Sign(priv, operatorAuthChallenge(h, hubId, nonce))
    private fun request(cpJwt: String, popSig: ByteArray, nonce: ByteArray, devicePublicKey: ByteArray? = null) =
        CommJson.encodeToString(TunnelAuthRequest(cpJwt, OperatorPoPWire.Raw(popSig), nonce, devicePublicKey)).encodeToByteArray()

    private class GateTunnel(private val tunnelH: ByteArray, request: ByteArray) : ServerNoiseTunnel {
        private val inbound = ArrayDeque(listOf(request))
        val sent = mutableListOf<ByteArray>()
        override val handshakeHash: ByteArray get() = tunnelH.copyOf()
        override suspend fun receive(): ByteArray? = inbound.removeFirstOrNull()
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun close() {}
    }

    private fun run(gate: Rr3TunnelGate, request: ByteArray): Pair<Boolean, TunnelAuthGrant?> = runBlocking {
        val t = GateTunnel(h, request)
        val ok = gate.authorize(t)
        ok to t.sent.firstOrNull()?.let { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }
    }

    @Test
    fun emptyStore_firstPoP_underCpJwt_enrolls_thenSteadyStateVerifies() {
        val store = InMemoryOperatorDeviceStore()
        val g = gate(store)
        // 1) first connect: empty store + valid CpJwt + PoP + raw-32B device key → TOFU enroll + grant.
        assertNull(store.enrolled(), "precondition: empty store")
        val (ok1, grant1) = run(g, request(validCpJwt(), sigBy(device.privateRaw, byteArrayOf(1)), byteArrayOf(1), devicePublicKey = device.publicRaw))
        assertTrue(ok1, "first PoP under a CpJwt anchors the device and grants")
        assertEquals(true, grant1?.granted)
        // CT-2b: the anchor is owned by the AUTHENTICATED operator, and IS the presented key.
        assertEquals(operatorId, store.enrolled()?.deviceId, "owner = the authenticated operator (CT-2b), not a payload claim")
        assertTrue(store.enrolled()?.publicKey.contentEquals(device.publicRaw), "the enrolled key is the presented device key (raw-32B)")
        // 2) steady-state: a fresh connect (store now non-empty, no devicePublicKey) verifies against the anchor.
        val (ok2, _) = run(g, request(validCpJwt(), sigBy(device.privateRaw, byteArrayOf(2)), byteArrayOf(2)))
        assertTrue(ok2, "a subsequent connect verifies against the enrolled anchor (CONNECTED eligible)")
    }

    @Test
    fun emptyStore_noCpJwt_neverEnrolls_antiLandGrab() {
        val store = InMemoryOperatorDeviceStore()
        // valid PoP + a device key, but a BROKEN CpJwt → reject AND the store stays empty (no enroll without a CpJwt).
        val (ok, _) = run(gate(store), request("not.a.jwt", sigBy(device.privateRaw, byteArrayOf(3)), byteArrayOf(3), devicePublicKey = device.publicRaw))
        assertFalse(ok, "no valid CpJwt ⇒ no authorize")
        assertNull(store.enrolled(), "no valid CpJwt ⇒ NO enroll (anti-land-grab: enroll is bound to the authenticated operator)")
    }

    @Test
    fun emptyStore_presentedKeyNotSignable_isNeverAnchored_verifyThenEnroll() {
        val store = InMemoryOperatorDeviceStore()
        val other = RawKeys.generateEd25519()
        // devicePublicKey = `other`, but the PoP is signed by `device` (the client doesn't hold `other`'s private key).
        val (ok, _) = run(gate(store), request(validCpJwt(), sigBy(device.privateRaw, byteArrayOf(4)), byteArrayOf(4), devicePublicKey = other.publicRaw))
        assertFalse(ok, "the PoP doesn't match the presented key ⇒ reject")
        assertNull(store.enrolled(), "verify-then-enroll: a key the client can't sign with is NEVER anchored")
    }

    @Test
    fun emptyStore_missingDeviceKey_failsClosed() {
        val store = InMemoryOperatorDeviceStore()
        // valid CpJwt + PoP but NO devicePublicKey on the wire → nothing to anchor → fail-closed (no enroll, no grant).
        val (ok, _) = run(gate(store), request(validCpJwt(), sigBy(device.privateRaw, byteArrayOf(5)), byteArrayOf(5), devicePublicKey = null))
        assertFalse(ok, "no device key on an empty store ⇒ can't anchor ⇒ reject")
        assertNull(store.enrolled())
    }

    @Test
    fun alreadyEnrolled_newDevice_neverReEnrolls_recoveryIsQ6Seam() {
        val original = RawKeys.generateEd25519()
        val store = InMemoryOperatorDeviceStore().apply { save(EnrolledOperatorDevice("dev1", DeviceKeyAlg.ED25519, original.publicRaw)) }
        val g = gate(store)
        val newDev = RawKeys.generateEd25519()
        // a valid CpJwt + a NEW device's PoP + the NEW key → must NOT re-enroll (recovery is never central-login-alone).
        val (ok, _) = run(g, request(validCpJwt(), sigBy(newDev.privateRaw, byteArrayOf(6)), byteArrayOf(6), devicePublicKey = newDev.publicRaw))
        assertFalse(ok, "a non-enrolled device is rejected against a non-empty store (no re-enroll)")
        assertTrue(store.enrolled()?.publicKey.contentEquals(original.publicRaw), "the original anchor is untouched — re-enroll is the Q6-gated recovery seam")
    }

    @Test
    fun emptyStore_spkiOnTheWire_defensivelyStripped_enrolls() {
        val store = InMemoryOperatorDeviceStore()
        // The client sent X.509 SPKI-44B instead of raw-32B (an encoding slip): the hub's defensive reader strips it
        // rather than silently bad-signature — the exact "grün-gebaut-nie-CONNECTED" trap, fixed at the encoding boundary.
        val spki = ed25519RawToSpki(device.publicRaw)
        val (ok, _) = run(gate(store), request(validCpJwt(), sigBy(device.privateRaw, byteArrayOf(7)), byteArrayOf(7), devicePublicKey = spki))
        assertTrue(ok, "a mistaken SPKI-44B on the wire is stripped to raw-32B and enrolls (robust encoding boundary)")
        assertTrue(store.enrolled()?.publicKey.contentEquals(device.publicRaw), "the anchor is the raw-32B key")
    }
}
