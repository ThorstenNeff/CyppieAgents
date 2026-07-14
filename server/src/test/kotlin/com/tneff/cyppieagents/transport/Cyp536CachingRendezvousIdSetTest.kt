package com.tneff.cyppieagents.transport

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-536 (M2 Option A, WS1) — [CachingRendezvousIdSet]: the anti-rotation cache for the epoch-derived rendezvous SET.
 * Registers ONCE, reuses on reconnect (a re-register would rotate the epoch → a different set → the client's resolved
 * set no longer pairs); never caches a null/empty register.
 */
class Cyp536CachingRendezvousIdSetTest {
    @Test
    fun registersOnce_reusesCachedSet() = runBlocking {
        val calls = AtomicInteger()
        val cache = CachingRendezvousIdSet { calls.incrementAndGet(); listOf("id0", "id1", "id2") }
        val first = cache.get()
        val second = cache.get()
        assertEquals(listOf("id0", "id1", "id2"), first)
        assertEquals(first, second, "the reconnect reuses the SAME cached set (anti-rotation)")
        assertEquals(1, calls.get(), "register ran exactly once — never re-registered (would rotate the epoch/set)")
    }

    @Test
    fun failedRegister_notCached_retriesNextGet() = runBlocking {
        val calls = AtomicInteger()
        // First call returns null (register failed), second returns a real set → the null must NOT have been cached.
        val cache = CachingRendezvousIdSet { if (calls.getAndIncrement() == 0) null else listOf("id0") }
        assertNull(cache.get(), "a failed register yields null")
        assertEquals(listOf("id0"), cache.get(), "the next get RETRIES (null was never cached)")
        assertEquals(2, calls.get(), "register was retried after the failure")
    }

    @Test
    fun emptyRegister_notCached_retriesNextGet() = runBlocking {
        val calls = AtomicInteger()
        val cache = CachingRendezvousIdSet { if (calls.getAndIncrement() == 0) emptyList() else listOf("id0") }
        assertNull(cache.get(), "an empty set is treated as no-registration (null) — fail-closed, never cached")
        assertEquals(listOf("id0"), cache.get(), "the next get retries past the empty register")
        assertEquals(2, calls.get(), "register was retried after the empty result")
    }
}
