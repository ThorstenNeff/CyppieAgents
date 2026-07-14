package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.operatorAuthChallenge
import java.security.KeyPair
import java.security.Signature
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-538 (WS3, §4 C1) — **RR3-grounded, real-crypto** proof of the pure-signer over the LOCKED format
 * (`:core operatorAuthChallenge`, CYP-473: `LP(h_i)‖LP(hubId)‖LP(nonce_i)‖LP("operator-auth")`, `Ed25519.sign`).
 * The transport-independent teeth are in [PerTunnelPoPProviderTest] (fake store); this pins the bytes against a
 * REAL persistent Ed25519 device key + REAL RR3 challenges, so "Ready(bytes) verifies server-side" is proven, not
 * asserted against an echo.
 *
 * WS3 is a binding-agnostic pure signer, so the two server-guarded per-tunnel invariants are *observable* here:
 *  - **R1 (channel-binding):** a PoP over tunnel A's `h_A` does NOT verify against tunnel B's challenge (`h_B`) —
 *    a foreign `h` ⇒ `bad_signature`. WS2 supplies the distinct `h_i`; the provider just signs it.
 *  - **rvid crypto-inert:** Ed25519 is deterministic (RFC 8032) → the SAME challenge under DIFFERENT rvids yields
 *    byte-identical signatures ⇒ rvid provably never entered the signed message.
 */
class PerTunnelPoPRr3JvmTest {

    private class StubUv(private val outcome: UvOutcome = UvOutcome.Verified) : UserVerification {
        var prompts = 0
            private set
        override suspend fun verify(reason: UvReason): UvOutcome {
            prompts++
            return outcome
        }
    }

    private fun ed25519Verifies(publicKeyPair: KeyPair, challenge: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("Ed25519").run {
            initVerify(publicKeyPair.public)
            update(challenge)
            verify(signature)
        }

    @Test
    fun rr3PoP_verifiesServerSide_r1BindsToItsOwnH_andRvidIsCryptoInert() = runTest {
        val keyPair = KeystoreOperatorDeviceKeyStore.generateDeviceKey() // real Ed25519 operator device key
        val uv = StubUv()
        val provider = UvCachingPerTunnelPoPProvider.wrap(
            rawUv = uv,
            reuseWindowMs = 60_000,
            nowMs = { 0L },
        ) { caching -> KeystoreOperatorDeviceKeyStore(caching, keyPair) }

        // WS2 assembles each tunnel's own RR3 challenge (distinct live handshake hash h_i + fresh nonce_i).
        val hubId = "hub-42"
        val chTunnel0 = operatorAuthChallenge(ByteArray(32) { 0xA0.toByte() }, hubId, nonce = byteArrayOf(1, 1))
        val chTunnel1 = operatorAuthChallenge(ByteArray(32) { 0xB1.toByte() }, hubId, nonce = byteArrayOf(2, 2))

        val sig0 = assertIs<PerTunnelPopOutcome.Ready>(provider.popFor("rv-0", chTunnel0)).pop
        val sig1 = assertIs<PerTunnelPopOutcome.Ready>(provider.popFor("rv-1", chTunnel1)).pop
        assertEquals(1, uv.prompts, "one UV ceremony unlocked BOTH per-tunnel signs (cached presence, not cached sig)")

        // Server-side truth: each PoP verifies over ITS OWN challenge…
        assertTrue(ed25519Verifies(keyPair, chTunnel0, sig0), "tunnel-0 PoP verifies over tunnel-0's RR3 challenge")
        assertTrue(ed25519Verifies(keyPair, chTunnel1, sig1), "tunnel-1 PoP verifies over tunnel-1's RR3 challenge")
        // …R1: and does NOT verify over the OTHER tunnel's challenge (foreign h ⇒ bad_signature).
        assertFalse(ed25519Verifies(keyPair, chTunnel1, sig0), "R1: a foreign h_i ⇒ bad_signature (no cross-tunnel replay)")
        assertFalse(ed25519Verifies(keyPair, chTunnel0, sig1), "R1: a foreign h_i ⇒ bad_signature (no cross-tunnel replay)")

        // rvid crypto-inert: the SAME challenge under a DIFFERENT rvid yields a byte-identical signature.
        val again = assertIs<PerTunnelPopOutcome.Ready>(provider.popFor("some-other-rendezvous-id", chTunnel0)).pop
        assertTrue(sig0.contentEquals(again), "rvid MUST NOT enter the signature — deterministic Ed25519 proves it")
    }

    @Test
    fun notEnrolled_whenNoDeviceKey_failsClosed_noPrompt() = runTest {
        val uv = StubUv()
        val provider = UvCachingPerTunnelPoPProvider.wrap(uv, 60_000, { 0L }) { caching ->
            KeystoreOperatorDeviceKeyStore(caching, keyPair = null) // not enrolled
        }
        assertEquals(PerTunnelPopOutcome.NotEnrolled, provider.popFor("rv-0", byteArrayOf(9)))
        assertEquals(0, uv.prompts, "no device key ⇒ NotEnrolled BEFORE any UV prompt")
    }
}
