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
    /**
     * CYP-804 (axis c — issuer trust, §5-C2) — the hub's per-hub ISSUER-TRUST posture as published on the discovery
     * record: whether THIS owned hub has a trusted issuer/relay anchor established. Lets the client DISTINGUISH
     * `owned-but-issuer-not-trusted` from a genuinely-offline hub (both are [online]==false today) → the typed
     * `IssuerNotTrusted` connect-cause instead of collapsing to `HubOffline`. A per-hub POSTURE, not a connect-time
     * oracle (§5-a: the tunnel reject stays uniform; the client derives from this + its own outcome).
     *
     * **Appended (never reordered) + nullable-default = ABSENT-when-unknown:** serialized via [com.tneff.cyppieagents.CommJson]
     * (`explicitNulls = false`), a `null` value is OMITTED — never emitted as explicit `null` — so an older CP that
     * doesn't publish the posture, or an unknown state, reads as absent (client → current behavior), and the web-ts
     * zod `.optional()` root validates (an explicit `null` would fail it). NEVER default a POSITIVE decision off absence.
     */
    val issuerTrust: HubIssuerTrust? = null,
)

/**
 * CYP-804 (axis c) — the per-hub issuer-trust posture published on [HubDescriptor.issuerTrust]. Mirrors the
 * server-internal `RemoteRelayWiring.RemoteIssuerTrustState` 1:1 (one coherent contract across hub-internal
 * classification and the wire). A DISTINCT axis-c namespace — NOT part of the axis-a hub-key TOFU vocabulary
 * (`HubTrust.kt`: [HubTrustState]/[TrustRejectReason]). Exported as a STANDALONE named `components/schemas` type
 * for Team-2 (`ContractGenerator.STANDALONE_ENUM_EXPORTS`).
 */
@Serializable
enum class HubIssuerTrust {
    /** A trusted issuer/relay anchor IS pinned at this hub — remote is establishable (axis-c edge present). */
    TRUSTED,

    /** Remote is INTENDED for this hub but NO trusted issuer is pinned → owned-but-issuer-not-trusted (axis-c edge
     *  absent). The client surfaces the terminal `IssuerNotTrusted` cause; recovery is OOB only (no in-app grant). */
    NOT_TRUSTED,

    /** The hub is not configured for remote at all → the issuer-trust question does not apply (a local-only hub). */
    REMOTE_NOT_CONFIGURED,
}
