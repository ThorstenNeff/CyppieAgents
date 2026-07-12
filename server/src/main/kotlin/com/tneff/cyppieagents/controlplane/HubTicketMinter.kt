package com.tneff.cyppieagents.controlplane

/**
 * CYP-501 (activation, INERT) — the CP-side **mint** of a hub `hubTicket` (the CpJwt the hub's RR3 gate verifies).
 * End-to-end when live: (1) **authenticate** the operator from [CpOperatorSession] (a dead session →
 * [HubTicketFailure.CP_SESSION_EXPIRED]); (2) **owner-check** — the operator must OWN [HubTicketRequest.hubId] in the
 * [HubRegistrar] (`RegisteredHub.ownerId`), else [HubTicketFailure.NOT_AUTHORIZED_FOR_HUB]; (3) **mint** via the
 * merged [CpJwtMinter] with `sub` = the **authenticated** operator (never client-chosen) + `cb` from the request + a
 * short TTL (= the Op-Session-TTL, CYP-484) → the compact EdDSA CpJwt. **INERT** — no live mint until the
 * Phase-2-Remote-GO.
 *
 * The two failures are **distinct operator-facing truths** (UIUX 3-truths model), NOT a generic null: CP_SESSION_EXPIRED
 * is non-terminal (re-auth + resume); NOT_AUTHORIZED_FOR_HUB is terminal and distinct from the hub's `authRejected`.
 *
 * Security (PO-confirmed): the client supplies `cb` (bound to ITS live `h`) and the CP signs with the authenticated
 * `sub`; the hub RR3 gate checks `cb == SHA-256(live-h ‖ hubId)` ∧ the operator PoP, so a wrong `cb`/`sub` fails
 * closed at the hub. The CP forges identity but never the PoP (RR2-B).
 */
fun interface HubTicketMinter {
    fun mint(request: HubTicketRequest, cpSession: CpOperatorSession): HubTicketResponse
}

/** The operator's Control-Plane session credential (e.g. a Kratos session token) presented with a mint request. The
 *  CP authenticates it → the operator; the client never chooses its own `sub`. */
data class CpOperatorSession(val sessionToken: String)

/** INERT default: no live mint. Fail-closed to the terminal deny (never wired live; the real minter distinguishes the
 *  operator-facing failures). */
object InertHubTicketMinter : HubTicketMinter {
    override fun mint(request: HubTicketRequest, cpSession: CpOperatorSession): HubTicketResponse =
        HubTicketResponse(failure = HubTicketFailure.NOT_AUTHORIZED_FOR_HUB)
}
