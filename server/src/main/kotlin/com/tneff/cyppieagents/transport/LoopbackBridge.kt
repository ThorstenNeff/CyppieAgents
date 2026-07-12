package com.tneff.cyppieagents.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
    /** Injectable for tests (a recording fake for the RST-vs-clean discipline); prod = a real loopback [Socket]. */
    private val socketFactory: (host: String, port: Int) -> BridgeSocket = ::RealBridgeSocket,
) {
    init {
        // T3 — fail closed if the bridge target is not loopback. A wildcard/public target would re-expose the routes.
        require(InetAddress.getByName(loopbackHost).isLoopbackAddress) {
            "loopback bridge target must be a loopback address, was '$loopbackHost'"
        }
    }

    /** Bridge [tunnel] to the loopback listener until either side ends; applies the asymmetric truncation guard. */
    suspend fun bridge(tunnel: ServerNoiseTunnel): Unit = coroutineScope {
        val socket = socketFactory(loopbackHost, loopbackPort)
        try {
            // Upstream: client bytes (via the UNTRUSTED relay) → the hub socket. Any end here is in-flight-uncertain.
            val up = launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val chunk = tunnel.receive() ?: break // relay/L2 closed — untrusted, treat as truncation
                        socket.write(chunk)
                    }
                } catch (_: Exception) {
                    // decrypt-fail on the tunnel, or a write onto a peer-reset socket — the connection ended; the
                    // pump is best-effort and the truncation guard below still fires. (Not an error to propagate.)
                } finally {
                    socket.reset() // ★ untrusted side ended → RST (never a clean EOF): the truncation guard
                }
            }
            // Downstream: hub response bytes → the client. A hub-side EOF is a TRUSTED clean close.
            val down = launch(Dispatchers.IO) {
                val buf = ByteArray(CHUNK)
                try {
                    while (true) {
                        val n = socket.read(buf)
                        if (n < 0) break // hub closed cleanly (its own response ended)
                        tunnel.send(buf.copyOf(n))
                    }
                } catch (_: Exception) {
                    // socket read after the guard's RST (Connection reset), or a send onto a closed tunnel — the
                    // connection ended; end the pump quietly. The RST the hub already saw is the truncation signal.
                } finally {
                    tunnel.close() // trusted, clean close → the remote client sees a clean end
                }
            }
            up.join()
            down.join()
        } finally {
            socket.reset()
            tunnel.close()
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/**
 * The loopback socket the bridge pumps through. Abstracted so tests can (a) run the real routes over a real socket and
 * (b) assert the RST-vs-clean **truncation discipline** deterministically. [reset] MUST send a TCP RST (abortive), NOT
 * a clean FIN — that distinction is the truncation guard.
 */
interface BridgeSocket {
    /** The connected peer address — asserted loopback by the bridge's T3 guard on the real socket. */
    val remote: InetAddress
    suspend fun write(bytes: ByteArray)
    /** Read into [buf]; returns the byte count, or -1 on a clean peer EOF. */
    suspend fun read(buf: ByteArray): Int
    /** Abortive close (RST): SO_LINGER 0. Idempotent. */
    fun reset()
}

/** Production [BridgeSocket] over a real loopback [Socket]; blocking I/O is offloaded to [Dispatchers.IO] by the pump. */
internal class RealBridgeSocket(host: String, port: Int) : BridgeSocket {
    private val socket = Socket().apply {
        // SO_LINGER 0 ⇒ close() emits a RST, not a FIN — the abortive semantics the truncation guard needs.
        setSoLinger(true, 0)
        tcpNoDelay = true
        connect(InetSocketAddress(host, port))
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remote: InetAddress get() = socket.inetAddress

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes); output.flush()
    }

    override suspend fun read(buf: ByteArray): Int = withContext(Dispatchers.IO) {
        input.read(buf)
    }

    override fun reset() {
        runCatching { if (!socket.isClosed) socket.close() } // SO_LINGER 0 → RST
    }
}
