package com.tneff.cyppieagents.controlplane

import kotlinx.serialization.Serializable

/**
 * CYP-501 (Epic CYP-427 Phase-2, activation) — the **client↔CP wire contract** for minting a hub `hubTicket` (the
 * CpJwt the hub's RR3 gate verifies). After the Noise handshake the client (CYP-496 `CpJwtProvider`) sends this to
 * the CP, bearing its operator auth; the CP authenticates the operator, checks hub ownership, and mints the EdDSA
 * CpJwt with `sub` = the **authenticated** operator (never client-chosen) + `cb` = this request's [cb].
 *
 * [cb] = `base64url(SHA-256(h ‖ hubId))` from the client's **live** session `h`. The hub re-derives and checks it
 * against its OWN live `h` (anti-cross-session-replay, CB-x1), so a wrong `cb` fails closed at the hub — supplying it
 * here is safe. INERT contract: no live mint until the Phase-2-Remote-GO. Consumed by the desktop client (Kotlin) and
 * regenerated as a TypeScript type for the web (Team-2).
 */
@Serializable
data class HubTicketRequest(
    val hubId: String,
    val cb: String,
)

/**
 * CYP-501 — the CP's mint **response**. Exactly one of [cpJwt] (minted) / [failure] is set. The failure is a **typed,
 * operator-facing** distinction (UIUX 3-truths model): the client renders the correct CP-side cause. A generic
 * "mint failed" would conflate two different truths (see [HubTicketFailure]).
 */
@Serializable
data class HubTicketResponse(
    val cpJwt: String? = null,
    val failure: HubTicketFailure? = null,
)

/**
 * CYP-501 — the CP-side mint failures the client must tell apart. `cpUnreachable` (a network failure reaching the CP)
 * is **client-side** and is NOT one of these — it never appears in a response.
 */
@Serializable
enum class HubTicketFailure {
    /** The CP could not AUTHENTICATE the operator (e.g. a dead Kratos session) → the client routes to re-auth
     *  (AuthGate) and resumes. **Non-terminal.** */
    CP_SESSION_EXPIRED,

    /** The operator IS authenticated but does not own/belong to the requested hub (the `RegisteredHub.ownerId` check
     *  fails) → **terminal, and distinct from** the hub-side `authRejected` (the hub's RR3 gate rejecting the PoP).
     *  Two different truths — a compromised CP that mints identity is still stopped at the hub, and a not-owner is
     *  stopped at the CP; the client must not conflate them. */
    NOT_AUTHORIZED_FOR_HUB,
}
