package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.operator.CachingUserVerification
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-537 F⑥-1 — the **consumer contract** the N-tunnel pool relies on: the ONE shared [CachingUserVerification]
 * (wired as the shared `operatorAuth` store's `userVerification`) gives **1 UV prompt for N tunnels**. The session's
 * control-tunnel authenticate + the pool's N workspace-tunnel authenticates all call the same UV → one prompt within
 * the bounded window, N reuses, **0 extra prompts** (the dogfood UX property). Fail-closed is preserved: a denial is
 * never cached. This pins the property; the joint Backend e2e proves it over REAL tunnels. (WS3 owns the class + its
 * own tests; this is the pool's dependency guard.)
 */
class Cyp537PoolUvReuseTest {

    /** A UV delegate that counts real prompts and returns [outcome]. */
    private class CountingUv(private val outcome: UvOutcome) : UserVerification {
        var prompts = 0
        override suspend fun verify(reason: UvReason): UvOutcome { prompts++; return outcome }
    }

    private fun caching(delegate: UserVerification, now: () -> Long) =
        CachingUserVerification(delegate = delegate, reuseWindowMs = 120_000L, nowMs = now)

    @Test
    fun oneUvForN_withinWindow_sequential() = runTest {
        val raw = CountingUv(UvOutcome.Verified)
        val uv = caching(raw) { 1_000L } // fixed clock ⇒ all within the window
        repeat(5) { assertEquals(UvOutcome.Verified, uv.verify(UvReason.OPERATOR_AUTH)) }
        assertEquals(1, raw.prompts, "N tunnel authenticates ⇒ exactly ONE real UV prompt (the F⑥-1 property)")
    }

    @Test
    fun oneUvForN_concurrent_singlePrompt() = runTest {
        // The pool authenticates its N tunnels CONCURRENTLY (off-lock) — the cache's Mutex must still yield ONE prompt.
        val raw = CountingUv(UvOutcome.Verified)
        val uv = caching(raw) { 1_000L }
        val outcomes = (1..8).map { async { uv.verify(UvReason.OPERATOR_AUTH) } }.awaitAll()
        assertEquals(List(8) { UvOutcome.Verified }, outcomes)
        assertEquals(1, raw.prompts, "8 concurrent authenticates ⇒ ONE prompt (Mutex + cache), never a torn double-prompt")
    }

    @Test
    fun denial_neverCached_failClosed() = runTest {
        // A denial must NOT satisfy the next tunnel — each re-prompts (never inherits a stale grant).
        val raw = CountingUv(UvOutcome.Unavailable)
        val uv = caching(raw) { 1_000L }
        repeat(3) { assertEquals(UvOutcome.Unavailable, uv.verify(UvReason.OPERATOR_AUTH)) }
        assertEquals(3, raw.prompts, "a non-Verified outcome is never cached (fail-closed) — every call re-delegates")
    }

    @Test
    fun pastWindow_reprompts_boundedReuse() = runTest {
        val raw = CountingUv(UvOutcome.Verified)
        var clock = 1_000L
        val uv = caching(raw) { clock }
        assertEquals(UvOutcome.Verified, uv.verify(UvReason.OPERATOR_AUTH))
        clock += 120_001L // past the bounded window
        assertEquals(UvOutcome.Verified, uv.verify(UvReason.OPERATOR_AUTH))
        assertEquals(2, raw.prompts, "reuse is bounded — past the window the next authenticate re-prompts")
    }
}
