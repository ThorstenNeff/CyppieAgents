package com.tneff.cyppieagents.connect

/**
 * CYP-419 (Epic CYP-395 S-L) — client-facing model of a registered hub (spec §5 B2 / seam **S-1** `GET /hubs`).
 * The real wire DTO is Backend-owned and lands in `:core` with S-J; this client-facing shape lets S-L build the
 * hub-list + connect screens against a stub now. [lastSeen] = epoch-ms (for the spec's relative "zuletzt gesehen").
 *
 * **[online] is Registry-Presence — advisory only (H1).** It is the Control-Plane's claim (via the Connector-WS)
 * and in Local mode says nothing about whether *this* frontend reaches the hub on the LAN. It is NEVER a
 * connection guarantee and is never rendered as "connected"/success-green (that truth is the connect feed, S-2).
 */
data class HubDescriptor(
    val hubId: String,
    val name: String,
    val online: Boolean,
    val defaultPort: Int,
    val lastSeen: Long,
)

/** CYP-419 — outcome of hub registration (spec §4 A2 / seam **S-4**). Phase-1 desktop = device-code automatic (Q7). */
data class HubRegistration(val hubId: String, val name: String)

/**
 * CYP-419 (S-L) — the Control-Plane client the hubConnect screens consume: the hub list (S-1) + hub registration
 * (S-4). **Stub-first:** S-L builds against [StubControlPlaneClient]; the live HTTP client against the real CP
 * chain lands in **S-J** (after Backend's S-D/S-C). Presence is advisory (H1).
 */
interface ControlPlaneClient {
    /**
     * GET /hubs (S-1) → the hubs registered to the signed-in account. Throws [ControlPlaneUnreachableException]
     * when the CP is unreachable — an HONEST error (H5: the first sign-in / hub-list needs the CP online), never a
     * silent hang and never a cache-faked "online".
     */
    suspend fun hubs(): List<HubDescriptor>

    /** Register this hub with the CP (S-4). Throws [ControlPlaneUnreachableException] when the CP is offline (A2). */
    suspend fun registerHub(name: String): HubRegistration
}

/**
 * CYP-419 — the Control Plane is not reachable. **H5:** the first sign-in and the hub list need the CP online; when
 * it is not, that is an honest, surfaced error state (`hubconnect_hubs_error` / `hubconnect_register_error_offline`
 * / connect cause `NEVER_ONLINE`), never a silent hang.
 */
class ControlPlaneUnreachableException(message: String = "control_plane_unreachable") : Exception(message)
