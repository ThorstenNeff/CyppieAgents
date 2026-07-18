package com.tneff.cyppieagents.net.hub.noise

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tunnel-warmth incident (NK-handshake abort attribution): a mid-NK-handshake **cancellation** — the batch-teardown
 * (`RemoteTunnelHubTransport`/`PooledTunnelSource` close) cancelling the in-flight `dial()` while `connect()` awaits
 * the NK message 2 — MUST propagate as [CancellationException], NOT be wrapped as a `NoiseHandshakeException`.
 * Wrapping it (the `catch (e: Exception)`) violated structured concurrency AND mis-reported a client teardown-cancel
 * as a genuine handshake failure, hiding the real (ii) client-cancel trigger behind Backend2's "failure-driven" churn.
 */
class NoiseJavaClientTransportCancelTest {

    /** A relay whose [send] records + returns, and whose [receive] suspends forever — so `connect()` writes NK msg1
     *  then blocks awaiting msg2, exactly where a mid-NK teardown cancellation lands. */
    private class SuspendingRelay : RelayChannel {
        val sent = mutableListOf<ByteArray>()
        override suspend fun send(frame: ByteArray) { sent.add(frame) }
        override suspend fun receive(): ByteArray? = awaitCancellation()
        override suspend fun close() {}
    }

    @Test
    fun midHandshakeCancellation_propagatesCancellation_notWrappedAsHandshakeFailure() = runTest {
        val transport = NoiseJavaClientTransport()
        val relay = SuspendingRelay()
        val hubStatic = ByteArray(32) { (it + 1).toByte() } // any 32B X25519 pin — NK writes msg1, then awaits msg2
        var caught: Throwable? = null
        val job = launch {
            try {
                transport.connect(hubStatic, relay, ByteArray(0))
            } catch (t: Throwable) {
                caught = t
            }
        }
        runCurrent() // let connect() write NK msg1 (relay.send) + reach the suspending relay.receive()
        assertTrue(relay.sent.isNotEmpty(), "precondition: the handshake reached the awaiting relay.receive()")
        job.cancelAndJoin()
        // With the fix (catch CancellationException → throw c): the cancellation propagates unchanged.
        // Reddening mutation: drop the CancellationException rethrow ⇒ the generic `catch (e: Exception)` wraps it as
        // NoiseHandshakeException ⇒ `caught` is NOT a CancellationException ⇒ RED.
        assertTrue(
            caught is CancellationException,
            "a mid-NK cancellation must propagate as CancellationException, not be wrapped as a handshake failure (was ${caught?.let { it::class.simpleName }})",
        )
    }
}
