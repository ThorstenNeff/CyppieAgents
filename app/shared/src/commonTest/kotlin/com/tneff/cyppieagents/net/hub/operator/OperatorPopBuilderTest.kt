package com.tneff.cyppieagents.net.hub.operator

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * CYP-443 Slice 2 — the transport-independent [OperatorPopBuilder] + PoP taxonomy + channel-binding honesty teeth.
 * Fakes the store so the wiring, the retryable-vs-terminal taxonomy, fresh-nonce, and the `h`-channel-binding are
 * pinned here (crypto is exercised in the jvm keystore test).
 */
class OperatorPopBuilderTest {

    private class FakeStore(private val result: PopResult) : OperatorDeviceKeyStore {
        var lastChallenge: ByteArray? = null
        override fun isEnrolled() = true
        override fun devicePublicKey(): ByteArray? = null
        override suspend fun sign(challenge: ByteArray): PopResult {
            lastChallenge = challenge
            return result
        }
    }

    @Test
    fun signed_returnsReadyWithNonce_overTheChannelBoundChallenge() = runTest {
        val nonce = byteArrayOf(1, 2, 3, 4)
        val store = FakeStore(PopResult.Signed(DevicePoP.Raw(byteArrayOf(9, 9))))
        val out = OperatorPopBuilder(store, { nonce }).buildPop(ByteArray(32) { 1 }, "hub-1")
        val ready = assertIs<PopBuildOutcome.Ready>(out)
        assertContentEquals(nonce, ready.nonce, "the nonce travels with the PoP so the hub recomputes the challenge")
        assertContentEquals(
            operatorAuthChallenge(ByteArray(32) { 1 }, "hub-1", nonce), store.lastChallenge,
            "the store signs the challenge bound to THIS h + hubId + nonce",
        )
    }

    @Test
    fun uvFailed_isRetryableTaxonomy_notAHubReject() = runTest {
        val out = OperatorPopBuilder(FakeStore(PopResult.UvFailed(UvFailReason.WRONG_PIN)), { byteArrayOf(0) })
            .buildPop(ByteArray(32), "h")
        // Local wrong-PIN stays a distinct, retryable local outcome — never conflated with a hub terminal reject.
        assertEquals(PopBuildOutcome.UvFailed(UvFailReason.WRONG_PIN), out)
    }

    @Test
    fun notEnrolled_andUnavailable_passThrough() = runTest {
        assertEquals(
            PopBuildOutcome.NotEnrolled,
            OperatorPopBuilder(FakeStore(PopResult.NotEnrolled), { byteArrayOf(0) }).buildPop(ByteArray(32), "h"),
        )
        assertEquals(
            PopBuildOutcome.AuthenticatorUnavailable,
            OperatorPopBuilder(FakeStore(PopResult.AuthenticatorUnavailable), { byteArrayOf(0) }).buildPop(ByteArray(32), "h"),
        )
    }

    @Test
    fun freshNoncePerPoP() = runTest {
        var counter = 0
        val gen = NonceGenerator { byteArrayOf(counter++.toByte(), 0x55) }
        val builder = OperatorPopBuilder(FakeStore(PopResult.Signed(DevicePoP.Raw(byteArrayOf(1)))), gen)
        val a = assertIs<PopBuildOutcome.Ready>(builder.buildPop(ByteArray(32), "h"))
        val b = assertIs<PopBuildOutcome.Ready>(builder.buildPop(ByteArray(32), "h"))
        assertFalse(a.nonce.contentEquals(b.nonce), "a fresh nonce per PoP (no replay)")
    }

    @Test
    fun challenge_bindsLiveH_andIsUnambiguous() {
        val hA = ByteArray(32) { 0x0A }
        val hB = ByteArray(32) { 0x0B }
        val nonce = byteArrayOf(7, 7)
        assertFalse(
            operatorAuthChallenge(hA, "hub", nonce).contentEquals(operatorAuthChallenge(hB, "hub", nonce)),
            "different h ⇒ different challenge (channel-binding to the live session)",
        )
        // Length-prefixing removes concat ambiguity: (hubId="ab", nonce="c") must differ from (hubId="a", nonce="bc").
        assertFalse(
            operatorAuthChallenge(hA, "ab", byteArrayOf('c'.code.toByte()))
                .contentEquals(operatorAuthChallenge(hA, "a", byteArrayOf('b'.code.toByte(), 'c'.code.toByte()))),
            "length-prefixed fields ⇒ no boundary collision",
        )
    }
}
