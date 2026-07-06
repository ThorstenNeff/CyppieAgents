package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-240 (B) — [CoalescingIdentityProvider]: the ~N concurrent shell-load whoami for the SAME credential
 * collapse to ONE in-flight [IdentityProvider.resolve], with **no cross-request cache** (a completed
 * resolution is evicted → the next request re-resolves, so logout/revoke is honored sub-second).
 *
 * CYP-251 — DETERMINISTIC by construction: driven on the [runTest] virtual-time scheduler, not
 * `Dispatchers.Default` + wall-clock. The provider's internal `async` runs on the test dispatcher (via
 * [backgroundScope]); `UnconfinedTestDispatcher` runs each launched coroutine eagerly to its first suspension, so "N callers are
 * simultaneously in-flight" and "all entered" are OBSERVED states before the assert, never timing guesses (the old
 * `Default`-thread version flaked under full-suite parallel load — a spurious red could mask a real one).
 */
class CoalescingIdentityProviderTest {

    /** A delegate that counts entries and parks each resolution on [gate] until the test releases it — so the
     *  test can prove N callers are simultaneously in-flight (and therefore MUST share one resolution). */
    private class GatedDelegate(private val gate: CompletableDeferred<Unit>) : IdentityProvider {
        val entered = AtomicInteger(0)
        override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
            entered.incrementAndGet()
            gate.await() // park in-flight
            return credential?.let { ResolvedIdentity("id-${it.value}", verified = true) }
        }
    }

    private fun cred(v: String, s: SessionCredential.Source = SessionCredential.Source.HEADER) = SessionCredential(v, s)

    @Test fun concurrentSameCredential_shareOneInFlightResolution() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val delegate = GatedDelegate(gate)
        // The provider's coalescing `async` runs on the test dispatcher via backgroundScope (auto-cancelled).
        val idp = CoalescingIdentityProvider(delegate, backgroundScope)

        // Leader + 4 followers for the SAME credential, all launched, then driven to their suspend points.
        val leader = async { idp.resolve(cred("sess-x")) }
        val followers = (1..4).map { async { idp.resolve(cred("sess-x")) } }
        // UnconfinedTestDispatcher runs each launched coroutine EAGERLY to its first suspension: the leader enters
        // the delegate + parks on the gate, and the followers JOIN its in-flight Deferred — all before the assert.

        assertEquals(1, delegate.entered.get(), "concurrent same-credential calls share ONE delegate resolution")

        gate.complete(Unit) // release the single in-flight resolution
        val results = (listOf(leader) + followers).awaitAll()
        assertEquals(1, delegate.entered.get(), "still exactly one whoami after all awaiters completed")
        assertEquals(List(5) { ResolvedIdentity("id-sess-x", verified = true) }, results, "all awaiters get the shared result")
    }

    @Test fun differentCredentials_and_differentSources_doNotCoalesce() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val delegate = GatedDelegate(gate)
        val idp = CoalescingIdentityProvider(delegate, backgroundScope)
        // 3 distinct keys: two different values, plus the SAME value on a different source (header vs cookie is a
        // different Kratos call → must not merge).
        val a = async { idp.resolve(cred("sess-a")) }
        val b = async { idp.resolve(cred("sess-b")) }
        val c = async { idp.resolve(cred("sess-a", SessionCredential.Source.COOKIE)) }
        // Eager: all three distinct-key resolutions enter the delegate independently (no coalescing) and park.

        assertEquals(3, delegate.entered.get(), "distinct (value,source) keys each resolve independently")

        gate.complete(Unit)
        awaitAll(a, b, c)
    }

    @Test fun noCrossRequestCache_sequentialCallsReResolve() = runTest(UnconfinedTestDispatcher()) {
        // No gate parking: each call completes, so the entry is evicted → the next call is a FRESH resolution.
        // This is why a revoke/logout is honored immediately (the rejected TTL-cache would have hidden it).
        val counted = object : IdentityProvider {
            val n = AtomicInteger(0)
            override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
                n.incrementAndGet(); return ResolvedIdentity("id", verified = true)
            }
        }
        val idp = CoalescingIdentityProvider(counted, backgroundScope)
        idp.resolve(cred("sess-x"))
        idp.resolve(cred("sess-x"))
        idp.resolve(cred("sess-x"))
        assertEquals(3, counted.n.get(), "no cross-request cache: each non-overlapping request re-resolves (revoke honored)")
    }
}
