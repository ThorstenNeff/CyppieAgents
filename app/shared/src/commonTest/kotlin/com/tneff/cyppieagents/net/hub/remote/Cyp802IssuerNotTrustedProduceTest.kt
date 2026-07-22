package com.tneff.cyppieagents.net.hub.remote

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.issuer.InertIssuerCheck
import com.tneff.cyppieagents.net.hub.issuer.IssuerTrustCheck
import com.tneff.cyppieagents.net.hub.issuer.IssuerTrustSignal
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-802 (CYP-747 S1c) — the CLIENT-PRODUCE integration tooth (edge ③). Proves the CYP-797 `IssuerNotTrusted` render
 * arm is now REACHABLE through the REAL produce path (`IssuerTrustCheck` → `toRemoteFailure()` inside the session state
 * machine), NOT only fixture-constructed (the way `Cyp747IssuerNotTrustedRenderTest` had to, as the sole prior
 * construction site). A `NotTrusted` signal drives a terminal fail-closed LOST that mirrors the TrustChanged/
 * TrustRejected arms; the INERT default never fires, so today's live flow is unchanged.
 *
 * Stub-parallel: `issuerTrust` here is a client-local STUB signal — the real server-fed determination (edge ① + the
 * :core wire carrier edge ②) is the held final slice.
 */
class Cyp802IssuerNotTrustedProduceTest {

    private class FakeTunnel : NoiseTunnel {
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    private class FakeTransport : ClientNoiseTransport {
        var calls = 0
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel {
            calls++
            return FakeTunnel()
        }
    }

    private val pinned = HubTrust { TrustResolution.Pinned(ByteArray(32)) }
    private val grant = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }

    private fun session(
        scope: CoroutineScope,
        transport: ClientNoiseTransport = FakeTransport(),
        issuerTrust: IssuerTrustCheck = InertIssuerCheck, // the production default; overridden per-test
    ) = RemoteHubSession(
        "hub-1", transport, RelayDialer { NoopRelay() }, pinned, grant, scope, Backoff(),
        issuerTrust = issuerTrust,
    )

    @Test
    fun issuerNotTrusted_isProduced_terminalFailClosed_beforeHandshake() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        // A STUB axis-c signal: owned hub, no trusted issuer. The session must PRODUCE RemoteFailure.IssuerNotTrusted
        // through the real mapping — the CYP-797 render arm becomes reachable without a fixture constructing it.
        val s = session(scope, transport = transport, issuerTrust = { IssuerTrustSignal.NotTrusted("acme-cp-issuer") })
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.LOST, s.state.value.conn, "an untrusted issuer is terminal fail-closed LOST")
        assertEquals(RemoteFailure.IssuerNotTrusted("acme-cp-issuer"), s.state.value.failure)
        assertEquals(0, transport.calls, "no authority ⇒ never spend the E2E handshake (mirrors TrustChanged)")
        assertNull(s.tunnel, "a terminal issuer block leaves no live tunnel")
        scope.cancel()
    }

    @Test
    fun inertDefaultIssuerCheck_leavesLiveFlowUnchanged_reachesConnected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // The production default (InertIssuerCheck → NotApplicable): the issuer gate never fires, so the happy path
        // reaches CONNECTED exactly as before — proof the stub-parallel seam did not perturb today's behaviour.
        val s = session(scope)
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn)
        assertNull(s.state.value.failure, "the inert issuer gate produces no failure")
        assertTrue(s.tunnel != null, "the happy path still yields a live tunnel")
        scope.cancel()
    }
}
