package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * CYP-633 — the **dedicated elastic dispatcher for bridge blocking I/O**. The loopback bridge's socket reads/writes
 * and pump loops are BLOCKING and long-lived (a per-stream `input.read()` parks a thread for the whole time a stream
 * waits for its route response — seconds for a slow route, indefinitely for a live WS). Running them on the shared,
 * **capped** `Dispatchers.IO` (default 64 threads) means ~2N threads for N streams: at N≈32 the IO dispatcher is
 * exhausted, a new bridge op (or ANY other `Dispatchers.IO` user — sqlite, file I/O) starves → livelock/timeout
 * (latent-HIGH; bites POOL at the id-count and MUX at `maxStreams`). Isolating the bridge's blocking ops onto a
 * **dedicated cached (elastic) pool** removes the contention: it grows to ~2N as needed (bounded by `maxStreams` /
 * the pool cap — not unbounded in practice) and idle threads are reaped, and it no longer competes with the rest of
 * the JVM's I/O. One dispatcher hardens both the pool ([LoopbackBridge]) and the mux (its per-stream [RealBridgeSocket]).
 */
object BridgeBlocking {
    val dispatcher: CoroutineDispatcher = Executors.newCachedThreadPool { r ->
        Thread(r, "cyp-bridge-io-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val threadSeq = AtomicLong()
}

/**
 * CYP-458 (S2) — the **Option A loopback bridge**: pump one terminated [ServerNoiseTunnel] (L2, decrypted client
 * bytes) to/from the **existing** hub Ktor listener over a `127.0.0.1` TCP socket. The unmodified routes handle the
 * client's HTTP/1.1 + WS bytes and re-verify the **real** credential (bearer / Kratos) carried inside the tunnel —
 * the loopback origin grants **zero** implicit trust (T1/T2). No new engine, no new listener; the bridge is a *client*
 * of the loopback listener the local hub already runs (RR5 carve-out: loopback ≠ public exposition).
 *
 * **Truncation guard (§4 / AC2), asymmetric by trust:**
 *  - the **untrusted** tunnel/relay side ending — `receive()` returns `null` OR throws (relay drop / L0 close /
 *    AEAD decrypt fail) — is **in-flight-uncertain** → the loopback socket is **RESET (RST, SO_LINGER 0)**, never a
 *    clean half-close, so the hub's Ktor aborts the in-flight request instead of completing a truncated one as clean;
 *  - only the **trusted** hub side (socket EOF after its own response, e.g. `Connection: close`) closes the tunnel
 *    **cleanly** so the remote client sees a clean end.
 *
 * **T3:** [loopbackHost] MUST be a loopback address — validated fail-closed at construction (never a wildcard/public
 * interface). **§3/§5:** one L2 connection per tunnel (no mux); the pump copies bounded chunks (no unbounded buffer).
 */
class LoopbackBridge(
    private val loopbackPort: Int,
    private val loopbackHost: String = "127.0.0.1",
    /** CYP-633 — the dispatcher the blocking pumps + socket I/O run on. Default = the dedicated elastic
     *  [BridgeBlocking.dispatcher] (NOT `Dispatchers.IO`); injectable so a test can pin a bounded one and prove the
     *  starvation deterministically. */
    private val blockingDispatcher: CoroutineDispatcher = BridgeBlocking.dispatcher,
    /** Injectable for tests (a recording fake for the RST-vs-clean discipline); prod = a real loopback [Socket] whose
     *  blocking I/O runs on [blockingDispatcher]. */
    private val socketFactory: (host: String, port: Int) -> BridgeSocket = { h, p -> RealBridgeSocket(h, p, blockingDispatcher) },
) {
    init {
        // T3 — fail closed if the bridge target is not loopback. A wildcard/public target would re-expose the routes.
        require(InetAddress.getByName(loopbackHost).isLoopbackAddress) {
            "loopback bridge target must be a loopback address, was '$loopbackHost'"
        }
    }

    /** Bridge [tunnel] to the loopback listener until either side ends; applies the asymmetric truncation guard. */
    suspend fun bridge(tunnel: ServerNoiseTunnel): Unit = coroutineScope {
        val bridgeId = bridgeSeq.incrementAndGet() // CYP-607 diag: correlate this bridge's up/down/end lines
        val socket = socketFactory(loopbackHost, loopbackPort)
        diagLog.info("CYP-607 bridge#{} start", bridgeId) // CYP-607
        try {
            // Upstream: client bytes (via the UNTRUSTED relay) → the hub socket. Any end here is in-flight-uncertain.
            val up = launch(blockingDispatcher) {
                var reason = "tunnel-clean-eof" // CYP-607: receive()==null (relay/client closed cleanly)
                try {
                    while (true) {
                        val chunk = tunnel.receive() ?: break // relay/L2 closed — untrusted, treat as truncation
                        socket.write(chunk)
                    }
                } catch (e: Exception) {
                    // decrypt-fail on the tunnel, or a write onto a peer-reset socket — the connection ended; the
                    // pump is best-effort and the truncation guard below still fires. (Not an error to propagate.)
                    reason = "tunnel-exc:${e::class.simpleName}" // CYP-607
                } finally {
                    // CYP-609 — asymmetric teardown by the KIND of upstream end (the reason from CYP-607):
                    //  • a CLEAN relay/client EOF (`receive()==null`, `tunnel-clean-eof`) → **graceful FIN** (half-close
                    //    the loopback out): the hub's Ktor sees a clean input-EOF, NOT a "Connection reset", and the
                    //    down-pump can still drain + flush the hub's queued response. The truncation guard is NOT lost —
                    //    Ktor's own HTTP layer rejects a truncated body (Content-Length short / missing terminal chunk →
                    //    non-2xx), which the CYP-459-T2 real-route teeth pin (Assist-verified sign-off, CYP-609).
                    //  • an ABRUPT/error end (`receive()` THREW — AEAD-decrypt-fail / tamper-truncation, or a write onto a
                    //    peer-reset socket, `tunnel-exc:*`) → **RST (SO_LINGER 0)**: in-flight-uncertain, hard-abort so a
                    //    malicious/lossy relay can never have a tampered stream completed as clean (§4/AC2 preserved).
                    val graceful = !reason.startsWith("tunnel-exc")
                    diagLog.info("CYP-607 bridge#{} up-end reason={} -> {}", bridgeId, reason, if (graceful) "socket.closeGraceful() (FIN)" else "socket.reset() (RST)")
                    if (graceful) socket.closeGraceful() else socket.reset()
                }
            }
            // Downstream: hub response bytes → the client. A hub-side EOF is a TRUSTED clean close.
            val down = launch(blockingDispatcher) {
                val buf = ByteArray(CHUNK)
                var reason = "hub-clean-eof" // CYP-607: read()<0 — the hub Ktor route closed its side (e.g. WS 1008)
                try {
                    while (true) {
                        val n = socket.read(buf)
                        if (n < 0) break // hub closed cleanly (its own response ended)
                        tunnel.send(buf.copyOf(n))
                    }
                } catch (e: Exception) {
                    // socket read after the guard's RST (Connection reset), or a send onto a closed tunnel — the
                    // connection ended; end the pump quietly. The RST the hub already saw is the truncation signal.
                    reason = "hub-exc:${e::class.simpleName}" // CYP-607
                } finally {
                    // CYP-607: `hub-clean-eof` here = the inner Ktor WS route CLOSED its side (a 1008 auth-close, or a
                    // handler that returned/threw) → the smoking gun for the "auth OK but still resets" branch.
                    diagLog.info("CYP-607 bridge#{} down-end reason={} -> tunnel.close()", bridgeId, reason)
                    tunnel.close() // trusted, clean close → the remote client sees a clean end
                }
            }
            up.join()
            down.join()
        } finally {
            diagLog.info("CYP-607 bridge#{} bridge-end (both pumps joined)", bridgeId) // CYP-607
            socket.reset()
            tunnel.close()
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024

        // CYP-607 DIAGNOSTIC INSTRUMENTATION (dogfood 2026-07-15) — TEMPORARY. The dogfood showed the hub resetting
        // 6-of-7 inner loopback WS with no trace of WHY. `wsReaderOrNull` logs the auth-null path; this logs the RST
        // TRIGGER at the bridge — which side ended first (down-end `hub-clean-eof` = the inner route closed; up-end
        // `tunnel-exc` = relay-leg) and why. One run then shows "auth-null" OR "bridge reset trigger=X". No secret
        // (only a monotonic bridge id + a reason string). Remove once the loopback-WS reject root is fixed.
        private val diagLog = LoggerFactory.getLogger("cyp607-diag")
        private val bridgeSeq = AtomicLong()
    }
}

/**
 * The loopback socket the bridge pumps through. Abstracted so tests can (a) run the real routes over a real socket and
 * (b) assert the FIN-vs-RST **truncation discipline** deterministically. [reset] MUST send a TCP RST (abortive) — used
 * on an ABRUPT/error upstream end (tamper-truncation, in-flight-uncertain); [closeGraceful] MUST send a clean FIN
 * (half-close) — used on a CLEAN upstream EOF (CYP-609). That FIN-vs-RST distinction is the truncation guard.
 */
interface BridgeSocket {
    /** The connected peer address — asserted loopback by the bridge's T3 guard on the real socket. */
    val remote: InetAddress
    suspend fun write(bytes: ByteArray)
    /** Read into [buf]; returns the byte count, or -1 on a clean peer EOF. */
    suspend fun read(buf: ByteArray): Int
    /** Abortive close (RST): SO_LINGER 0. Idempotent. Used on an ABRUPT/error upstream end (truncation guard). */
    fun reset()
    /** CYP-609 — graceful FIN half-close of the WRITE side (`shutdownOutput`): the peer's Ktor sees a clean input-EOF
     *  (never a "Connection reset"), and the READ side stays open so the down-pump can drain + flush the peer's queued
     *  response. Used on a CLEAN upstream EOF. Idempotent. (The truncation guard is NOT lost — Ktor's own HTTP layer
     *  rejects a truncated body; the abrupt/tamper path still [reset]s.) */
    fun closeGraceful()
}

/** Production [BridgeSocket] over a real loopback [Socket]; its blocking I/O is offloaded to [dispatcher] — CYP-633:
 *  the dedicated elastic [BridgeBlocking.dispatcher] by default, NOT the shared capped `Dispatchers.IO`. */
internal class RealBridgeSocket(
    host: String,
    port: Int,
    private val dispatcher: CoroutineDispatcher = BridgeBlocking.dispatcher,
) : BridgeSocket {
    private val socket = Socket().apply {
        // SO_LINGER 0 ⇒ close() emits a RST, not a FIN — the abortive semantics the truncation guard needs.
        setSoLinger(true, 0)
        tcpNoDelay = true
        connect(InetSocketAddress(host, port))
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remote: InetAddress get() = socket.inetAddress

    override suspend fun write(bytes: ByteArray) = withContext(dispatcher) {
        output.write(bytes); output.flush()
    }

    override suspend fun read(buf: ByteArray): Int = withContext(dispatcher) {
        input.read(buf)
    }

    override fun reset() {
        runCatching { if (!socket.isClosed) socket.close() } // SO_LINGER 0 → RST
    }

    override fun closeGraceful() {
        // CYP-609: shutdownOutput() sends a FIN (graceful half-close) regardless of SO_LINGER 0 (which only governs
        // close()). The input stays readable so the down-pump can drain the peer's queued response before it ends.
        runCatching { if (!socket.isClosed && !socket.isOutputShutdown) socket.shutdownOutput() }
    }
}
