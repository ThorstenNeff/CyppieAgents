package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDeviceStore
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.crypto.SecretCipherException
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-459 — the [Rr3TunnelGate] security teeth: the tunnel is authorized IFF the CP-signed CpJwt (identity, live-`h`
 * channel-binding) AND the operator device PoP (live-`h` challenge) both verify. Real [CpJwtVerifier] +
 * [OperatorAssertionVerifier] + a real CP-minted JWT + a real Ed25519 device signature; the tunnel is a fake carrying
 * the [TunnelAuthRequest]. Covers CB-x1 (cross-session replay), nonce replay, CB-d1 (bad-`h`), and CB-and-c
 * (AND-never-OR both ways). Uniform no-oracle reject.
 */
class Cyp459Rr3GateTest {

    private val h = ByteArray(32) { (it + 1).toByte() } // a 32-byte live handshake hash
    private val hubId = "hub_test"
    private val operatorId = "op-1"
    private val issuer = "cp-issuer"
    private val kid = "kid1"
    private val nowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519()      // the CP signing keypair
    private val device = RawKeys.generateEd25519()  // the operator device keypair
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)
    private val deviceStore = InMemoryOperatorDeviceStore().apply {
        save(EnrolledOperatorDevice("dev1", DeviceKeyAlg.ED25519, device.publicRaw))
    }

    private fun config() = Rr3Config(
        hubId = hubId,
        pinnedOperatorId = operatorId,
        expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) cp.publicRaw else null },
        expectedRpId = "hub.example",
    )
    private fun gate() = Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), deviceStore, config(), now = { nowMs })

    private fun validCpJwt(hForCb: ByteArray = h) =
        minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(hForCb, hubId), nowMs, ttlMs = 300_000)
    private fun validPopSig(hForChallenge: ByteArray = h, nonce: ByteArray) =
        RawKeys.ed25519Sign(device.privateRaw, operatorAuthChallenge(hForChallenge, hubId, nonce))
    private fun request(cpJwt: String, popSig: ByteArray, nonce: ByteArray) =
        CommJson.encodeToString(TunnelAuthRequest(cpJwt, OperatorPoPWire.Raw(popSig), nonce)).encodeToByteArray()

    /** A fake tunnel: exposes [tunnelH], yields [request] once from receive(), captures the grant reply. */
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
            val grant = t.sent.firstOrNull()?.let { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }
            ok to grant
        }

    @Test
    fun validTriple_grantsAndAuthorizes() {
        val nonce = byteArrayOf(1, 2, 3)
        val (ok, grant) = run(gate(), h, request(validCpJwt(), validPopSig(nonce = nonce), nonce))
        assertTrue(ok, "a valid CpJwt + PoP against the live h authorizes the tunnel")
        assertEquals(true, grant?.granted)
    }

    @Test
    fun tamperedCpJwt_rejects_uniformCode() {
        val nonce = byteArrayOf(4)
        val tampered = validCpJwt().dropLast(3) + "AAA" // corrupt the CP signature
        val (ok, grant) = run(gate(), h, request(tampered, validPopSig(nonce = nonce), nonce))
        assertFalse(ok)
        assertEquals(false, grant?.granted)
        assertEquals("auth_failed", grant?.reason, "reject is the uniform non-secret code (no oracle)")
    }

    @Test
    fun badPoP_wrongDeviceKey_rejects() {
        val nonce = byteArrayOf(5)
        val wrong = RawKeys.generateEd25519()
        val badSig = RawKeys.ed25519Sign(wrong.privateRaw, operatorAuthChallenge(h, hubId, nonce))
        val (ok, _) = run(gate(), h, request(validCpJwt(), badSig, nonce))
        assertFalse(ok, "a PoP signed by a non-enrolled device is rejected")
    }

    @Test
    fun cbX1_cpJwtBoundToAnotherSessionH_rejected() {
        // ★ CB-x1: the CpJwt's cb is bound to a DIFFERENT session's h (hB), but the live tunnel h is hA. The PoP is
        // valid for hA, so ONLY the channel-binding differs → the CHANNEL_BINDING predicate rejects the replay.
        val hB = ByteArray(32) { (it + 99).toByte() }
        val nonce = byteArrayOf(6)
        val cpJwtForB = validCpJwt(hForCb = hB)
        val (ok, _) = run(gate(), h, request(cpJwtForB, validPopSig(nonce = nonce), nonce))
        assertFalse(ok, "a CpJwt bound to another session's h fails the channel-binding (anti-cross-session-replay)")
    }

    @Test
    fun replayedNonce_rejected() {
        val g = gate() // one gate → one nonce ledger shared across both authorize calls
        val nonce = byteArrayOf(7)
        assertTrue(run(g, h, request(validCpJwt(), validPopSig(nonce = nonce), nonce)).first, "first use authorizes")
        // replay the SAME nonce (freshly-signed, still valid crypto) → the single-use ledger rejects it
        assertFalse(
            run(g, h, request(validCpJwt(), validPopSig(nonce = nonce), nonce)).first,
            "a replayed nonce is rejected (single-use ledger)",
        )
    }

    @Test
    fun cbD1_nonThirtyTwoByteH_failsClosed_beforeVerify() {
        val nonce = byteArrayOf(8)
        val (ok, grant) = run(gate(), ByteArray(16), request(validCpJwt(), validPopSig(nonce = nonce), nonce))
        assertFalse(ok, "CB-d1: a non-32B live h fails closed before any verify (no ambiguous cb)")
        assertEquals("auth_failed", grant?.reason)
    }

    @Test
    fun cbAndC_validCpJwt_butBadPoP_rejected_notOr() {
        val nonce = byteArrayOf(10)
        val wrong = RawKeys.generateEd25519()
        val badPop = RawKeys.ed25519Sign(wrong.privateRaw, operatorAuthChallenge(h, hubId, nonce))
        assertFalse(
            run(gate(), h, request(validCpJwt(), badPop, nonce)).first,
            "AND (not OR): a valid CpJwt with an invalid PoP is still rejected",
        )
    }

    @Test
    fun cbX4_tokenBoundToConstantH_rejectedOnLiveHTunnel() {
        // ★ CB-x4: a CpJwt + PoP bound to a FIXED all-zeros h (exactly what a constant-`h` verifier would compute).
        // On a real-`h` tunnel the correct gate REJECTS it — it derives the challenge/cb from the LIVE h, not a
        // constant. Mutation (gate uses `ByteArray(32)`/any constant instead of `tunnel.handshakeHash`) → this grants = RED.
        val zeros = ByteArray(32)
        val nonce = byteArrayOf(12)
        val cpJwtZeros = validCpJwt(hForCb = zeros)
        val popZeros = validPopSig(hForChallenge = zeros, nonce = nonce)
        assertFalse(
            run(gate(), h, request(cpJwtZeros, popZeros, nonce)).first,
            "the gate MUST use the live h, never a constant (a constant-h verifier would accept this cross-session token)",
        )
    }

    @Test
    fun cbAndC_validPoP_butBadCpJwt_rejected_notOr() {
        val nonce = byteArrayOf(11)
        assertFalse(
            run(gate(), h, request("not.a.jwt", validPopSig(nonce = nonce), nonce)).first,
            "AND (not OR): a valid PoP with an invalid CpJwt is still rejected",
        )
    }

    @Test
    fun t4_successfulAuthorize_againstEnrolledStore_isReadOnly_neverReEnrolls() {
        // ★ T4 (scoped by CYP-525): against an ALREADY-ENROLLED store (this fixture pre-enrolls `dev1`), a successful
        // authorize is read-only — it NEVER re-enrolls/mutates the anchor (re-enroll is the Q6-gated recovery seam,
        // never central-login-alone). CYP-525 adds a sanctioned exception ONLY for an EMPTY store (TOFU first-enroll
        // under a CpJwt), covered separately; the steady-state laundering guard here is unchanged. Mutation (the gate
        // re-enrolls / mutates a non-empty store) → the anchor changes = RED.
        val before = deviceStore.enrolled()
        val nonce = byteArrayOf(13)
        assertTrue(run(gate(), h, request(validCpJwt(), validPopSig(nonce = nonce), nonce)).first, "precondition: authorized")
        assertSame(before, deviceStore.enrolled(), "a successful RR3 authorize against an already-enrolled store never re-enrolls/mutates the anchor")
    }

    @Test
    fun cyp557_tamperedDeviceStoreAnchor_failsClosed_cleanReject_notUncaughtThrow() {
        // ★ CYP-557 (CYP-550 ③): the pre-GE5 fallback `deviceStore.enrolled()` throws on a tampered at-rest blob
        // (`SecretStoreBackedOperatorDeviceStore` fails-closed with `SecretCipherException`). Unguarded, that throw
        // propagates out of `authorize` → the handler closes the tunnel → a silent reconnect/lockout loop with NO
        // operator diagnostic (the exact silent-lockout `rejectTampered` exists to prevent). The fix wraps the fallback
        // in the same `rejectTampered` guard: a clean fail-closed reject, never an uncaught throw. MUT (remove the
        // guard) → `authorize` throws → this test errors = RED.
        val tamperedStore = object : OperatorDeviceStore {
            override fun enrolled(): EnrolledOperatorDevice? = throw SecretCipherException("tampered device blob")
            override fun save(device: EnrolledOperatorDevice) {}
        }
        val gate = Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), tamperedStore, config(), now = { nowMs })
        val nonce = byteArrayOf(20)
        val (ok, grant) = run(gate, h, request(validCpJwt(), validPopSig(nonce = nonce), nonce))
        assertFalse(ok, "a tampered deviceStore anchor → clean fail-closed reject, never an uncaught throw (CYP-557)")
        assertEquals("auth_failed", grant?.reason, "the uniform reject code (the rejectTampered fail-closed path)")
    }
}
