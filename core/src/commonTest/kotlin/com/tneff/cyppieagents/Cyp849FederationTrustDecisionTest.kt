package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.FederationTrustDecision
import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.model.IssuerTrustFederationDecider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-849 (S-Fed-2) — teeth for the fail-closed cross-hub trust DECISION over the [HubIssuerTrust] axis.
 *
 * The PL-binding requirement is **one tooth per axis state** — each pins the mapping of ONE input posture, so a
 * mutation that flips that posture's classification reds exactly its own tooth. The expected values are the
 * fail-closed POLICY (ADMIT only for TRUSTED), stated independently — not read back from the decider under test.
 *
 * Plus the fail-closed ABSENCE tooth (`null`, per CYP-804 "never default a positive decision off absence") and the
 * invariant property (ADMIT ⟺ TRUSTED across every value) so no future member can silently reach ADMIT.
 */
class Cyp849FederationTrustDecisionTest {

    private val decide = IssuerTrustFederationDecider::decide

    // --- one tooth per axis state (PL-binding) -------------------------------------------------------------------

    /** TRUSTED is the ONLY posture that admits. MUT `TRUSTED -> DENY` → RED. */
    @Test
    fun trusted_admits() {
        assertEquals(FederationTrustDecision.ADMIT, decide(HubIssuerTrust.TRUSTED))
    }

    /** Remote intended but no trusted issuer pinned → refuse. MUT `NOT_TRUSTED -> ADMIT` → RED. */
    @Test
    fun notTrusted_denies() {
        assertEquals(FederationTrustDecision.DENY, decide(HubIssuerTrust.NOT_TRUSTED))
    }

    /** Remote not configured at all → nothing to trust → refuse. MUT `REMOTE_NOT_CONFIGURED -> ADMIT` → RED. */
    @Test
    fun remoteNotConfigured_denies() {
        assertEquals(FederationTrustDecision.DENY, decide(HubIssuerTrust.REMOTE_NOT_CONFIGURED))
    }

    // --- fail-closed absence (CYP-804: never default a positive decision off absence) ----------------------------

    /** An absent/omitted posture (older/unknown CP) MUST deny, not silently admit. MUT `null -> ADMIT` → RED. */
    @Test
    fun absentPosture_deniesFailClosed() {
        assertEquals(FederationTrustDecision.DENY, decide(null))
    }

    // --- the invariant: ADMIT for EXACTLY one input, TRUSTED --------------------------------------------------------

    /**
     * The whole fail-closed promise as a property: across every [HubIssuerTrust] member AND the absent case, a
     * posture admits IFF it is exactly TRUSTED. Any mutation that widens ADMIT to a second input reds here — the
     * catch-all that survives adding a new enum member (the exhaustive `when` would fail to compile first, but this
     * pins the *policy* even if someone adds a member and maps it to ADMIT).
     */
    @Test
    fun admit_iff_trusted_acrossAllInputs() {
        val inputs: List<HubIssuerTrust?> = HubIssuerTrust.entries + null
        for (posture in inputs) {
            val expected = if (posture == HubIssuerTrust.TRUSTED) FederationTrustDecision.ADMIT
            else FederationTrustDecision.DENY
            assertEquals(expected, decide(posture), "posture=$posture must map to $expected")
        }
    }
}
