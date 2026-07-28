package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-882a (S-Fed §9.3 foundation, DARK) — the tunnel session is bound to the operatorId the tunnel ACTUALLY
 * authenticated as (`authorize`'s returned CpJwt `sub`), NOT a static wiring constant. This is the foundation the
 * §9.3 tunnel↔credential enforcement (CYP-882b) builds on. Everything here is the handler+registry seam against a
 * stub authorize — no network, no arming.
 */
class Cyp882aTunnelOperatorBindingTest {

    /** A tunnel that holds open until `close()`, so the session registers and can be inspected before teardown. */
    private class HoldTunnel : ServerNoiseTunnel {
        override val handshakeHash = ByteArray(32)
        private val closed = CompletableDeferred<Unit>()
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? { closed.await(); return null } // blocks until closed
        override suspend fun close() { closed.complete(Unit) }
    }

    private fun handler(registry: TunnelSessionRegistry, authorize: suspend (ServerNoiseTunnel) -> String?) =
        Rr3AuthenticatedTunnelHandler(
            authorize = authorize,
            bridge = { t -> while (t.receive() != null) { /* pump until torn down */ } },
            registry = registry,
            sessionTtlMs = 60_000L,
        )

    /**
     * ★ CENTRAL TOOTH — the session is registered under the AUTHENTICATED operatorId, so a revocation for THAT id
     * tears it down and a revocation for any OTHER id does not. **Mutation:** bind the session to a static/wrong id
     * (e.g. revert the handler to register a constant) → `revokeOperator("op-authenticated")` returns 0 → reds.
     */
    @Test
    fun session_isBoundToAuthenticatedOperatorId() = runBlocking {
        val registry = TunnelSessionRegistry()
        val tunnel = HoldTunnel()
        val job = launch { handler(registry) { "op-authenticated" }.handle(tunnel) }
        withTimeout(3_000) { while (registry.activeCount() == 0) delay(10) } // wait for register
        assertEquals(0, registry.revokeOperator("some-other-op"), "the session is NOT bound to a wrong/static operatorId")
        assertEquals(1, registry.revokeOperator("op-authenticated"), "the session IS bound to the authenticated operatorId")
        job.join()
    }

    /** A denied tunnel (authorize → null) registers nothing — fail-closed, never bridged. */
    @Test
    fun deniedTunnel_registersNothing() = runBlocking {
        val registry = TunnelSessionRegistry()
        handler(registry) { null }.handle(HoldTunnel()) // returns immediately: denied → close, no bridge, no register
        assertEquals(0, registry.activeCount())
    }

    /** Two DIFFERENT authenticated operators register as two distinct bindings — a revoke targets only its own id
     *  (the multi-operator property the §9.3 binding relies on; today the single-op floor keeps it to one, but the
     *  binding is per-authenticated-id by construction). */
    @Test
    fun distinctAuthenticatedOperators_bindSeparately() = runBlocking {
        val registry = TunnelSessionRegistry()
        val jobA = launch { handler(registry) { "op-A" }.handle(HoldTunnel()) }
        val jobB = launch { handler(registry) { "op-B" }.handle(HoldTunnel()) }
        withTimeout(3_000) { while (registry.activeCount() < 2) delay(10) }
        assertEquals(1, registry.revokeOperator("op-A"), "revoking op-A tears down only op-A's tunnel")
        assertEquals(1, registry.activeCount(), "op-B's tunnel is untouched by op-A's revoke")
        assertEquals(1, registry.revokeOperator("op-B"))
        jobA.join(); jobB.join()
    }
}
