package com.tneff.cyppieagents.net.hub.issuer

import com.tneff.cyppieagents.model.HubIssuerTrust
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
    fun adapter_mapsFrozenCarrier_toClientDomain_1to1_plusAbsent() {
        // CYP-804 boundary adapter: the :core wire enum -> client-local domain (Pattern B). 1:1 + null(absent)->Unknown.
        assertEquals(IssuerTrustSignal.NotTrusted("acme"), HubIssuerTrust.NOT_TRUSTED.toIssuerTrustSignal("acme"))
        assertEquals(IssuerTrustSignal.NotTrusted(null), HubIssuerTrust.NOT_TRUSTED.toIssuerTrustSignal())
        assertEquals(IssuerTrustSignal.Trusted, HubIssuerTrust.TRUSTED.toIssuerTrustSignal())
        assertEquals(IssuerTrustSignal.NotApplicable, HubIssuerTrust.REMOTE_NOT_CONFIGURED.toIssuerTrustSignal())
        // ★ absent (old CP) -> Unknown → PROCEED, never affirm — the [[safe-but-silent-default-needs-own-state]] edge.
        assertEquals(IssuerTrustSignal.Unknown, (null as HubIssuerTrust?).toIssuerTrustSignal())
        // and only NOT_TRUSTED produces the block through the full producer chain:
        assertEquals(RemoteFailure.IssuerNotTrusted("acme"), HubIssuerTrust.NOT_TRUSTED.toIssuerTrustSignal("acme").toRemoteFailure())
        assertNull((null as HubIssuerTrust?).toIssuerTrustSignal().toRemoteFailure())
    }

    @Test
    fun inertDefault_isUnknown_neverAFailure() = runTest {
        // The stub-parallel default is the honest UNKNOWN (no issuer determination wired yet) — it proceeds without
        // affirming trust, so the live flow is unchanged, and never fabricates a verdict.
        assertEquals(IssuerTrustSignal.Unknown, InertIssuerCheck.evaluate("hub-1"))
        assertNull(InertIssuerCheck.evaluate("hub-1").toRemoteFailure())
    }
}
