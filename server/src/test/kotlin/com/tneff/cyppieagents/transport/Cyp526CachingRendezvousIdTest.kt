package com.tneff.cyppieagents.transport

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-526 — [CachingRendezvousId] is the anti-rotation invariant: `LiveRelayRendezvous.register` mints a FRESH epoch
 * (→ a new id) every call, so the reconnect loop must register ONCE and reuse the cached id — re-registering per
 * re-dial would rotate the id and strand the client's resolved id.
 */
class Cyp526CachingRendezvousIdTest {

    @Test
    fun registersOnce_reusesCachedId() = runBlocking {
        val calls = AtomicInteger(0)
        val c = CachingRendezvousId { "id-${calls.incrementAndGet()}" }
        assertEquals("id-1", c.get())
        assertEquals("id-1", c.get())
        assertEquals("id-1", c.get())
        assertEquals(1, calls.get(), "register called ONCE; the id is cached (never rotates across reuse)")
    }

    @Test
    fun failedRegister_notCached_retriedNextTime() = runBlocking {
        val calls = AtomicInteger(0)
        val c = CachingRendezvousId { if (calls.incrementAndGet() == 1) null else "id-ok" }
        assertNull(c.get(), "a failed register → null, and null is NOT cached")
        assertEquals("id-ok", c.get(), "the next get retries the register (a failed register is never cached)")
    }

    @Test
    fun invalidate_forcesReRegister() = runBlocking {
        val calls = AtomicInteger(0)
        val c = CachingRendezvousId { "id-${calls.incrementAndGet()}" }
        assertEquals("id-1", c.get())
        c.invalidate()
        assertEquals("id-2", c.get(), "invalidate forces a re-register (reserved for a future rendezvous-TTL)")
    }
}
