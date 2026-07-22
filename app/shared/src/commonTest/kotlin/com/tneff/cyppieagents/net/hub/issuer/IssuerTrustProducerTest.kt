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
    fun everyNonNotTrustedState_producesNoFailure_proceed() {
        // Trusted / not-configured / UNKNOWN(absent) all PROCEED — only an explicit NOT_TRUSTED blocks. Unknown must
        // proceed (not block: blocking on absence breaks old servers) yet is a DISTINCT state, never a positive affirm.
        assertNull(IssuerTrustSignal.Trusted.toRemoteFailure(), "a trusted issuer must NOT surface a failure")
        assertNull(IssuerTrustSignal.NotApplicable.toRemoteFailure(), "no remote issuer gate must NOT surface a failure")
        assertNull(IssuerTrustSignal.Unknown.toRemoteFailure(), "an ABSENT/unknown issuer verdict must PROCEED, never block")
    }

    @Test
    fun inertDefault_isUnknown_neverAFailure() = runTest {
        // The stub-parallel default is the honest UNKNOWN (no issuer determination wired yet) — it proceeds without
        // affirming trust, so the live flow is unchanged, and never fabricates a verdict.
        assertEquals(IssuerTrustSignal.Unknown, InertIssuerCheck.evaluate("hub-1"))
        assertNull(InertIssuerCheck.evaluate("hub-1").toRemoteFailure())
    }
}
