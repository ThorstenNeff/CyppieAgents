package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-503 (P4b) — the `min(ticket-exp, op-session-TTL)` **single source**. The hub tunnel-cap fires at exactly
 * [RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS] — the SAME constant [com.tneff.cyppieagents.controlplane.LiveHubTicketMinter]
 * stamps into the ticket `exp` (`Cyp503LiveHubTicketMinterTest.p4a_ticketTtl_isSingleSourced`). Virtual clock (the
 * CYP-492 recipe: `runTest` + `advanceTimeBy` + `runCurrent`) brackets the boundary EXACTLY — alive at `const-1`,
 * torn down at `const+1` — so neither leg of the `min` can drift from the one constant.
 *
 * ★ Mutation: drop the handler's `delay(sessionTtlMs)` → the tunnel is never torn down → the `torn-down at const+1`
 *   assert reds (the same guard CYP-484/492 proves, here bound to the shared activation constant).
 */
class Cyp503TtlSingleSourceTest {

    /** A fake L2 whose receive() blocks until close() — so the bridge pump ends exactly when the TTL tears it down. */
    private class SignalTunnel : ServerNoiseTunnel {
        val closed = CompletableDeferred<Unit>()
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? { closed.await(); return null }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun close() { closed.complete(Unit) }
    }

    @Test
    fun hubCap_firesAtTheSingleSourcedOpSessionTtl_virtualClock() = runTest {
        val ttl = RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS
        val registry = TunnelSessionRegistry()
        val tunnel = SignalTunnel()
        val handler = Rr3AuthenticatedTunnelHandler(
            authorize = { true },
            bridge = { t -> while (t.receive() != null) { /* pump until the TTL tears the session down */ } },
            registry = registry,
            operatorId = "op-1",
            sessionTtlMs = ttl, // ← the SAME single source the ticket exp derives from
        )
        val job = launch { handler.handle(tunnel) }
        runCurrent() // authorize, register, arm the TTL
        advanceTimeBy(ttl - 1); runCurrent()
        assertFalse(tunnel.closed.isCompleted, "alive BEFORE the single-sourced Op-Session-TTL")
        advanceTimeBy(2); runCurrent()
        assertTrue(tunnel.closed.isCompleted, "torn down EXACTLY at the single-sourced Op-Session-TTL (min upper leg)")
        job.join()
    }
}
