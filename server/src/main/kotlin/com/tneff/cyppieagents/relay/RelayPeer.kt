package com.tneff.cyppieagents.relay

/**
 * CYP-506 (Epic CYP-427 Phase-2, activation) — the two ends a rendezvous pairs. The relay pairs exactly ONE [HUB]
 * with ONE [CLIENT] on a matching opaque rendezvous id (CYP-501 §2, RR4).
 */
enum class RelayRole { HUB, CLIENT }

/**
 * CYP-506 — one side of a relayed pair: a duplex **opaque-frame** channel. Deliberately transport-agnostic (no Ktor
 * type here) so the pairing/forwarding CORE ([RendezvousRelay]) is unit-testable against in-memory peers, and the
 * live WS session is just one impl (`WebSocketRelayPeer`).
 *
 * Frames are **opaque `ByteArray`s** — the relay NEVER decodes, parses, or interprets them (RR4: it sees only
 * ciphertext + the opaque id + sizes/timing, never plaintext or `hubId`). [rendezvousId] is an **opaque key**, never
 * reversed to a `hubId`.
 */
interface RelayPeer {
    /** The opaque rendezvous id this peer registered under — used ONLY as a pairing key, never parsed. */
    val rendezvousId: String
    val role: RelayRole

    /** Next inbound opaque frame from this peer, or `null` when its connection closed (EOF → tear the pair down). */
    suspend fun receive(): ByteArray?

    /** Forward one opaque frame to this peer, verbatim (1 frame in = 1 frame out — message-preserving, CYP-443). */
    suspend fun send(frame: ByteArray)

    /** Idempotent close of this peer's connection. */
    suspend fun close()
}
