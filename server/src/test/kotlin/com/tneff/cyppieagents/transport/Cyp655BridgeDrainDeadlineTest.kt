package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-655 — the **bridge drain-deadline OUTCOME tooth**, built BEFORE the fix so it pins the *property*, not the fix.
 *
 * The leak (proven, probes A/B/C/E + F1/F2): [LoopbackBridge]'s down-pump parks in `socket.read()`. On a CLEAN up-end
 * the bridge FIN-half-closes the loopback ([BridgeSocket.closeGraceful] = `shutdownOutput`, CYP-609) — the READ side
 * stays open. For an idle-never-emitting hub feed (a stopped agent's `/ws/lifecycle` | `/ws/busy-state`) the hub then
 * sends nothing and never EOFs, so the parked read is **never freed** → the down-pump (and its blocking-dispatcher
 * thread) leaks, permanently and idle-dependently. `pingPeriodMillis` and `SO_TIMEOUT` are REFUTED fixes (F1/F2: the
 * pinger stops after incoming-EOF; SO_TIMEOUT would kill live idle feeds). The agreed fix is a **tunnel-closed-keyed
 * drain-deadline**: once the tunnel/up-side has closed, give the down-pump a bounded window, then force-close (RST) so
 * the parked read is freed — WITHOUT touching a still-live idle feed.
 *
 * This tooth pins BOTH directions of that property:
 *  • **①** ([tunnelClosed_idleHub_parkedReadFreedWithinDeadline_leakClosed]) — leak CLOSED: after the tunnel closes, a
 *    parked idle read is freed within a bounded deadline. Was **RED on develop** (no drain-deadline); the
 *    tunnel-closed-keyed drain-deadline fix ([LoopbackBridge] `drainDeadlineMs`, default 1s) makes it GREEN — now
 *    un-ignored, a live regression guard.
 *  • **②** ([tunnelLive_idleHub_socketNotForceClosed_featureIntact]) — feature INTACT: a LIVE-but-idle feed's socket is
 *    NOT force-closed. GREEN on develop and under the correct tunnel-closed-keyed fix; **RED under a read-idle-keyed
 *    deadline** (the mutation the PO will run — the trap the whole tooth exists to prevent).
 *  • **ANCHOR** ([anchor_abruptUpEnd_resetFreesParkedDownRead_bridgeCompletes]) — the positive control: proves the
 *    harness can OBSERVE a parked read being freed at all (the known-positive RST path). Without it ①/② are untrustable.
 *  • **GRIP** ([grip_readIdleForceClose_reddensTheFeatureTooth]) — proves ② is non-vacuous: a read-idle-keyed force-close
 *    (a faithful stand-in for the mutation) DOES trip ②'s oracle.
 *
 * WHERE THE TOOTH STOPS (scope, stated for the PO): it pins the teardown *logic* — WHICH end-kind frees the parked read
 * and whether a live feed is spared — at the [bridge] boundary via an injected [BridgeSocket] whose half-close semantics
 * (FIN = read stays parked, RST = read freed) mirror the wire exactly. It does NOT re-measure the real thread being
 * held (that is the dispatcher's job, and dispatcher-agnostic to the property), nor does it run the REAL read-idle
 * mutation against the REAL fix (the fix does not exist yet — the PO runs that when it lands; GRIP proves the oracle
 * grips that behavior today). ① is the acceptance red the fix must turn green.
 */
class Cyp655BridgeDrainDeadlineTest {

    /** ①: the fix must free a parked idle read within this human-tolerable bound after the tunnel closes. The fix's
     *  chosen drain-deadline default MUST be < this ceiling (a stopped-feed cleanup well under 3s is reasonable). */
    private val leakCloseCeilingMs = 3_000L

    /** ②/GRIP: a LIVE-but-idle feed must NOT be force-closed for at least this window (it may live forever). */
    private val liveSurviveWindowMs = 2_000L

    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.forEach { runCatching { it() } }; cleanups.clear() }

    // ---- controllable fake L2 tunnel — end-kind mirrors Cyp458's ControllableTunnel (clean-EOF vs abrupt-fault) ----
    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile private var faultOnDrain = false
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? {
            val r = inbound.receiveCatching() // suspends while OPEN + empty ⇒ the tunnel stays LIVE (idle) until closed
            if (r.isClosed && faultOnDrain) throw RuntimeException("AEAD decrypt-fail / tamper (abrupt L2 fault)")
            return r.getOrNull()
        }
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        /** CLEAN relay/client EOF → `receive()` returns null → the bridge FIN-half-closes (closeGraceful, CYP-609). */
        fun dropRelay() { inbound.close() }
        /** ABRUPT relay fault → `receive()` THROWS → the bridge RSTs (reset) — the known-positive teardown. */
        fun throwRelay() { faultOnDrain = true; inbound.close() }
        // Never dropped/thrown ⇒ receive() suspends forever ⇒ a LIVE idle tunnel.
    }

    /**
     * The loopback socket to an idle hub feed: the hub NEVER sends a byte and NEVER EOFs, so the down-pump's read()
     * parks. Faithful half-close semantics (the exact wire asymmetry the probes measured):
     *  • [closeGraceful] = FIN (`shutdownOutput`): the WRITE side closes, the READ side STAYS OPEN → read() stays parked.
     *  • [reset]         = RST (`close`, SO_LINGER 0): the read side dies → read() returns -1 → the down-pump is freed.
     * Only an RST frees a parked idle read; a FIN does not. The CYP-655 leak IS "the clean path FINs, so it is never freed".
     */
    private class IdleHubSocket : BridgeSocket {
        val downReadParked = CompletableDeferred<Unit>() // the down-pump entered read() and is parked (observability)
        val resetCalled = CompletableDeferred<Unit>()    // an RST / force-close was issued on this socket
        val finCalled = CompletableDeferred<Unit>()      // a graceful FIN (closeGraceful) was issued
        private val released = CompletableDeferred<Unit>() // trips read() → -1; ONLY an RST completes it (FIN does not)
        override val remote: InetAddress = InetAddress.getLoopbackAddress()
        override suspend fun write(bytes: ByteArray) { /* up-pump writes; irrelevant to the idle-read property */ }
        override suspend fun read(buf: ByteArray): Int {
            downReadParked.complete(Unit)
            released.await() // parks until an RST frees it — the idle hub never sends/EOFs, and a FIN leaves this open
            return -1
        }
        override fun reset() { resetCalled.complete(Unit); released.complete(Unit) } // RST → the parked read is freed
        override fun closeGraceful() { finCalled.complete(Unit) /* FIN: read side stays open → read STAYS parked */ }
    }

    private fun bridgeOver(sock: BridgeSocket) = LoopbackBridge(9, "127.0.0.1") { _, _ -> sock }

    @Test
    fun anchor_abruptUpEnd_resetFreesParkedDownRead_bridgeCompletes() = runBlocking {
        // ANCHOR / positive control for the whole apparatus: prove the harness can OBSERVE a parked down-read being
        // FREED and the bridge completing. The ABRUPT up-end path already RSTs (CYP-609) → the idle read is freed → both
        // pumps end → bridge() returns. If THIS were red, ①/② would be untrustworthy (the harness couldn't see a
        // freeing at all). This is the known positive that makes ①'s red and ②'s green meaningful.
        val sock = IdleHubSocket()
        val tunnel = ControllableTunnel()
        val completed = withTimeoutOrNull(leakCloseCeilingMs) {
            val job = launch(Dispatchers.IO) { bridgeOver(sock).bridge(tunnel) }
            sock.downReadParked.await() // the down-pump is parked in read()
            tunnel.throwRelay()         // ABRUPT up-end → the bridge issues socket.reset() (RST) → frees the parked read
            job.join()                  // both pumps end → bridge() returns
            true
        }
        assertNotNull(completed, "ANCHOR: abrupt up-end → RST frees the parked idle read → bridge completes; the harness sees a freeing")
        assertTrue(sock.resetCalled.isCompleted, "ANCHOR: the freeing came via an RST (reset) — the known-positive teardown")
    }

    @Test
    // CYP-655: un-ignored — the tunnel-closed-keyed drain-deadline fix (LoopbackBridge.drainDeadlineMs, default 1s) has
    // landed, so this acceptance test is now GREEN and a live regression guard. (Was @Ignore'd + RED on develop
    // 1da14371: finCalled=true resetCalled=false, bridge parked >3s = the leak.)
    fun tunnelClosed_idleHub_parkedReadFreedWithinDeadline_leakClosed() = runBlocking {
        // ① DIRECTION ONE — the LEAK IS CLOSED. A CLEAN up-end (relay/client closed → receive() null → the bridge FIN-
        // half-closes the loopback, CYP-609) followed by an idle-never-emitting hub (a stopped agent's /ws/lifecycle):
        // the down-pump's read() is parked and the FIN does NOT free it (read side stays open). The DESIRED property is
        // that a tunnel-closed-keyed drain-deadline force-closes (RST) after a bounded wait → the parked read is freed →
        // bridge() completes. RED on current develop (no drain-deadline: the FIN leaves the read parked forever = the leak).
        val sock = IdleHubSocket()
        val tunnel = ControllableTunnel()
        // Launch the bridge in the OUTER scope so the deadline WAIT below cannot cancel it — a timeout-cancel would fire
        // the bridge's own finally→reset() and pollute the diagnostic (resetCalled flips true as an artifact of teardown,
        // not of the fix freeing the read). We observe the socket state at the deadline, THEN tear down explicitly.
        val job = launch(Dispatchers.IO) { bridgeOver(sock).bridge(tunnel) }
        cleanups += { job.cancel() }
        sock.downReadParked.await()
        tunnel.dropRelay() // CLEAN up-end → the bridge FIN-half-closes (closeGraceful); the read side stays open
        val completed = withTimeoutOrNull(leakCloseCeilingMs) { job.join(); true } // WITH fix: RST after drain → returns
        val finAtDeadline = sock.finCalled.isCompleted    // captured while the bridge is still parked (pre-cancel)
        val resetAtDeadline = sock.resetCalled.isCompleted
        job.cancel()
        assertTrue(
            completed != null,
            "①  leak-closed: after the tunnel closes, the parked idle down-read is freed within ${leakCloseCeilingMs}ms. " +
                "finCalled=$finAtDeadline resetCalled=$resetAtDeadline " +
                "(RED on current develop: the clean path FINs [expect finCalled=true] but nothing force-closes the read " +
                "[expect resetCalled=false] → the down-pump parks forever = the CYP-655 thread leak. The tunnel-closed-" +
                "keyed drain-deadline fix must turn this GREEN.)",
        )
    }

    @Test
    fun tunnelLive_idleHub_socketNotForceClosed_featureIntact() = runBlocking {
        // ② DIRECTION TWO — the FEATURE STAYS INTACT. A LIVE tunnel (never dropped/thrown → receive() suspends → the
        // up-side is open) carrying an idle-never-emitting hub feed (a live but silent /ws/lifecycle | /ws/busy-state):
        // the down-pump parks in read(), the up-pump parks in receive(). This is a legitimate long-lived silent feed and
        // it MUST survive — the socket is NOT force-closed. GREEN on develop AND under the correct tunnel-closed-keyed
        // fix (the tunnel never closed → the drain-deadline never arms). RED under a READ-IDLE-keyed deadline (the
        // mutation): it would force-close the idle-but-live socket → kill the live feed. This is the direction the
        // read-idle mutation flips — see grip_readIdleForceClose_reddensTheFeatureTooth.
        val sock = IdleHubSocket()
        val tunnel = ControllableTunnel() // stays LIVE — never dropRelay()/throwRelay()
        val job = launch(Dispatchers.IO) { bridgeOver(sock).bridge(tunnel) }
        cleanups += { job.cancel() }
        sock.downReadParked.await()
        val forceClosed = withTimeoutOrNull(liveSurviveWindowMs) { sock.resetCalled.await(); true }
        val resetDuringWindow = sock.resetCalled.isCompleted // capture BEFORE the teardown cancel (cancel() also RSTs)
        job.cancel()
        assertNull(
            forceClosed,
            "②  feature-intact: a LIVE-but-idle feed's socket is NOT force-closed within ${liveSurviveWindowMs}ms — the " +
                "long-lived silent /ws/lifecycle survives. resetCalled(duringWindow)=$resetDuringWindow (RED under a " +
                "read-idle-keyed deadline, which would force-close the idle-but-live socket and kill the feed).",
        )
    }

    @Test
    fun grip_readIdleForceClose_reddensTheFeatureTooth() = runBlocking {
        // GRIP for ② — prove the oracle is NON-VACUOUS and catches EXACTLY the read-idle mutation. Same LIVE-but-idle
        // setup as ②, but add a READ-IDLE watchdog: a faithful stand-in for the mutation the PO will run on the real fix
        // (a drain-deadline keyed on read-idleness, IGNORING the live tunnel). The watchdog force-closes (RST) the idle-
        // but-live socket after an idle deadline. The ②-oracle (withTimeoutOrNull { resetCalled.await() }) must now FIRE
        // (non-null) → ② would RED. Proves ②'s green is a real discrimination and flips on precisely the read-idle
        // keying. (The REAL mutation on the REAL fix is the PO's to run when the fix lands.)
        val sock = IdleHubSocket()
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { bridgeOver(sock).bridge(tunnel) }
        cleanups += { job.cancel() }
        sock.downReadParked.await()
        val readIdleDeadlineMs = 300L
        val watchdog = launch(Dispatchers.IO) { delay(readIdleDeadlineMs); sock.reset() } // ← read-idle-keyed force-close (the mutation)
        cleanups += { watchdog.cancel() }
        val forceClosed = withTimeoutOrNull(liveSurviveWindowMs) { sock.resetCalled.await(); true }
        job.cancel(); watchdog.cancel()
        assertTrue(
            forceClosed != null,
            "GRIP: a read-idle-keyed force-close (the mutation) DOES trip the feature-tooth oracle → ② reddens. " +
                "Confirms ② is non-vacuous and flips on exactly the read-idle keying the PO will mutate.",
        )
    }
}
