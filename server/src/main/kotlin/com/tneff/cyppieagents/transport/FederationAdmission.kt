package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.FederationTrustDecider
import com.tneff.cyppieagents.model.FederationTrustDecision
import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.model.IssuerTrustFederationDecider

/**
 * CYP-850 (S-Fed-3, Epic CYP-832 Multi-Hub federation) — the fail-closed **server-side admission gate** for an
 * off-loopback FEDERATED peering. Structurally the same posture-AND-identity shape as the god-token gate
 * (CYP-828 `operatorEligible = isOperator ∧ loopbackPosture`): TWO independent fail-closed conditions must BOTH hold
 * to [FederationTrustDecision.ADMIT].
 *
 *  1. [federationEnabled] — THIS hub is explicitly configured to federate at all. **DEFAULT `false` = fail-closed**:
 *     a gate constructed without an explicit posture admits NOTHING, so a missed/future wiring site inherits DENY,
 *     never a silent hole (decide by comparing FAILURE modes, not the happy path — the safe value is the DEFAULT).
 *  2. [decider] — the specific remote peer's issuer-trust posture classifies as ADMIT (CYP-849 S-Fed-2: ADMIT ⟺
 *     [HubIssuerTrust.TRUSTED]; NOT_TRUSTED / REMOTE_NOT_CONFIGURED / absent(null) → DENY). The gate NEVER re-derives
 *     peer trust — it asks the [FederationTrustDecider] seam.
 *
 * The two axes are INDEPENDENT: an admit-all decider still cannot admit while the posture is disabled, and a
 * federation-enabled hub still denies an untrusted peer. Both must be positive.
 *
 * **This gate does NOT arm anything.** It is the pure admission DECISION only. Deriving [federationEnabled] from real
 * config (a pinned issuer anchor + relay URL — cf. `RemoteRelayWiring.classifyIssuerTrust` / `resolveIssuerAnchor`)
 * and consuming this decision at the live off-loopback connect path is a SEPARATE wiring step, gated behind the Epic
 * §9.3 (tunnel↔credential runtime binding, M2 G4 / CYP-532) plus the god-token close (CYP-828) and server-side
 * re-auth. Until that lands, the live connect path stays `InertRelayConnector` (default-deny) — this gate is not
 * wired into it.
 */
class FederationAdmissionGate(
    private val decider: FederationTrustDecider = IssuerTrustFederationDecider,
    private val federationEnabled: Boolean = false,
) {
    /**
     * ADMIT ⟺ this hub is [federationEnabled] AND the remote peer's issuer-trust posture is admitted by [decider].
     * Every other combination — federation disabled, OR the peer not positively trusted, OR an absent posture —
     * DENIES. The `federationEnabled &&` conjunct is the outer fail-closed gate; dropping it would let a trusted peer
     * in even on a hub that never opted into federation.
     */
    fun admit(remoteIssuerTrust: HubIssuerTrust?): FederationTrustDecision =
        if (federationEnabled && decider.decide(remoteIssuerTrust) == FederationTrustDecision.ADMIT) {
            FederationTrustDecision.ADMIT
        } else {
            FederationTrustDecision.DENY
        }
}
