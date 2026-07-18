package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-528 — the clean-tunnel-end re-dial path (CYP-526) must not `delay(0)` tight-spin when tunnels end near-instantly
 * (a churn: rapid connect/disconnect, or terminate/bridge returning without a real session). A MIN-INTERVAL floor throttles
 * ONLY the clean-end path; a real session (held ≥ floor) still re-dials immediately, and the FAILURE path is unchanged.
 */
class Cyp528ChurnFloorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeRelayChannel : ServerRelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTunnel : ServerNoiseTunnel {
        override val handshakeHash = ByteArray(32)
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTerminator : ServerNoiseTerminator {
        override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel = FakeTunnel()
    }
    private fun countingDialer(dials: AtomicInteger) = object : RelayDialer {
        override suspend fun dial(relayUrl: String): ServerRelayChannel { dials.incrementAndGet(); return FakeRelayChannel() }
    }

    @Test
    fun backToBackCleanEnds_areFloored_notTightLoop() = runBlocking {
        val delays = CopyOnWriteArrayList<Long>()
        val dials = AtomicInteger(0)
        // A FIXED clock → each dial→bridge cycle measures elapsed ≈ 0 (a near-instant tunnel-end / churn). The recording
        // sleeper observes the computed re-dial delay without real waiting; the handler returns immediately (clean end).
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), countingDialer(dials), FakeTerminator(), { }, scope,
            backoffMs = { 0L }, cleanEndFloorMs = 500L, nowMs = { 1_000L }, sleep = { delays.add(it) }, reDialJitterMs = { 300L },
        )
        c.start()
        withTimeout(5_000) { while (dials.get() < 3) delay(10) }
        c.stop()
        assertTrue(delays.take(3).all { it >= 500L }, "back-to-back clean-ends are floored to >= cleanEndFloorMs, never delay(0) tight-loop: ${delays.toList()}")
    }

    @Test
    fun cleanEnd_heldBeyondFloor_reDialsAfterJitteredFloor_notInstant_CYP528b() = runBlocking {
        val delays = CopyOnWriteArrayList<Long>()
        val dials = AtomicInteger(0)
        // A clock that advances 10s per read → every cycle's elapsed = 10s >> the 500ms floor → the CYP-528 floor term
        // yields 0. PRE-CYP-528b this re-dialed at 0ms — under a burst of simultaneous held-≥-floor clean-ends (the
        // dogfood unconsumed-tunnel churn) that was a synchronized 0-5ms thundering herd (the dominant amplifier). NOW
        // the JITTERED floor caps the INSTANT path: even a held-≥-floor clean-end waits the jitter, never 0.
        val clk = AtomicLong(0)
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), countingDialer(dials), FakeTerminator(), { }, scope,
            backoffMs = { 0L }, cleanEndFloorMs = 500L, nowMs = { clk.addAndGet(10_000L) }, sleep = { delays.add(it) }, reDialJitterMs = { 300L },
        )
        c.start()
        withTimeout(5_000) { while (dials.get() < 3) delay(10) }
        c.stop()
        // Mutation (remove `maxOf(..., reDialJitterMs())` on the clean-end path) → wait = 0 → this REDs.
        assertTrue(delays.take(3).all { it == 300L }, "held-≥-floor clean-end re-dials after the JITTERED floor (300), NOT the 0ms instant hammer: ${delays.toList()}")
    }

    private class ThrowingTerminator : ServerNoiseTerminator {
        override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel = throw RuntimeException("NK handshake failed")
    }

    @Test
    fun failurePath_jittersTheDeterministicBackoff_deSyncsHerd_CYP528b() = runBlocking {
        val delays = CopyOnWriteArrayList<Long>()
        val dials = AtomicInteger(0)
        // The dial succeeds but the NK handshake THROWS → attempt++ → the FAILURE path. Its backoff
        // (`AdmissionRetry.delayForAttempt`) is a FIXED curve identical for every responder → re-syncs the herd. CYP-528b
        // adds the additive jitter: wait = backoff + jitter, so N failing responders don't re-dial in lock-step. Here
        // `backoffMs` is a CONSTANT {1000}, so it isolates the JITTER term (the attempt value doesn't matter); the
        // escalation-across-attempts is covered separately by [repeatedHandshakeFail_escalatesBackoff…_CYP606].
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), countingDialer(dials), ThrowingTerminator(), { }, scope,
            backoffMs = { 1_000L }, cleanEndFloorMs = 500L, nowMs = { 1_000L }, sleep = { delays.add(it) }, reDialJitterMs = { 300L },
        )
        c.start()
        withTimeout(5_000) { while (dials.get() < 2) delay(10) }
        c.stop()
        // Mutation (remove `+ reDialJitterMs()` on the failure path) → wait = 1000 → this REDs.
        assertTrue(delays.take(2).all { it == 1_300L }, "the failure backoff is JITTERED (backoff 1000 + jitter 300 = 1300) — de-syncs the deterministic curve: ${delays.toList()}")
    }

    @Test
    fun repeatedHandshakeFail_escalatesBackoff_notPinnedAtAttempt1_CYP606() = runBlocking {
        val delays = CopyOnWriteArrayList<Long>()
        val dials = AtomicInteger(0)
        // CYP-606 (Backend2 amplifier ⑥): a tunnel that DIALS ok but whose NK handshake keeps THROWING must ESCALATE its
        // backoff (attempt 1→2→3…), not pin at backoffMs(1). PRE-fix the `attempt = 0` reset sat at dial-success — before
        // terminate() — so every cycle reset attempt to 0 → terminate throws → attempt=1 → PINNED at backoffMs(1) forever
        // (a tight 250ms re-dial loop that never backs off). An attempt-DEPENDENT backoff (`a -> a*1000`) makes the pin
        // vs. escalation observable; jitter=0 isolates the escalation term.
        val c = NoiseRelayConnector(
            RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), countingDialer(dials), ThrowingTerminator(), { }, scope,
            backoffMs = { a -> a * 1_000L }, cleanEndFloorMs = 500L, nowMs = { 1_000L }, sleep = { delays.add(it) }, reDialJitterMs = { 0L },
        )
        c.start()
        withTimeout(5_000) { while (dials.get() < 3) delay(10) }
        c.stop()
        // Mutation (move the `attempt = 0` reset back to dial-success, i.e. before terminate()) → attempt pins at 1 →
        // delays become [1000, 1000, 1000] → this REDs. The fix (reset only after tunnelHandler SERVED) → escalation.
        assertTrue(
            delays.take(3) == listOf(1_000L, 2_000L, 3_000L),
            "repeated dial-ok/handshake-fail ESCALATES the backoff (1000,2000,3000), not pinned at backoffMs(1): ${delays.toList()}",
        )
    }
}
