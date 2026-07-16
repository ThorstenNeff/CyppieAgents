package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-633 — the **runtime concurrency tooth** the compile-only merge gate cannot catch. It proves, with REAL loopback
 * sockets + REAL blocking reads + REAL dispatchers, the thread-model root: the bridge's long-lived blocking socket
 * reads park their dispatcher threads for the whole life of a stream, so on a **capped shared** dispatcher (this is
 * `Dispatchers.IO`, cap 64, at ~32 streams = 2N=64) a further bridge op STARVES — while on the dedicated **elastic**
 * `BridgeBlocking.dispatcher` (the fix) the same op PROGRESSES. Deterministic (a small injected cap, box-independent)
 * and **mutation-proven**: change the production `BridgeBlocking.dispatcher` from the elastic cached pool to a
 * capped/fixed pool and the elastic assertion reds. The production-scale 2N=64 repro over separate JVMs is the
 * complementary Windows/e2e check.
 *
 * ★ Two hard-won teardown/probe rules (an earlier draft hung the JVM):
 *  1. The starvation probe is a **cancellable marker-await**, NOT a `withTimeoutOrNull` around a blocking socket op:
 *     the timeout can only preempt at a suspension point, and a queued/blocked `withContext(dispatcher){ write }` never
 *     yields → the timeout can't fire → `runBlocking` hangs forever. Awaiting a [CompletableDeferred] the marker task
 *     completes IS a clean suspension point, so the deadline always fires and the method always returns.
 *  2. A blocking `input.read()` is **uninterruptible** — neither cancellation nor `dispatcher.close()` unparks it. So
 *     every pool here uses **daemon** threads AND every client socket is tracked and [RealBridgeSocket.reset] in
 *     teardown (async-close makes the parked read throw and unpark). Both: no hang, whatever the OS timing.
 */
class Cyp633BridgeBlockingPoolTest {

    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.asReversed().forEach { runCatching { it() } } }

    /** A fixed pool of **daemon** threads — a deterministic stand-in for `Dispatchers.IO`'s fixed cap, but daemon so a
     *  still-parked blocking read can never keep the JVM alive past the test. Registered for shutdown. */
    private fun cappedDaemonPool(n: Int): ExecutorCoroutineDispatcher {
        val seq = AtomicInteger()
        val d = Executors.newFixedThreadPool(n) { r ->
            Thread(r, "cyp633-capped-${seq.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        cleanups += { d.close() }
        return d
    }

    /** Create a real loopback [RealBridgeSocket] AND register it for [RealBridgeSocket.reset] in teardown. reset()
     *  closes the client socket, which makes any thread parked in this socket's `read()` throw and unpark. */
    private fun trackedSocket(port: Int, d: CoroutineDispatcher): RealBridgeSocket =
        RealBridgeSocket("127.0.0.1", port, d).also { s -> cleanups += { runCatching { s.reset() } } }

    /** Occupy [d] with [holders] long-lived blocking socket reads (each parks its thread for the whole test, exactly
     *  like a stream awaiting a slow route / a live WS). Returns after giving them time to actually park. */
    private fun saturate(port: Int, d: CoroutineDispatcher, scope: CoroutineScope, holders: Int) {
        repeat(holders) { val s = trackedSocket(port, d); scope.launch(d) { runCatching { s.read(ByteArray(1)) } } }
        Thread.sleep(400) // let the reads get scheduled onto their threads and block
    }

    /** Submit a trivial **marker** task to [d] and report whether it gets to RUN within [deadlineMs]. The marker only
     *  needs a free thread to complete a [CompletableDeferred]; awaiting that Deferred is a **cleanly cancellable**
     *  suspension point, so the deadline can always fire (unlike wrapping a blocking socket op). Saturated fixed pool →
     *  marker never scheduled → false; elastic pool → grows a thread → true. */
    private suspend fun markerRunsWithin(d: CoroutineDispatcher, scope: CoroutineScope, deadlineMs: Long): Boolean {
        val ran = CompletableDeferred<Unit>()
        scope.launch(d) { ran.complete(Unit) }
        return withTimeoutOrNull(deadlineMs) { ran.await() } != null
    }

    /** A loopback server that accepts connections and NEVER writes → a client `read()` blocks indefinitely (parks its
     *  dispatcher thread), while a small `write()` returns. This is the mechanism that starves a capped dispatcher. */
    private fun silentServer(): Int {
        val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        cleanups += { runCatching { server.close() } }
        val accepted = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        cleanups += { synchronized(accepted) { accepted.forEach { runCatching { it.close() } } } }
        Thread({ while (!server.isClosed) runCatching { accepted += server.accept() }.getOrElse { return@Thread } },
            "cyp633-silent-accept").apply { isDaemon = true; start() }
        return server.localPort
    }

    @Test
    fun blockingBridgeReads_starveACappedSharedDispatcher_butProgressOnTheDedicatedElasticPool() = runBlocking {
        val port = silentServer()
        val cap = 8
        val scope = CoroutineScope(SupervisorJob())
        cleanups += { scope.cancel() }

        // ── BUG: a SHARED CAPPED dispatcher (deterministic stand-in for Dispatchers.IO's fixed cap). Every thread held
        //    by a long-lived blocking bridge read → a further bridge op cannot get a thread → STARVES. ──
        val capped = cappedDaemonPool(cap)
        saturate(port, capped, scope, holders = cap)
        assertFalse(
            markerRunsWithin(capped, scope, deadlineMs = 1500),
            "CYP-633 bug: a bridge op STARVES on a capped shared dispatcher whose threads are all held by long-lived blocking reads (= Dispatchers.IO at 2N)",
        )

        // ── FIX: the dedicated ELASTIC pool (production default, BridgeBlocking.dispatcher). The SAME saturation — far
        //    beyond `cap` concurrent long-lived blocking reads — does NOT starve: the pool grows, the op progresses. ──
        val elastic = BridgeBlocking.dispatcher
        saturate(port, elastic, scope, holders = cap * 3)
        assertTrue(
            markerRunsWithin(elastic, scope, deadlineMs = 1500),
            "CYP-633 fix: on the dedicated elastic BridgeBlocking.dispatcher a bridge op PROGRESSES under 3×cap long-lived blocking reads (mutation: make BridgeBlocking.dispatcher a capped/fixed pool → this reds)",
        )
    }
}
