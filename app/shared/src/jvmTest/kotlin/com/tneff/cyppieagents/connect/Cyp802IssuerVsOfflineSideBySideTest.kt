package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.net.hub.issuer.DescriptorIssuerCheck
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialException
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-802 (axis-c) — **IssuerNotTrusted vs an actually-offline hub, SIDE BY SIDE in one run** (QA slice F5).
 *
 * The "not conflated with offline" property was proven only STRUCTURALLY (`Cyp443TrustAxisSeparationGuardTest`
 * source-scan) and by TIMING (`Cyp802IssuerNotTrustedProduceTest`: the issuer block is produced before the Noise
 * handshake). No test drove BOTH terminal outcomes in the SAME run and asserted they stay distinct — the behavioral
 * gap this closes. And the distinction is sharper than two different failure *values*: reading the state machine,
 * `IssuerNotTrusted` is a **TERMINAL** hard block (`attemptConnect` sets `conn = LOST` + returns TERMINAL — no retry,
 * OOB-only recovery), while an offline/dial failure is **TRANSIENT** (the failure is surfaced but the session keeps
 * reconnecting, `conn` never LOST). Conflating them would be a real bug in EITHER direction: retry-looping forever on
 * a permanent authority refusal, or permanently stranding a hub that is merely temporarily offline.
 *
 * Non-vacuity is the pair driven together: the issuer hub reaches a terminal LOST carrying `IssuerNotTrusted`, the
 * offline hub surfaces `HubOffline` in a NON-terminal (still-reconnecting) state — distinct in BOTH value and
 * terminality. Both use the same pinned-trust + granted-auth harness so the ONLY difference is issuer-posture vs
 * reachability.
 */
class Cyp802IssuerVsOfflineSideBySideTest {

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
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel = FakeTunnel()
    }

    private val pinned = HubTrust { TrustResolution.Pinned(ByteArray(32)) }
    private val grant = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }

    @Test
    fun issuerNotTrusted_isTerminal_whileOffline_isTransient_theTwoCausesAreNotConflated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // ── Case A: an OWNED hub whose issuer is NOT trusted. Dial + pin succeed; the issuer gate fires → TERMINAL.
            val issuerSession = buildRemoteHubSession(
                hubId = "hub-issuer-not-trusted",
                transport = FakeTransport(), dialer = RelayDialer { NoopRelay() },
                trust = pinned, authenticator = grant, scope = scope,
                issuerTrust = DescriptorIssuerCheck(HubIssuerTrust.NOT_TRUSTED),
            )
            issuerSession.start()
            val issuerTerminal = withTimeout(5_000) { issuerSession.state.first { it.conn == RemoteConnState.LOST } }

            // ── Case B: a TRUSTED-issuer hub that is merely OFFLINE (the dial fails). Surfaces HubOffline and keeps
            //    reconnecting — it must NOT be a terminal LOST (that would strand a transient outage as permanent).
            val offlineSession = buildRemoteHubSession(
                hubId = "hub-offline",
                transport = FakeTransport(), dialer = RelayDialer { throw RelayDialException(RemoteFailure.HubOffline) },
                trust = pinned, authenticator = grant, scope = scope,
                issuerTrust = DescriptorIssuerCheck(HubIssuerTrust.TRUSTED),
            )
            offlineSession.start()
            val offlineState = withTimeout(5_000) { offlineSession.state.first { it.failure == RemoteFailure.HubOffline } }

            // ── The side-by-side: distinct in BOTH value AND terminality — neither conflated with the other. ──────────
            assertEquals(RemoteFailure.IssuerNotTrusted(null), issuerTerminal.failure,
                "an owned-but-issuer-not-trusted hub produces the IssuerNotTrusted cause")
            assertEquals(RemoteConnState.LOST, issuerTerminal.conn,
                "issuer-not-trusted is a TERMINAL hard block (no retry) — conn = LOST")

            assertEquals(RemoteFailure.HubOffline, offlineState.failure,
                "an offline hub produces the HubOffline cause, NOT IssuerNotTrusted")
            assertNotEquals(RemoteConnState.LOST, offlineState.conn,
                "offline is TRANSIENT — the failure is surfaced but the session keeps reconnecting (never a terminal LOST)")

            assertNotEquals<RemoteFailure?>(issuerTerminal.failure, offlineState.failure,
                "IssuerNotTrusted != HubOffline — the two causes are not conflated in the same run")
            assertTrue(
                issuerTerminal.failure is RemoteFailure.IssuerNotTrusted && offlineState.failure is RemoteFailure.HubOffline,
                "distinct typed causes: a permanent authority refusal (terminal) vs a transient reachability outage",
            )
        } finally {
            scope.cancel()
        }
    }
}
