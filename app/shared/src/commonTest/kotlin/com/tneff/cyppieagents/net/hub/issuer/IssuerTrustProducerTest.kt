package com.tneff.cyppieagents.net.hub.issuer

import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-802 (CYP-747 S1c) — the CLIENT-PRODUCE mapping tooth (axis-c). Pins that the issuer-trust carrier → RemoteFailure
 * producer maps EXACTLY: only `NotTrusted` produces a failure (fail-closed proceed otherwise), and it carries the
 * issuer hint through. Mutation (run): map `NotTrusted → null` or `Trusted → IssuerNotTrusted` → RED.
 */
class IssuerTrustProducerTest {

    @Test
    fun notTrusted_producesIssuerNotTrusted_carryingTheIssuerHint() {
        assertEquals(
            RemoteFailure.IssuerNotTrusted("acme-cp-issuer"),
            IssuerTrustSignal.NotTrusted("acme-cp-issuer").toRemoteFailure(),
        )
        assertEquals(RemoteFailure.IssuerNotTrusted(null), IssuerTrustSignal.NotTrusted(null).toRemoteFailure())
    }

    @Test
    fun trustedAndNotApplicable_produceNoFailure_proceed() {
        assertNull(IssuerTrustSignal.Trusted.toRemoteFailure(), "a trusted issuer must NOT surface a failure")
        assertNull(IssuerTrustSignal.NotApplicable.toRemoteFailure(), "no remote issuer gate must NOT surface a failure")
    }

    @Test
    fun inertDefault_isNotApplicable_neverAFailure() = runTest {
        // The stub-parallel default must be inert: it never fabricates an issuer verdict, so the live flow is unchanged.
        assertEquals(IssuerTrustSignal.NotApplicable, InertIssuerCheck.evaluate("hub-1"))
        assertNull(InertIssuerCheck.evaluate("hub-1").toRemoteFailure())
    }
}
