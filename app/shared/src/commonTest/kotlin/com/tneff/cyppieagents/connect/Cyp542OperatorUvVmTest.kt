package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.operator.vault.EnrollOutcome
import com.tneff.cyppieagents.net.hub.operator.vault.OperatorEnrollController
import com.tneff.cyppieagents.net.hub.operator.vault.StrengthVerdict
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-542 / B1 — the operator-UV VM surface (AC-1/AC-2/AC-4), tested against a fake per-connect session (no render).
 * Pins: **AC-1** DeviceNotEnrolled routes to the set-passphrase step (NOT the retry-reloop; INERT-null enroll keeps the
 * old LOST fallback); **AC-2** a successful enroll pre-arms the just-set passphrase + auto-reconnects, so the FIRST auth
 * UV reuses it (no 2nd prompt) and the session reaches CONNECTED; a typed refusal (TooWeak) surfaces ERROR + does NOT
 * pre-arm; **AC-4** the auth-time passphrase prompt is surfaced during AUTHENTICATING, submit drives CONNECTED, and a
 * cancel (abort) bubbles to LOST/OperatorUvFailed. The VM runs on an [UnconfinedTestDispatcher] so launches settle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp542OperatorUvVmTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)

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

    private val handshakingTransport = object : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel = FakeTunnel()
    }

    private class IdleOob : OobConfirmCoordinator {
        override val state: StateFlow<OobConfirmState> = MutableStateFlow(OobConfirmState.Idle)
        override fun approve() {}
        override fun reject() {}
        override suspend fun presentedStatic(hubId: String): ByteArray? = null
    }

    private class FakeEnroll(private val outcome: EnrollOutcome) : OperatorEnrollController {
        var enrolledWith: String? = null
        override fun suggestPassphrase(): CharArray = "Zephyr7!mQ anchor-mint Kx9vB".toCharArray()
        override fun validate(passphrase: CharArray): StrengthVerdict = StrengthVerdict.OK
        override suspend fun enroll(passphrase: CharArray): EnrollOutcome {
            enrolledWith = passphrase.concatToString(); return outcome
        }
    }

    private fun CoroutineScope.session(auth: OperatorAuthenticator) = RemoteHubSession(
        hubId = hub.hubId,
        transport = handshakingTransport,
        dialer = RelayDialer { NoopRelay() },
        trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
        authenticator = auth,
        scope = this,
        backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
    )

    private fun TestScope.vm(
        auth: OperatorAuthenticator,
        coordinator: PassphrasePromptCoordinator? = null,
        enroll: OperatorEnrollController? = null,
    ): Pair<HubConnectViewModel, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RemoteConnectComponentsFactory { _, sessionScope ->
            RemoteConnectComponents(
                sessionScope.session(auth), IdleOob(),
                passphrasePrompt = coordinator, enroll = enroll,
            )
        }
        val m = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = listOf(hub)),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteComponentsFactory = factory,
            scope = scope,
        )
        return m to scope
    }

    @Test
    fun deviceNotEnrolled_routesToSetPassphrase_notRetryReloop() = runTest {
        val (m, scope) = vm(
            auth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.DeviceNotEnrolled },
            coordinator = LivePassphrasePromptCoordinator(),
            enroll = FakeEnroll(EnrollOutcome.Enrolled),
        )
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.SetPassphrase>(m.state.value)
        assertEquals(EnrollPhase.ENTERING, s.phase, "AC-1: DeviceNotEnrolled ⇒ the set-passphrase step, not LOST/retry")
        scope.cancel()
    }

    @Test
    fun deviceNotEnrolled_noEnrollController_fallsBackToLost_inert() = runTest {
        val (m, scope) = vm(auth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.DeviceNotEnrolled }) // no coordinator/enroll
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(m.state.value)
        assertEquals(RemoteConnState.LOST, s.remote.conn)
        assertEquals(RemoteFailure.DeviceNotEnrolled, s.remote.failure, "INERT: with no enroll controller the old LOST fallback holds")
        scope.cancel()
    }

    @Test
    fun setEnrollPassphrase_enrolled_preArmsAndAutoReconnectsToConnected() = runTest {
        val coordinator = LivePassphrasePromptCoordinator()
        val enroll = FakeEnroll(EnrollOutcome.Enrolled)
        var authCalls = 0
        val auth = OperatorAuthenticator { _, _ ->
            authCalls++
            if (authCalls == 1) OperatorAuthOutcome.DeviceNotEnrolled // first connect: this device isn't set up
            else {
                val pw = coordinator.prompt(UvReason.OPERATOR_AUTH) // AC-2: pre-armed ⇒ resolves immediately (no prompt)
                if (pw != null) OperatorAuthOutcome.Granted else OperatorAuthOutcome.UvFailed
            }
        }
        val (m, scope) = vm(auth, coordinator, enroll)
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        assertIs<HubConnectUiState.SetPassphrase>(m.state.value)

        m.setEnrollPassphrase("Basalt5#harbor Qw2nV zephyr".toCharArray()); advanceUntilIdle()
        assertEquals("Basalt5#harbor Qw2nV zephyr", enroll.enrolledWith, "the entered passphrase was sealed by the core enroll")
        val s = assertIs<HubConnectUiState.RemoteConnecting>(m.state.value)
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn, "AC-2: enroll auto-drives the reconnect to CONNECTED via the pre-armed UV")
        assertEquals(2, authCalls, "AC-2: exactly one reconnect; the pre-arm fed the 2nd auth (no re-prompt)")
        scope.cancel()
    }

    @Test
    fun setEnrollPassphrase_tooWeak_surfacesErrorPhase_doesNotPreArm() = runTest {
        val coordinator = LivePassphrasePromptCoordinator()
        val (m, scope) = vm(
            auth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.DeviceNotEnrolled },
            coordinator = coordinator,
            enroll = FakeEnroll(EnrollOutcome.TooWeak),
        )
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        assertIs<HubConnectUiState.SetPassphrase>(m.state.value)

        m.setEnrollPassphrase("weak".toCharArray()); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.SetPassphrase>(m.state.value)
        assertEquals(EnrollPhase.ERROR, s.phase)
        assertEquals(EnrollOutcome.TooWeak, s.outcome, "a refusal surfaces the typed cause for distinct copy")
        // no pre-arm on the refusal path: a fresh prompt actually prompts (would auto-resolve if wrongly armed).
        val pending = async { coordinator.prompt(UvReason.OPERATOR_AUTH) }
        advanceUntilIdle()
        assertIs<PassphrasePromptState.Prompting>(coordinator.state.value, "a refused enroll must NOT pre-arm the coordinator")
        coordinator.cancel(); pending.await(); scope.cancel()
    }

    @Test
    fun passphrasePrompt_surfacedDuringAuth_submitDrivesConnected() = runTest {
        val coordinator = LivePassphrasePromptCoordinator()
        val auth = OperatorAuthenticator { _, _ ->
            val pw = coordinator.prompt(UvReason.OPERATOR_AUTH)
            if (pw != null) OperatorAuthOutcome.Granted else OperatorAuthOutcome.UvFailed
        }
        val (m, scope) = vm(auth, coordinator, FakeEnroll(EnrollOutcome.Enrolled))
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        assertIs<HubConnectUiState.PassphrasePrompt>(m.state.value)

        m.submitPassphrase("Basalt5#harbor Qw2nV zephyr".toCharArray()); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(m.state.value)
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn, "submit ⇒ the UV grants ⇒ CONNECTED")
        scope.cancel()
    }

    @Test
    fun passphrasePrompt_cancel_bubblesToLostUvFailed_abortPath() = runTest {
        val coordinator = LivePassphrasePromptCoordinator()
        val auth = OperatorAuthenticator { _, _ ->
            val pw = coordinator.prompt(UvReason.OPERATOR_AUTH)
            if (pw != null) OperatorAuthOutcome.Granted else OperatorAuthOutcome.UvFailed
        }
        val (m, scope) = vm(auth, coordinator, FakeEnroll(EnrollOutcome.Enrolled))
        m.start(); advanceUntilIdle(); m.selectHub(hub); m.connectRemote(); advanceUntilIdle()
        assertIs<HubConnectUiState.PassphrasePrompt>(m.state.value)

        m.cancelPassphrase(); advanceUntilIdle()
        val s = assertIs<HubConnectUiState.RemoteConnecting>(m.state.value)
        assertEquals(RemoteConnState.LOST, s.remote.conn)
        assertEquals(RemoteFailure.OperatorUvFailed, s.remote.failure, "AC-4: cancel (abort) ⇒ LOST/OperatorUvFailed, never a hub reject")
        scope.cancel()
    }
}
