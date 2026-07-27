package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.ExperimentalFederation
import kotlinx.coroutines.flow.Flow

/**
 * CYP-851 (S-Fed-4a, Epic CYP-832) — the STUB hub↔hub peer-transport seam. **Byte-OPAQUE by construction:** it
 * carries `ByteArray` frames and NEVER a concrete federation DTO. This is the §5-Naht discipline made structural —
 * the session plumbing rides THIS interface, so when §4b freezes the concrete `FederationFrame` / `FederationHello`
 * byte shape, the session needs no reshape (the PL-binding tooth: ride the stub, never the wire).
 *
 * One transport = one peer channel. N concurrent peers = N transports; there is **NO mux** (multiplexing many
 * logical streams over one transport is §5-forbidden — the ratified direction is N-tunnel). See [FederationSession].
 *
 * The real implementation (a Noise tunnel over the relay, hub in the client-dialer role, §3) is wired only behind
 * Epic §9.3; this seam is what tests and the session are built against meanwhile.
 */
@ExperimentalFederation
interface FederationPeerTransport {
    /** The inbound opaque-frame stream (the session surfaces it verbatim; frames are not parsed here). */
    val incoming: Flow<ByteArray>

    /** Send one opaque outbound frame. */
    suspend fun send(frame: ByteArray)

    /** Close the underlying channel. */
    suspend fun close()
}

/**
 * CYP-851 (S-Fed-4a) — hub↔hub federation **session plumbing** over a [FederationPeerTransport] stub. It forwards
 * opaque frames both ways and guards the lifecycle; it **never decodes a frame** (that is §4b). Each session wraps
 * exactly ONE transport → N concurrent sessions ride N independent transports with **NO multiplexing**.
 *
 * Does NOT arm anything — the pure plumbing over a stub seam; the live transport is §9.3-gated.
 */
@ExperimentalFederation
class FederationSession(private val transport: FederationPeerTransport) {
    private var closed = false

    val isClosed: Boolean get() = closed

    /** The inbound opaque-frame stream — the transport's stream verbatim; the session does not parse frames. */
    val incoming: Flow<ByteArray> get() = transport.incoming

    /** Forward an opaque outbound frame. **Fail-closed:** sending on a closed session throws, never silently drops. */
    suspend fun send(frame: ByteArray) {
        check(!closed) { "federation session is closed" }
        transport.send(frame)
    }

    /** Idempotent close — closes the underlying transport exactly once. */
    suspend fun close() {
        if (closed) return
        closed = true
        transport.close()
    }
}
