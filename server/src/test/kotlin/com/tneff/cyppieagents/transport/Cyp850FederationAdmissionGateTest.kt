package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.FederationTrustDecider
import com.tneff.cyppieagents.model.FederationTrustDecision
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-850 (S-Fed-3) — teeth for the fail-closed federation admission gate. Both axes proven in both directions,
 * plus the PL-binding fail-closed tooth and a positive control (else an all-denying gate passes every deny tooth
 * vacuously). The gate composes `federationEnabled ∧ decider.decide==ADMIT`.
 */
class Cyp850FederationAdmissionGateTest {

    // --- PL-BINDING fail-closed tooth --------------------------------------------------------------------------

    /**
     * The DEFAULT-constructed gate (federationEnabled defaulting false) DENIES even a TRUSTED peer. This is the
     * PL-binding "default-deny raus → Admit grün = ROT" tooth: MUT the default-deny out — federationEnabled default
     * `true`, OR drop the `federationEnabled &&` conjunct — makes this ADMIT → RED.
     */
    @Test
    fun defaultPosture_deniesEvenTrustedPeer_failClosed() {
        val gate = FederationAdmissionGate() // default federationEnabled = false
        assertEquals(FederationTrustDecision.DENY, gate.admit(HubIssuerTrust.TRUSTED))
    }

    /** Explicit disabled posture also denies a TRUSTED peer — pins the posture-AND-identity semantics. */
    @Test
    fun disabledPosture_deniesTrustedPeer() {
        val gate = FederationAdmissionGate(federationEnabled = false)
        assertEquals(FederationTrustDecision.DENY, gate.admit(HubIssuerTrust.TRUSTED))
    }

    // --- positive control (else all-deny is vacuous) -----------------------------------------------------------

    /** Enabled hub + TRUSTED peer → ADMIT. Without this, an all-denying gate would pass every deny tooth vacuously. */
    @Test
    fun enabledAndTrusted_admits() {
        val gate = FederationAdmissionGate(federationEnabled = true)
        assertEquals(FederationTrustDecision.ADMIT, gate.admit(HubIssuerTrust.TRUSTED))
    }

    // --- decider delegation (enabled, but peer not positively trusted) -----------------------------------------

    /** MUT `NOT_TRUSTED -> ADMIT` in the decider (or bypass it) → RED. */
    @Test
    fun enabledButNotTrusted_denies() {
        val gate = FederationAdmissionGate(federationEnabled = true)
        assertEquals(FederationTrustDecision.DENY, gate.admit(HubIssuerTrust.NOT_TRUSTED))
    }

    @Test
    fun enabledButRemoteNotConfigured_denies() {
        val gate = FederationAdmissionGate(federationEnabled = true)
        assertEquals(FederationTrustDecision.DENY, gate.admit(HubIssuerTrust.REMOTE_NOT_CONFIGURED))
    }

    /** Enabled but ABSENT peer posture (null) → DENY — the CYP-804 fail-closed absence, delegated through the gate. */
    @Test
    fun enabledButAbsentPosture_deniesFailClosed() {
        val gate = FederationAdmissionGate(federationEnabled = true)
        assertEquals(FederationTrustDecision.DENY, gate.admit(null))
    }

    // --- the two axes are INDEPENDENT --------------------------------------------------------------------------

    /**
     * A custom decider that ADMITs everything still cannot admit when the posture is disabled — proves
     * `federationEnabled` is a real OUTER gate, not shadowed by the decider. MUT drop the `federationEnabled &&`
     * conjunct → this ADMITs → RED. (Complements [defaultPosture_deniesEvenTrustedPeer_failClosed]: that one MUTs the
     * default value; this one MUTs the conjunct even against an admit-all decider.)
     */
    @Test
    fun disabledPosture_overridesAnAdmitAllDecider() {
        val admitAll = FederationTrustDecider { FederationTrustDecision.ADMIT }
        val gate = FederationAdmissionGate(decider = admitAll, federationEnabled = false)
        assertEquals(FederationTrustDecision.DENY, gate.admit(HubIssuerTrust.TRUSTED))
    }
}
