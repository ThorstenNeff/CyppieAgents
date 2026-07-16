package com.tneff.cyppieagents.net.hub

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
 * CYP-647 — the **client mirror** of the server's `Cyp633BridgeBlockingPoolTest`. Proves, with REAL loopback sockets +
 * REAL blocking reads + REAL dispatchers, the same thread-model root on the CLIENT bridge: a per-connection blocking
 * [RealBridgeConn.read] (`input.read()`) parks its dispatcher thread for the whole life of a workspace WS, so on a
 * **capped shared** dispatcher (= `Dispatchers.IO`, cap 64, at ~32 live WS = exhausted) a further bridge op STARVES —
 * while on the dedicated **elastic** [ClientBridgeBlocking.dispatcher] (the fix) the same op PROGRESSES. Deterministic
 * (a small injected cap, box-independent) and **mutation-proven**: make [ClientBridgeBlocking.dispatcher] a capped/fixed
 * pool and the elastic assertion reds. The production-scale repro over N real tunnels is the complementary e2e check.
 *
 * ★ Same two hard-won rules as the server tooth (an earlier server draft hung the JVM): the probe is a **cancellable
 * marker-await**, NOT a `withTimeoutOrNull` around a blocking socket op (a queued/blocked dispatcher op never yields →
 * the timeout can't fire → `runBlocking` hangs); and every pool is **daemon** + every socket is tracked and
 * [RealBridgeConn.reset] in teardown (async-close unparks the uninterruptible read). Both: no hang, whatever the timing.
 */
class Cyp647ClientBridgeBlockingPoolTest {

    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.asReversed().forEach { runCatching { it() } } }

    /** A fixed pool of **daemon** threads — a deterministic stand-in for `Dispatchers.IO`'s fixed cap, but daemon so a
     *  still-parked blocking read can never keep the JVM alive past the test. Registered for shutdown. */
    private fun cappedDaemonPool(n: Int): ExecutorCoroutineDispatcher {
        val seq = AtomicInteger()
        val d = Executors.newFixedThreadPool(n) { r ->
            Thread(r, "cyp647-capped-${seq.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        cleanups += { d.close() }
        return d
    }

    /** Connect a real loopback socket to [port], wrap it in a [RealBridgeConn] on [d], AND register it for
     *  [RealBridgeConn.reset] in teardown (async-close unparks any thread blocked in this conn's `read()`). */
    private fun trackedConn(port: Int, d: CoroutineDispatcher): RealBridgeConn {
        val conn = RealBridgeConn(Socket("127.0.0.1", port), d)
        cleanups += { runCatching { conn.reset() } }
        return conn
    }

    /** Occupy [d] with [holders] long-lived blocking [RealBridgeConn.read]s (each parks its thread for the whole test,
     *  exactly like a live workspace WS awaiting request bytes). Returns after giving them time to actually park. */
    private fun saturate(port: Int, d: CoroutineDispatcher, scope: CoroutineScope, holders: Int) {
        repeat(holders) { val c = trackedConn(port, d); scope.launch(d) { runCatching { c.read(ByteArray(1)) } } }
        Thread.sleep(400)
    }

    /** Submit a trivial **marker** task to [d] and report whether it gets to RUN within [deadlineMs]. Awaiting the
     *  [CompletableDeferred] the marker completes is a cleanly cancellable suspension point, so the deadline can always
     *  fire. Saturated fixed pool → marker never scheduled → false; elastic pool → grows a thread → true. */
    private suspend fun markerRunsWithin(d: CoroutineDispatcher, scope: CoroutineScope, deadlineMs: Long): Boolean {
        val ran = CompletableDeferred<Unit>()
        scope.launch(d) { ran.complete(Unit) }
        return withTimeoutOrNull(deadlineMs) { ran.await() } != null
    }

    /** A loopback server that accepts connections and NEVER writes → a client `read()` blocks indefinitely (parks its
     *  dispatcher thread). This is the mechanism that starves a capped dispatcher. */
    private fun silentServer(): Int {
        val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        cleanups += { runCatching { server.close() } }
        val accepted = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        cleanups += { synchronized(accepted) { accepted.forEach { runCatching { it.close() } } } }
        Thread({ while (!server.isClosed) runCatching { accepted += server.accept() }.getOrElse { return@Thread } },
            "cyp647-silent-accept").apply { isDaemon = true; start() }
        return server.localPort
    }

    @Test
    fun clientBridgeReads_starveACappedSharedDispatcher_butProgressOnTheDedicatedElasticPool() = runBlocking {
        val port = silentServer()
        val cap = 8
        val scope = CoroutineScope(SupervisorJob())
        cleanups += { scope.cancel() }

        // ── BUG: a SHARED CAPPED dispatcher (stand-in for Dispatchers.IO's cap). Every thread held by a long-lived
        //    blocking client-bridge read → a further bridge op cannot get a thread → STARVES. ──
        val capped = cappedDaemonPool(cap)
        saturate(port, capped, scope, holders = cap)
        assertFalse(
            markerRunsWithin(capped, scope, deadlineMs = 1500),
            "CYP-647 bug: a client bridge op STARVES on a capped shared dispatcher whose threads are all held by long-lived blocking reads (= Dispatchers.IO at ~N live WS)",
        )

        // ── FIX: the dedicated ELASTIC pool (production default, ClientBridgeBlocking.dispatcher). The SAME saturation
        //    — far beyond `cap` long-lived blocking reads — does NOT starve: the pool grows, the op progresses. ──
        val elastic = ClientBridgeBlocking.dispatcher
        saturate(port, elastic, scope, holders = cap * 3)
        assertTrue(
            markerRunsWithin(elastic, scope, deadlineMs = 1500),
            "CYP-647 fix: on the dedicated elastic ClientBridgeBlocking.dispatcher a client bridge op PROGRESSES under 3×cap long-lived blocking reads (mutation: make ClientBridgeBlocking.dispatcher a capped/fixed pool → this reds)",
        )
    }
}
