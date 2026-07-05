package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-240 (B) — [CoalescingIdentityProvider]: the ~N concurrent shell-load whoami for the SAME credential
 * collapse to ONE in-flight [IdentityProvider.resolve], with **no cross-request cache** (a completed
 * resolution is evicted → the next request re-resolves, so logout/revoke is honored sub-second).
 */
class CoalescingIdentityProviderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** A delegate that counts entries and parks each resolution on [gate] until the test releases it — so the
     *  test can prove N callers are simultaneously in-flight (and therefore MUST share one resolution). */
    private class GatedDelegate(private val gate: CompletableDeferred<Unit>) : IdentityProvider {
        val entered = AtomicInteger(0)
        val leaderEntered = CompletableDeferred<Unit>()
        override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
            entered.incrementAndGet()
            leaderEntered.complete(Unit)
            gate.await() // park in-flight
            return credential?.let { ResolvedIdentity("id-${it.value}", verified = true) }
        }
    }

    private fun cred(v: String, s: SessionCredential.Source = SessionCredential.Source.HEADER) = SessionCredential(v, s)

    @Test fun concurrentSameCredential_shareOneInFlightResolution() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val delegate = GatedDelegate(gate)
        val idp = CoalescingIdentityProvider(delegate, scope)

        // Leader enters + parks in-flight.
        val leader = scope.async { idp.resolve(cred("sess-x")) }
        delegate.leaderEntered.await() // leader is provably inside delegate (parked on the gate)
        // Now fire N-1 more for the SAME credential WHILE the leader is still in-flight → they must join it,
        // not enter the delegate.
        val followers = (1..4).map { scope.async { idp.resolve(cred("sess-x")) } }
        // (No follower can have entered the delegate: the only in-flight resolution is the leader's.)
        assertEquals(1, delegate.entered.get(), "concurrent same-credential calls share ONE delegate resolution")

        gate.complete(Unit) // release the single in-flight resolution
        val results = (listOf(leader) + followers).awaitAll()
        assertEquals(1, delegate.entered.get(), "still exactly one whoami after all awaiters completed")
        assertEquals(List(5) { ResolvedIdentity("id-sess-x", verified = true) }, results, "all awaiters get the shared result")
    }

    @Test fun differentCredentials_and_differentSources_doNotCoalesce() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val delegate = GatedDelegate(gate)
        val idp = CoalescingIdentityProvider(delegate, scope)
        // 3 distinct keys: two different values, plus the SAME value on a different source (header vs cookie is a
        // different Kratos call → must not merge).
        val a = scope.async { idp.resolve(cred("sess-a")) }
        val b = scope.async { idp.resolve(cred("sess-b")) }
        val c = scope.async { idp.resolve(cred("sess-a", SessionCredential.Source.COOKIE)) }
        // let them all enter
        while (delegate.entered.get() < 3) kotlinx.coroutines.yield()
        assertEquals(3, delegate.entered.get(), "distinct (value,source) keys each resolve independently")
        gate.complete(Unit); awaitAll(a, b, c)
    }

    @Test fun noCrossRequestCache_sequentialCallsReResolve() = runBlocking {
        // No gate parking: each call completes, so the entry is evicted → the next call is a FRESH resolution.
        // This is why a revoke/logout is honored immediately (the rejected TTL-cache would have hidden it).
        val counted = object : IdentityProvider {
            val n = AtomicInteger(0)
            override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
                n.incrementAndGet(); return ResolvedIdentity("id", verified = true)
            }
        }
        val idp = CoalescingIdentityProvider(counted, scope)
        idp.resolve(cred("sess-x"))
        idp.resolve(cred("sess-x"))
        idp.resolve(cred("sess-x"))
        assertEquals(3, counted.n.get(), "no cross-request cache: each non-overlapping request re-resolves (revoke honored)")
    }
}
