package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-484 (② Session-Lifetime & Revocation, Decision 4) — the authorized tunnel session has a **passive Op-Session-TTL**
 * (minutes) and can be **revoked immediately** (drop the live tunnel over the existing relay, no per-request phone-home).
 * [TunnelSessionRegistry] holds the active sessions; [Rr3AuthenticatedTunnelHandler] registers on authorize, arms the
 * TTL, and unregisters on end. INERT-safe: nothing here runs until a live relay tunnel is authorized.
 */
class Cyp484SessionLifetimeTest {

    /** A fake L2 tunnel whose receive() blocks until close() — so a bridge `while (receive()!=null){}` ends exactly
     *  when the session is torn down (by the TTL or a revocation). */
    private class SignalTunnel : ServerNoiseTunnel {
        val closed = CompletableDeferred<Unit>()
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? { closed.await(); return null }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun close() { closed.complete(Unit) }
    }

    private fun handler(registry: TunnelSessionRegistry, authorize: Boolean, ttlMs: Long) =
        Rr3AuthenticatedTunnelHandler(
            authorize = { authorize },
            bridge = { t -> while (t.receive() != null) { /* pump until the session is torn down */ } },
            registry = registry,
            operatorId = OP,
            sessionTtlMs = ttlMs,
        )

    @Test
    fun opSessionTtl_tearsDownTunnel_afterTtl_andUnregisters() = runBlocking {
        val registry = TunnelSessionRegistry()
        val tunnel = SignalTunnel()
        val job = launch { handler(registry, authorize = true, ttlMs = 100).handle(tunnel) }
        withTimeout(3_000) { tunnel.closed.await() }
        assertTrue(tunnel.closed.isCompleted, "the passive Op-Session-TTL tears the tunnel down when it elapses")
        job.join()
        assertEquals(0, registry.activeCount(), "the session is unregistered after teardown")
    }

    @Test
    fun revocation_immediatelyDropsActiveTunnel_noPhoneHome() = runBlocking {
        val registry = TunnelSessionRegistry()
        val tunnel = SignalTunnel()
        val job = launch { handler(registry, authorize = true, ttlMs = 60_000).handle(tunnel) }
        withTimeout(3_000) { while (registry.activeCount() == 0) delay(10) } // wait for the session to register
        assertEquals(1, registry.activeCount())

        // ★ Decision 4: revocation is a LOCAL registry call (no CP round-trip) that drops the live tunnel at once.
        assertEquals(1, registry.revokeOperator(OP), "revocation tears down the 1 active session immediately")
        withTimeout(3_000) { tunnel.closed.await() }
        assertTrue(tunnel.closed.isCompleted, "the live tunnel is dropped by the revocation, before the TTL")
        job.join()
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun rejectedTunnel_closed_neverRegistered() = runBlocking {
        val registry = TunnelSessionRegistry()
        val tunnel = SignalTunnel()
        handler(registry, authorize = false, ttlMs = 60_000).handle(tunnel)
        assertTrue(tunnel.closed.isCompleted, "a rejected tunnel is closed")
        assertEquals(0, registry.activeCount(), "a rejected tunnel is never registered — there is no session to revoke")
    }

    @Test
    fun registry_revokeOperator_closesOnlyMatching_idempotent() {
        val registry = TunnelSessionRegistry()
        var a1 = false
        var a2 = false
        var b = false
        registry.register("op-A") { a1 = true }
        registry.register("op-A") { a2 = true }
        registry.register("op-B") { b = true }
        assertEquals(3, registry.activeCount())

        assertEquals(2, registry.revokeOperator("op-A"), "both op-A sessions torn down")
        assertTrue(a1 && a2, "each op-A close-handle fired")
        assertFalse(b, "op-B is untouched (revocation is per-operator)")
        assertEquals(1, registry.activeCount())
        assertEquals(0, registry.revokeOperator("op-A"), "idempotent — nothing left for op-A")
        assertEquals(0, registry.revokeOperator("op-unknown"), "revoking an unknown operator tears down nothing")
    }

    @Test
    fun registry_unregister_removesSession_fromRevocation() {
        val registry = TunnelSessionRegistry()
        var closed = false
        val id = registry.register("op-1") { closed = true }
        assertEquals(1, registry.activeCount())
        registry.unregister(id)
        assertEquals(0, registry.activeCount())
        assertEquals(0, registry.revokeOperator("op-1"), "an unregistered (ended) session is not torn down")
        assertFalse(closed, "unregister does not fire the close-handle")
    }

    private companion object {
        const val OP = "op-1"
    }
}
