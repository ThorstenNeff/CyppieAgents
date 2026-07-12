package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.transport.RemoteRelayWiring

/**
 * CYP-503 (Epic CYP-427 Phase-2, activation) — the **LIVE** CP-side `hubTicket` minter, the drop-in for
 * [InertHubTicketMinter] at the Phase-2-Remote-GO. **Built + tested INERT:** nothing wires it into a live route yet
 * (the CP mint endpoint is GO-gated), so the platform default remains [InertHubTicketMinter] (flag-off). At GO the
 * only change is the wiring swap behind `CYPPIE_REMOTE_RELAY_URL` — **no new code, no new gate**.
 *
 * It enforces the **4 activation-gate points** (each mutation-proven in `Cyp503LiveHubTicketMinterTest` /
 * `Cyp503TtlSingleSourceTest`):
 *  1. **Owner-check, fail-closed** — the authenticated operator MUST own [HubTicketRequest.hubId] in [HubRegistrar]
 *     (`RegisteredHub.ownerId`); an unknown hub OR a non-owner → [HubTicketFailure.NOT_AUTHORIZED_FOR_HUB].
 *  2. **`sub` from the AUTHENTICATED operator, never the request** — [HubTicketRequest] carries no identity field;
 *     the CP sets `sub` = [authenticate]'s result. A compromised CP forges identity but never the operator PoP the
 *     hub ALSO checks at RR3 (RR2-B) — so a forged `sub` still fails closed at the hub.
 *  3. **`cb` passed 1:1, never recomputed** — the CP has no session `h`; it signs the client-supplied
 *     [HubTicketRequest.cb] verbatim. The binding is enforced at the hub against the LIVE `h` (S-E CHANNEL_BINDING),
 *     which is precisely why a client-supplied `cb` is safe.
 *  4. **Short TTL = the single-sourced Op-Session-TTL** — the ticket `exp` derives from
 *     [RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS], the SAME constant the hub tunnel-cap uses
 *     ([Rr3AuthenticatedTunnelHandler] `sessionTtlMs`), so the effective `min(ticket-exp, op-session-TTL)` cannot
 *     drift between the two legs.
 *
 * A dead CP session ([authenticate] → null) → [HubTicketFailure.CP_SESSION_EXPIRED] (non-terminal; the client
 * re-auths and resumes). Every branch returns a **typed** [HubTicketResponse] (never a bare null / throw) — the
 * CYP-501 UIUX 3-truths contract.
 */
class LiveHubTicketMinter(
    /** Authenticate the CP session → the operator's identity, or `null` for a dead/invalid session. The CP owns
     *  this decision; the client never chooses its own `sub`. */
    private val authenticate: (CpOperatorSession) -> String?,
    private val registrar: HubRegistrar,
    private val cpJwtMinter: CpJwtMinter,
    private val nowMs: () -> Long,
    /** The Op-Session-TTL — defaults to the SINGLE SOURCE the hub tunnel-cap also reads (CYP-484). Injected so the
     *  boundary is virtual-clock-testable; never an independently-chosen literal. */
    private val ttlMs: Long = RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS,
) : HubTicketMinter {
    override fun mint(request: HubTicketRequest, cpSession: CpOperatorSession): HubTicketResponse {
        // (auth) a dead CP session → a non-terminal, distinct operator-facing truth (re-auth + resume).
        val operatorId = authenticate(cpSession)
            ?: return HubTicketResponse(failure = HubTicketFailure.CP_SESSION_EXPIRED)
        // (1) owner-check, fail-closed: an unknown hub OR a known-but-not-owned hub → the terminal deny.
        val hub = registrar.lookup(request.hubId)
            ?: return HubTicketResponse(failure = HubTicketFailure.NOT_AUTHORIZED_FOR_HUB)
        if (hub.ownerId != operatorId) {
            return HubTicketResponse(failure = HubTicketFailure.NOT_AUTHORIZED_FOR_HUB)
        }
        val jwt = cpJwtMinter.mint(
            hubId = request.hubId,
            operatorId = operatorId,        // (2) authenticated sub — never sourced from the request
            channelBinding = request.cb,    // (3) client-supplied cb, verbatim (bound to the live h AT THE HUB)
            issuedAtMs = nowMs(),
            ttlMs = ttlMs,                  // (4) single-sourced Op-Session-TTL
        )
        return HubTicketResponse(cpJwt = jwt)
    }
}
