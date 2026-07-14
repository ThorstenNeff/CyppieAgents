package com.tneff.cyppieagents.net.hub.operator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-538 (WS3, §4 C1 locked @ `889e6919`) — UV-caching + per-tunnel PoP derivation teeth (transport-independent).
 * Fakes the [OperatorDeviceKeyStore] (real crypto lives in [PerTunnelPoPRr3JvmTest]) with a store that mirrors the
 * REAL "UV-first, then sign THIS challenge" shape and **echoes the challenge into the signature** — so a distinct
 * per-tunnel challenge ⇒ a distinct PoP (no signature reuse is observable), and the UV gating is exercised exactly
 * as production does it.
 *
 * The load-bearing (non-vacuous) teeth — each has a named mutant that turns it RED:
 *  - [uvPromptedExactlyOnce_acrossNTunnels] — mutant: re-prompt per tunnel ⇒ prompts == N.
 *  - [boundedReuseWindow_repromptsPastTtl] — mutant: unbounded cache (drop the expiry check) ⇒ prompts stays 1.
 *  - [uvDenied_failsClosed_notCached_nextTunnelReprompts] — mutant: cache on denial ⇒ no re-prompt / cachedUv non-null.
 *  - [antiReplay_onlyUvCached_neverSignature_distinctPopPerTunnel] — mutant: cache/return the first PoP ⇒ pops equal.
 */
class PerTunnelPoPProviderTest {

    /** Counts REAL prompts and lets a test flip the outcome; an optional [delayMs] forces coroutine overlap. */
    private class CountingUv(
        var outcome: UvOutcome = UvOutcome.Verified,
        private val delayMs: Long = 0,
    ) : UserVerification {
        var prompts = 0
            private set
        override suspend fun verify(reason: UvReason): UvOutcome {
            prompts++
            if (delayMs > 0) delay(delayMs)
            return outcome
        }
    }

    /** Mirrors [KeystoreOperatorDeviceKeyStore]: not-enrolled short-circuits BEFORE any UV; else UV-first, then sign
     *  THIS challenge. The "signature" echoes the challenge so distinct challenges ⇒ distinct, non-reusable PoPs.
     *  Note: `sign` does NOT receive `rvid` — the provider structurally cannot thread rvid into the crypto. */
    private class EchoStore(
        private val uv: UserVerification,
        private val enrolled: Boolean = true,
    ) : OperatorDeviceKeyStore {
        var signs = 0
            private set
        override fun isEnrolled() = enrolled
        override fun devicePublicKey(): ByteArray? = null
        override suspend fun sign(challenge: ByteArray): PopResult {
            if (!enrolled) return PopResult.NotEnrolled // not-enrolled is decided before UV — no prompt leak
            return when (val o = uv.verify(UvReason.OPERATOR_AUTH)) {
                UvOutcome.Verified -> {
                    signs++
                    PopResult.Signed(DevicePoP.Raw(challenge.copyOf()))
                }
                is UvOutcome.Denied -> PopResult.UvFailed(o.reason)
                UvOutcome.Unavailable -> PopResult.AuthenticatorUnavailable
            }
        }
    }

    private fun provider(
        uv: CountingUv,
        clock: () -> Long,
        windowMs: Long = 1_000,
        enrolled: Boolean = true,
    ): UvCachingPerTunnelPoPProvider =
        UvCachingPerTunnelPoPProvider.wrap(uv, windowMs, clock) { caching -> EchoStore(caching, enrolled) }

    private fun ready(o: PerTunnelPopOutcome): ByteArray = assertIs<PerTunnelPopOutcome.Ready>(o).pop

    // ---- Tooth 1: UV once across N tunnels (+ every k covered, not just tunnel 0) ---------------------------------

    @Test
    fun uvPromptedExactlyOnce_acrossNTunnels() = runTest {
        val uv = CountingUv()
        val p = provider(uv, clock = { 0L })

        val pops = (0 until 4).map { k -> ready(p.popFor(rvid = "rv-$k", challenge = byteArrayOf(k.toByte(), 0xAB.toByte()))) }

        assertEquals(1, uv.prompts, "N per-tunnel derivations MUST cost exactly ONE UV prompt")
        pops.forEachIndexed { k, pop ->
            assertContentEquals(byteArrayOf(k.toByte(), 0xAB.toByte()), pop, "every tunnel k=$k signs its OWN challenge")
        }
    }

    // ---- openSession = the one ceremony ---------------------------------------------------------------------------

    @Test
    fun openSession_isTheOneCeremony_thenPopForDoesNotReprompt() = runTest {
        val uv = CountingUv()
        val p = provider(uv, clock = { 0L })

        val assertion = assertNotNull(p.openSession(), "openSession returns the assertion that unlocks the N signs")
        assertEquals(UvReason.OPERATOR_AUTH, assertion.reason)
        assertEquals(1, uv.prompts, "openSession is exactly one ceremony")

        repeat(3) { k -> assertIs<PerTunnelPopOutcome.Ready>(p.popFor("rv-$k", byteArrayOf(k.toByte()))) }
        assertEquals(1, uv.prompts, "the N per-tunnel signs reuse the open gate — no extra prompts")
    }

    @Test
    fun openSession_uvDenied_returnsNull_failClosed() = runTest {
        val uv = CountingUv(outcome = UvOutcome.Denied(UvFailReason.CANCELLED))
        val p = provider(uv, clock = { 0L })

        assertNull(p.openSession(), "a denied/cancelled UV opens NO session (fail-closed — caller aborts before dialing)")
        assertNull(p.cachedUv, "no gate is left open after a denied ceremony")
    }

    // ---- Tooth 2: bounded reuse window (NOT unbounded) — the PO1/Assist axis --------------------------------------

    @Test
    fun boundedReuseWindow_repromptsPastTtl() = runTest {
        val uv = CountingUv()
        var now = 0L
        val p = provider(uv, clock = { now }, windowMs = 1_000)

        assertIs<PerTunnelPopOutcome.Ready>(p.popFor("rv-0", byteArrayOf(0))) // prompt #1 @ t=0
        now = 999
        assertIs<PerTunnelPopOutcome.Ready>(p.popFor("rv-1", byteArrayOf(1))) // still in window → cache hit
        assertEquals(1, uv.prompts, "within the bounded window the cache serves without a re-prompt")

        now = 1_000 // exactly at expiry → invalid (half-open, fail-closed edge)
        assertIs<PerTunnelPopOutcome.Ready>(p.popFor("rv-2", byteArrayOf(2)))
        assertEquals(2, uv.prompts, "past the bounded window the cache is dropped → a fresh UV prompt (never unbounded)")
    }

    @Test
    fun cachedUv_isNullBeforeFirstUv_andAfterWindow() = runTest {
        val uv = CountingUv()
        var now = 0L
        val p = provider(uv, clock = { now }, windowMs = 1_000)

        assertNull(p.cachedUv, "no cached UV before the first prompt")
        p.popFor("rv-0", byteArrayOf(0))
        val a = assertNotNull(p.cachedUv, "a valid cached UV is exposed after the first prompt")
        assertEquals(UvReason.OPERATOR_AUTH, a.reason)
        assertEquals(1_000, a.expiresAtMs, "the bounded window is verifiedAt + reuseWindow")
        now = 1_000
        assertNull(p.cachedUv, "the cached UV is not exposed once the bounded window has elapsed")
    }

    // ---- Tooth 3: fail-closed on UV denial — not cached, next tunnel re-prompts -----------------------------------

    @Test
    fun uvDenied_failsClosed_notCached_nextTunnelReprompts() = runTest {
        val uv = CountingUv(outcome = UvOutcome.Denied(UvFailReason.WRONG_PIN))
        val p = provider(uv, clock = { 0L })

        val denied = p.popFor("rv-0", byteArrayOf(0))
        assertEquals(PerTunnelPopOutcome.UvFailed(UvFailReason.WRONG_PIN), denied, "UV denial ⇒ retryable UvFailed, no PoP")
        assertNull(p.cachedUv, "a denial is NEVER cached (fail-closed — must not satisfy the next tunnel)")

        p.popFor("rv-1", byteArrayOf(1))
        assertEquals(2, uv.prompts, "a denied UV does not vouch for the next tunnel — it re-prompts")
    }

    @Test
    fun notEnrolled_passesThrough_noUvPromptLeak() = runTest {
        val uv = CountingUv()
        val p = provider(uv, clock = { 0L }, enrolled = false)

        assertEquals(PerTunnelPopOutcome.NotEnrolled, p.popFor("rv-0", byteArrayOf(0)))
        assertEquals(0, uv.prompts, "not-enrolled routes to enroll BEFORE any UV prompt (no prompt leak)")
        assertNull(p.cachedUv)
    }

    @Test
    fun authenticatorUnavailable_passesThrough_failClosed() = runTest {
        val uv = CountingUv(outcome = UvOutcome.Unavailable)
        val p = provider(uv, clock = { 0L })

        assertEquals(PerTunnelPopOutcome.Unavailable, p.popFor("rv-0", byteArrayOf(0)))
        assertNull(p.cachedUv, "an unavailable authenticator is not a cached grant")
    }

    // ---- Tooth 4: per-tunnel anti-replay — ONLY the UV is cached, never the signature ----------------------------

    @Test
    fun antiReplay_onlyUvCached_neverSignature_distinctPopPerTunnel() = runTest {
        val uv = CountingUv()
        val p = provider(uv, clock = { 0L })

        val chA = byteArrayOf(0xA1.toByte(), 0xA2.toByte())
        val chB = byteArrayOf(0xB1.toByte(), 0xB2.toByte())
        val sigA = ready(p.popFor("rv-0", chA))
        val sigB = ready(p.popFor("rv-1", chB))

        assertEquals(1, uv.prompts, "still one UV — but the signatures are per-tunnel")
        assertContentEquals(chA, sigA, "tunnel A's PoP is over tunnel A's OWN challenge")
        assertContentEquals(chB, sigB, "tunnel B's PoP is over tunnel B's OWN challenge")
        assertFalse(sigA.contentEquals(sigB), "no signature reuse across tunnels — a PoP for A cannot authenticate B")
    }

    /** rvid is crypto-inert: the SAME challenge under DIFFERENT rvids yields the SAME signature — the provider never
     *  threads rvid into the crypto (real-Ed25519 grounding in [PerTunnelPoPRr3JvmTest]). */
    @Test
    fun rvidIsCryptoInert_sameChallengeDifferentRvid_sameSignature() = runTest {
        val uv = CountingUv()
        val p = provider(uv, clock = { 0L })
        val challenge = byteArrayOf(0x11, 0x22, 0x33)

        val underA = ready(p.popFor("rendezvous-A", challenge))
        val underB = ready(p.popFor("rendezvous-B", challenge))

        assertContentEquals(underA, underB, "rvid MUST NOT enter the signature — same challenge ⇒ same PoP")
    }

    // ---- Concurrency: the pool's concurrent derivations still cost exactly one prompt ------------------------------

    @Test
    fun concurrentPoolDerivations_singlePrompt() = runTest {
        val uv = CountingUv(delayMs = 50) // force overlap: the first prompt suspends while the others queue on the mutex
        val p = provider(uv, clock = { 0L })

        val outcomes = coroutineScope {
            (0 until 5).map { k -> async { p.popFor("rv-$k", byteArrayOf(k.toByte())) } }.awaitAll()
        }

        assertEquals(1, uv.prompts, "concurrent per-tunnel derivations serialize on the UV → exactly one prompt")
        assertTrue(outcomes.all { it is PerTunnelPopOutcome.Ready }, "every concurrent tunnel still gets its PoP")
    }
}
