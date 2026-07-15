package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.transport.BridgeSocket
import com.tneff.cyppieagents.transport.RealBridgeSocket
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * CYP-620 Increment 3 (step 5) — the **multiplexed** replacement for `LoopbackBridge`. Where `LoopbackBridge` is a
 * dumb 1-socket-per-tunnel pump, [MuxBridge] runs a [YamuxSession] over the ONE terminated [ServerNoiseTunnel] and
 * dials one **per-stream** loopback socket (a [RealBridgeSocket], `127.0.0.1`) to the hub's existing Ktor listener.
 * The unmodified routes see the same per-stream loopback connections they see today and re-verify the **real**
 * credential per stream (T1/T2/M2) — the loopback origin grants zero implicit trust.
 *
 * It is a drop-in for the `suspend (ServerNoiseTunnel) -> Unit` bridge seam of `Rr3AuthenticatedTunnelHandler`: the
 * feature flag `remote.transport=mux|pool` selects `MuxBridge::bridge` vs `LoopbackBridge::bridge` at wiring time.
 *
 * **G7 handshake first (§4.8.10):** before any yamux frame, [bridge] reads the peer's [MuxHello] (the first tunnel
 * message, inside Noise), verifies mode+version, and answers with ours. A bad/absent hello — e.g. a `pool`-mode peer
 * that sends HTTP bytes instead — is refused **fail-closed** (the tunnel is closed, no session runs, no bytes bridged).
 * This is the mixed-mode guard: the untrusted relay cannot tamper the marker (it rides the encrypted channel).
 *
 * **T3:** [loopbackHost] MUST be a loopback address — validated fail-closed at construction, exactly as `LoopbackBridge`.
 * **§4.8.3:** a stream close/reset affects only its own loopback socket (the CYP-609/618 FIN-vs-RST discipline lives in
 * [YamuxStream], per stream); a tunnel drop resets every stream and completes the session.
 */
class MuxBridge(
    private val loopbackPort: Int,
    private val loopbackHost: String = "127.0.0.1",
    /** Injectable for tests; prod = a real loopback [RealBridgeSocket]. Dialed once per opened stream. */
    private val socketFactory: (host: String, port: Int) -> BridgeSocket = ::RealBridgeSocket,
    private val maxStreams: Int = YamuxSession.DEFAULT_MAX_STREAMS,
) {
    init {
        require(InetAddress.getByName(loopbackHost).isLoopbackAddress) {
            "mux bridge target must be a loopback address, was '$loopbackHost'"
        }
    }

    /** Run the mux over [tunnel] until it (or the session) ends. Fail-closed if the G7 hello does not match. */
    suspend fun bridge(tunnel: ServerNoiseTunnel): Unit = coroutineScope {
        // G7: the peer's hello is the first tunnel message. A pool peer / tampered marker / EOF → refuse.
        val peerHello = tunnel.receive()
        if (peerHello == null || !MuxHello.verify(peerHello)) {
            // WARN (fail-closed refuse): the classified reason (absent / bad-length / bad-magic / version-skew /
            // mode-mismatch) — never the raw hello bytes. This is the mixed-mode / downgrade-attempt diagnostic seam.
            log.warn("CYP-620 mux G7 hello REJECTED (reason={}) → refused fail-closed, tunnel closed", MuxHello.rejectReason(peerHello))
            tunnel.close()
            return@coroutineScope
        }
        log.info("CYP-620 mux G7 hello accepted → yamux session starting (maxStreams={})", maxStreams)
        tunnel.send(MuxHello.ENCODED) // our hello — the client verifies it symmetrically

        // Handshake OK: run the yamux session. One loopback socket per opened stream (streamId/class not needed for
        // dialing — the route is chosen by the HTTP path INSIDE the stream, so both are ignored here).
        val link = YamuxFrameLink(tunnel)
        val scheduler = MuxWriteScheduler()
        val session = YamuxSession(
            link = link,
            scheduler = scheduler,
            sinkFactory = { _, _ -> socketFactory(loopbackHost, loopbackPort) },
            scope = this,
            maxStreams = maxStreams,
        )
        session.run() // returns on tunnel drop / inbound GoAway / protocol-error teardown
    }

    private companion object {
        private val log = LoggerFactory.getLogger(MuxBridge::class.java)
    }
}
