package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.ExperimentalFederation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CYP-864 (S-Fed-4b, Epic CYP-832, DARK) — the **N-tunnel binding seam**: adapts a [ServerNoiseTunnel] (the CYP-457
 * post-handshake encrypted duplex over the relay, the CYP-536 N-tunnel building block) to the federation
 * [FederationPeerTransport] (CYP-851). This is how a hub↔hub peering rides the ratified transport (§3: one federated
 * peer per N-tunnel, client-dialer role, no mux) without the session/plumbing knowing anything about Noise/relay.
 *
 * **Byte-OPAQUE:** frames pass through unchanged in both directions — the binding never decodes a frame, so the
 * ratified §4b wire bytes are carried byte-exact (M2-freeze conformance). It only bridges the shapes: the tunnel's
 * pull `receive(): ByteArray?` (null = closed) becomes the transport's push [incoming] `Flow`.
 *
 * It also exposes the tunnel's [handshakeHash] (`h`) — the channel-binding the `federation-peer` PoP is bound to
 * (§4b-1, `operatorAuthChallenge(h, hubId, nonce)` reuse) at the peer handshake.
 *
 * **DARK:** the [ServerNoiseTunnel] is the seam — tests bind a stub. A LIVE tunnel (from a real relay-dialed
 * NoiseRelayConnector) is wired only behind Epic §9.3 (server re-auth + god-token✅ on real topology + GO). No live
 * connect/transport here.
 */
@ExperimentalFederation
class FederationTunnelTransport(private val tunnel: ServerNoiseTunnel) : FederationPeerTransport {

    /** The tunnel's Noise handshake hash `h` — the channel-binding for the federation-peer PoP (§4b-1). */
    val handshakeHash: ByteArray get() = tunnel.handshakeHash

    /**
     * The inbound frame stream — drains the tunnel's `receive()` until it returns `null` (closed/EOF), then completes.
     * Cold: each collection drains the tunnel. Byte-exact — frames are surfaced verbatim, never parsed.
     */
    override val incoming: Flow<ByteArray> = flow {
        while (true) {
            val frame = tunnel.receive() ?: break
            emit(frame)
        }
    }

    /** Forward one opaque frame as one Noise transport message — byte-exact, no reshape. */
    override suspend fun send(frame: ByteArray) = tunnel.send(frame)

    /** Close the underlying tunnel. */
    override suspend fun close() = tunnel.close()
}
