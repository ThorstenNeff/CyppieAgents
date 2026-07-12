package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-481 (Epic CYP-427 Phase-2) — the **Backend-owned wire DTO** for the Control-Plane hub list (`GET /hubs`, seam
 * S-1 / S-J): the hubs registered to the signed-in account. This is the `:core` home the client's temporary
 * stub-facing `connect.HubDescriptor` (CYP-419) always predicted ("the real wire DTO is Backend-owned and lands in
 * `:core` with S-J"). Additive through the CYP-234 drift-gates; the live `GET /hubs` endpoint follows with CP
 * activation (the CP hub registry is INERT until the Phase-2-Remote-GO). Consumed by the desktop client (Kotlin) and
 * regenerated as a TypeScript type for the web (Team-2).
 *
 * **[dhPubKey] is the load-bearing addition (Decision 3 = Option X):** the hub's X25519 Noise static as **base64 of
 * the raw 32-byte little-endian u-coordinate** (RFC 7748) — the SAME encoding the hub publishes in its
 * `HubIdentity.dhPubKey` / `RegisteredHub.dhPubKey`. The client **TOFU-pins** this value (CYP-478, `HubTrust.Pinned`)
 * and the Noise_NK initiator uses it as the pinned responder static — so a wrong/substituted key fails the handshake
 * closed (MITM/misroute caught). It is never pinned from a CP-mutable source over an existing pin.
 */
@Serializable
data class HubDescriptor(
    val hubId: String,
    val name: String,
    /** Registry-presence, **advisory only** (H1): the CP's claim via the Connector-WS, never a connection guarantee. */
    val online: Boolean,
    val defaultPort: Int,
    /** Epoch-ms of last presence (for the "zuletzt gesehen" display); advisory, like [online]. */
    val lastSeen: Long,
    /** base64 raw 32-byte X25519 static (RFC 7748 LE u-coordinate); the client TOFU-pins it (CYP-478). */
    val dhPubKey: String,
)
