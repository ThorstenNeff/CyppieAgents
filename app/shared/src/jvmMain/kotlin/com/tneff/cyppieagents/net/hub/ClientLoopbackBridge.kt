package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket

/**
 * CR3 ① — the **client mirror** of the server `transport/LoopbackBridge`: presents the workspace's HTTP/1.1(+WS) bytes
 * to the hub over the Noise [NoiseTunnel]. The workspace Ktor client dials `http://127.0.0.1:<port>`; this bridge
 * accepts that loopback connection and pumps the socket ↔ the tunnel, so the UNMODIFIED workspace client + the
 * UNMODIFIED hub routes talk over Noise with no new engine (the JVM/Desktop leg of the CR3 datapath; Web/iOS need the
 * from-scratch multiplatform engine — the ② follow-on). One connection per tunnel (Spec §5, no mux) — the S thru-cut.
 *
 * **Truncation guard (asymmetric by trust), mirrored from the server bridge:**
 *  - the **untrusted** tunnel/relay side ending — `receive()==null` OR a throw (relay drop / AEAD fail) — is
 *    **in-flight-uncertain** → the local socket is **RESET (RST, SO_LINGER 0)**, never a clean half-close, so the
 *    workspace client ABORTS the in-flight request instead of reading a truncated response as a clean end;
 *  - only the **trusted** local side (the workspace client's own clean EOF after its request) closes the tunnel cleanly.
 *
 * The pump is over a [BridgeConn] seam so tests drive the RST-vs-clean discipline deterministically without a real socket.
 */
class ClientLoopbackBridge(private val loopbackHost: String = "127.0.0.1") {
    init {
        // Never bind/dial a non-loopback address — the workspace bytes must never leave the host in cleartext.
        require(InetAddress.getByName(loopbackHost).isLoopbackAddress) {
            "client loopback bridge host must be a loopback address, was '$loopbackHost'"
        }
    }

    /** Pump one accepted connection ↔ [tunnel] until either side ends; applies the asymmetric truncation guard. */
    suspend fun pump(tunnel: NoiseTunnel, conn: BridgeConn): Unit = coroutineScope {
        // Upstream: the workspace client's REQUEST bytes → the tunnel (to the hub). A clean local EOF ends the request
        // → close the tunnel cleanly (the trusted side).
        val up = launch(Dispatchers.IO) {
            val buf = ByteArray(CHUNK)
            try {
                while (true) {
                    val n = conn.read(buf)
                    if (n < 0) break // the local workspace client closed its request cleanly
                    tunnel.send(buf.copyOf(n))
                }
            } catch (_: Exception) {
                // a read after the downstream guard's RST, or a send onto a closed tunnel — the connection ended.
            } finally {
                runCatching { tunnel.close() } // trusted local side ended → clean tunnel close
            }
        }
        // Downstream: the hub's RESPONSE bytes (via the UNTRUSTED relay) → the local socket. Any end here is
        // in-flight-uncertain → RST the local socket (the truncation guard), never a clean EOF.
        val down = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val chunk = tunnel.receive() ?: break // relay/tunnel closed — untrusted → treat as truncation
                    conn.write(chunk)
                }
            } catch (_: Exception) {
                // decrypt-fail on the tunnel, or a write onto a peer-reset socket — the connection ended.
            } finally {
                conn.reset() // ★ untrusted side ended → RST (never a clean FIN): the truncation guard
            }
        }
        up.join()
        down.join()
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/**
 * The accepted loopback connection the bridge pumps through. Abstracted so tests assert the RST-vs-clean **truncation
 * discipline** deterministically. [reset] MUST send a TCP RST (abortive), NOT a clean FIN — that distinction is the guard.
 */
interface BridgeConn {
    /** Read into [buf]; returns the byte count, or -1 on a clean peer EOF. */
    suspend fun read(buf: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    /** Abortive close (RST): SO_LINGER 0. Idempotent. */
    fun reset()
}

/** Production [BridgeConn] over a real accepted loopback [Socket]; blocking I/O offloaded to [Dispatchers.IO] by the pump. */
internal class RealBridgeConn(private val socket: Socket) : BridgeConn {
    init {
        socket.setSoLinger(true, 0) // close() emits a RST, not a FIN — the abortive semantics the truncation guard needs
        socket.tcpNoDelay = true
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    override suspend fun read(buf: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buf) }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
    override fun reset() { runCatching { if (!socket.isClosed) socket.close() } } // SO_LINGER 0 → RST
}
