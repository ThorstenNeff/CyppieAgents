package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-458 (S2) — the [NoiseRelayConnector] teeth. **AC1 (RR5, outbound-only):** the connector only ever DIALS the
 * relay via the [RelayDialer] seam (a fake here) — it binds no listener for the tunnel — and when disabled it is
 * INERT (no dial at all). Also the wiring order (dial → terminate → hand the L2 tunnel to the loopback-bridge
 * handler) and fail-closed teardown of the L0 channel on a handshake failure.
 */
class Cyp458RelayConnectorTest {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> /* expected fail-closed throw */ },
    )
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeRelayChannel : ServerRelayChannel {
        @Volatile var closed = false
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }
    private class FakeTunnel : ServerNoiseTunnel {
        override val handshakeHash = ByteArray(32)
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTerminator(private val tunnel: ServerNoiseTunnel) : ServerNoiseTerminator {
        @Volatile var calls = 0
        override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel { calls++; return tunnel }
    }
    private class FailingTerminator : ServerNoiseTerminator {
        // A generic failure — the connector's fail-closed teardown catches any Exception (not tied to CYP-457's type).
        override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel =
            throw IllegalStateException("handshake failed")
    }
    private class RecordingDialer(private val ch: ServerRelayChannel) : RelayDialer {
        val dialed = CopyOnWriteArrayList<String>()
        override suspend fun dial(relayUrl: String): ServerRelayChannel { dialed += relayUrl; return ch }
    }

    @Test
    fun disabledConfig_neverDials_inert() = runBlocking {
        val dialer = RecordingDialer(FakeRelayChannel())
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = false, relayUrl = "wss://relay/x"),
            dialer, FakeTerminator(FakeTunnel()), { }, scope,
        )
        c.start()
        c.stop()
        assertTrue(dialer.dialed.isEmpty(), "disabled → INERT: never dials the relay (no outbound, and certainly no listener)")
    }

    @Test
    fun enabled_dialsOutboundOnce_terminates_thenHandsTunnelToBridge() = runBlocking {
        val tunnel = FakeTunnel()
        val dialer = RecordingDialer(FakeRelayChannel())
        val terminator = FakeTerminator(tunnel)
        val handled = CompletableDeferred<ServerNoiseTunnel>()
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/rzv-abc"),
            dialer, terminator, { handled.complete(it) }, scope,
        )
        c.start()
        val got = withTimeout(5_000) { handled.await() }
        assertEquals(listOf("wss://relay/rzv-abc"), dialer.dialed.toList(), "AC1: exactly one OUTBOUND dial to the relay")
        assertEquals(1, terminator.calls, "the NK terminator ran on the dialed L0 channel")
        assertSame(tunnel, got, "the terminated L2 tunnel is handed straight to the loopback-bridge handler")
        c.stop()
    }

    @Test
    fun handshakeFailure_closesL0Channel_failClosed() = runBlocking {
        val relay = FakeRelayChannel()
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"),
            RecordingDialer(relay), FailingTerminator(), { }, scope,
        )
        c.start()
        withTimeout(5_000) { while (!relay.closed) delay(20) }
        assertTrue(relay.closed, "a failed NK handshake tears the L0 relay channel down (fail-closed)")
        c.stop()
    }
}
