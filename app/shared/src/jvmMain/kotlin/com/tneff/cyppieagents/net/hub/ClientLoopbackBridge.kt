package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.pool.BackpressureSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
        // Upstream (conn → tunnel): the workspace REQUEST bytes. H7 (CYP-535) — a bounded ≤INFLIGHT_FRAMES credit
        // window sits between the source-socket READER and the tunnel-SENDER: when the window is full the reader
        // suspends (stops reading the loopback socket) → TCP backpressure to the origin (the workspace Ktor client)
        // → a fixed per-tunnel in-flight ceiling (≤8×CHUNK/dir) instead of Ktor's implicit/version-fragile buffer.
        // Loss-free (never a drop) and ORTHOGONAL to the truncation guard (a full window never RSTs; only a tunnel
        // END does — that stays exactly as-is). A pooled tunnel ([BackpressureSignal]) surfaces the full window as
        // C3 BACKPRESSURED; a plain tunnel isn't one, so the window still bounds memory but emits no signal.
        val window = Channel<ByteArray>(capacity = INFLIGHT_FRAMES)
        val signal = tunnel as? BackpressureSignal
        val reader = launch(Dispatchers.IO) {
            val buf = ByteArray(CHUNK)
            try {
                while (true) {
                    val n = conn.read(buf)
                    if (n < 0) break // the local workspace client closed its request cleanly (trusted EOF)
                    val frame = buf.copyOf(n)
                    if (window.trySend(frame).isFailure) { // window full ⇒ ≤8 credit exhausted: block the pump
                        signal?.onBackpressured(true)
                        window.send(frame) // suspends until the sender drains a slot → source-socket TCP backpressure
                        signal?.onBackpressured(false)
                    }
                }
            } catch (_: Exception) {
                // a read after the downstream guard's RST — the connection ended.
            } finally {
                window.close() // no more request bytes; the sender drains the remaining window then closes the tunnel
            }
        }
        val sender = launch(Dispatchers.IO) {
            try {
                for (frame in window) tunnel.send(frame) // drain the credit window → the tunnel (to the hub)
            } catch (_: Exception) {
                // a send onto a closed tunnel — the connection ended.
            } finally {
                runCatching { tunnel.close() } // trusted local side ended → clean tunnel close (after draining)
            }
        }
        // Downstream: the hub's RESPONSE bytes (via the UNTRUSTED relay) → the local socket. Any end here is
        // in-flight-uncertain → RST the local socket (the truncation guard), never a clean EOF. UNCHANGED by H7 —
        // the inbound direction is bounded by the local socket's OS buffer + the SERVER bridge's own send-window.
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
        reader.join()
        sender.join()
        down.join()
    }

    private companion object {
        const val CHUNK = 16 * 1024

        /**
         * CYP-535 (H7) — the per-tunnel, per-direction in-flight credit window: **≤8 frames** (`8 × CHUNK` =
         * ≤128 KB/dir, ≤256 KB/tunnel bidirectional). Single-sourced here for the client bridge; the server
         * `transport/LoopbackBridge` **mirrors** the same value on its own tunnel-send direction (the client/server
         * bridge-mirror rule). The aggregate bound is this × [com.tneff.cyppieagents.net.hub.pool.TUNNEL_POOL_CAP]
         * (H7 §5: per-tunnel independent windows, not a shared credit pool — no cross-tunnel head-of-line blocking).
         */
        const val INFLIGHT_FRAMES = 8
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
