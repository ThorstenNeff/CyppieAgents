package com.tneff.cyppieagents.model

/**
 * CYP-849 (S-Fed-2, Epic CYP-832 Multi-Hub Hub/Relay federation) — the **cross-hub trust DECISION** over the
 * already-shipped [HubIssuerTrust] axis (CYP-747 Model-2 / CYP-804). This is pure classification: given the
 * issuer-trust posture a remote hub publishes on its discovery record ([HubDescriptor.issuerTrust]), decide whether
 * THIS hub may admit a federated peering from it.
 *
 * It is the **S-Fed-2 seam** the S-Fed-3 admission gate (CYP-850) consumes: the gate NEVER re-derives trust — it asks
 * a [FederationTrustDecider]. Keeping the decision here (next to the vocabulary it classifies, in `:core`) means both
 * the server admission gate and any future client-side pre-check share ONE fail-closed policy, not two that can drift.
 *
 * **Design note — this decision does NOT arm anything.** ADMIT is a *trust classification*, not a live connect. The
 * off-loopback remote-connect stays DARK behind the Epic's §9.3 (tunnel↔credential runtime binding, M2 G4 / CYP-532)
 * and the §4b/§5 Weichen. A DENY here is one fail-closed layer; §9.3 is the arming gate.
 */
enum class FederationTrustDecision {
    /** The remote posture is positively classified as trusted — a federated peering MAY be admitted (subject to §9.3). */
    ADMIT,

    /** The remote posture is not trusted, or cannot be positively classified — a federated peering is refused. */
    DENY,
}

/**
 * The S-Fed-2 decision seam. A single pure classification of a remote hub's [HubIssuerTrust] posture into an
 * [FederationTrustDecision]. The S-Fed-3 gate stubs against THIS `fun interface` — never against the raw enum — so
 * the gate's tests inject an admit/deny decider without reaching into trust policy, and the policy lives in exactly
 * one place ([IssuerTrustFederationDecider]).
 */
fun interface FederationTrustDecider {
    fun decide(remoteIssuerTrust: HubIssuerTrust?): FederationTrustDecision
}

/**
 * The canonical **fail-closed** federation trust policy.
 *
 * ADMIT ⟺ the remote posture is EXACTLY [HubIssuerTrust.TRUSTED] — a trusted issuer/relay anchor is actually pinned
 * at the remote hub. Every other posture denies:
 *  - [HubIssuerTrust.NOT_TRUSTED]      → DENY (remote is intended but no trusted issuer is pinned).
 *  - [HubIssuerTrust.REMOTE_NOT_CONFIGURED] → DENY (the remote isn't set up for remote at all — nothing to trust).
 *  - `null` (ABSENT) → DENY. An older/unknown CP omits the posture ([HubDescriptor.issuerTrust] is nullable and, per
 *    CYP-804 `explicitNulls=false`, absent-when-unknown). **NEVER default a POSITIVE decision off absence** (CYP-804) —
 *    an unclassifiable posture is a DENY, not a silent admit.
 *
 * The `when` is EXHAUSTIVE with **no `else`** (the `RemoteRelayWiring.RemoteIssuerTrustState.toWire()` discipline): a
 * NEW [HubIssuerTrust] member fails to COMPILE here until it is deliberately classified — so a future state cannot
 * silently fall into ADMIT *or* an implicit branch. Fail-closed in the derivation AND compile-forced at extension.
 */
object IssuerTrustFederationDecider : FederationTrustDecider {
    override fun decide(remoteIssuerTrust: HubIssuerTrust?): FederationTrustDecision =
        when (remoteIssuerTrust) {
            HubIssuerTrust.TRUSTED -> FederationTrustDecision.ADMIT
            HubIssuerTrust.NOT_TRUSTED -> FederationTrustDecision.DENY
            HubIssuerTrust.REMOTE_NOT_CONFIGURED -> FederationTrustDecision.DENY
            null -> FederationTrustDecision.DENY
        }
}
