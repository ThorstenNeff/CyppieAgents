package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.operator.vault.DecryptedKeyHold
import com.tneff.cyppieagents.net.hub.pool.PoolTunnelDialer
import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CYP-443 Slice 2 — the shared teardown harness reused across the CI-6 teardown-matrix teeth (`onCleared`,
 * `connectRemoteInternal` re-connect, error-path). It is the SAME recording-factory pattern Slice 1 introduced:
 * a real [RemoteHubSession] over fake crypto seams + observable per-connect secrets, injected through the real
 * [RemoteConnectComponentsFactory] ctor-seam so the VM's `activeComponents` — and thus `closeActiveComponents`
 * — run for real. `internal` top-level (no clash with Slice 1's private-nested copies; that file stays frozen).
 */

internal val HUB_A = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
internal val HUB_B = HubDescriptor("hub-b", "host-b", online = true, defaultPort = 8787, lastSeen = 1L)

/** The seeded decrypted-device-key bytes; a teardown must zeroize them so [DecryptedKeyHold.get] returns `null`. */
internal val SEEDED_KEY = byteArrayOf(7, 7, 7, 7)

internal class TdFakeTunnel : NoiseTunnel {
    var closed = false
    override val handshakeHash = ByteArray(32) { 0x11 }
    override suspend fun send(plaintext: ByteArray) {}
    override suspend fun receive(): ByteArray? = null
    override suspend fun close() { closed = true }
}

internal class TdNoopRelay : RelayChannel {
    override suspend fun send(frame: ByteArray) {}
    override suspend fun receive(): ByteArray? = null
    override suspend fun close() {}
}

/** Captures the session's handshake tunnel, so a teardown's `session.close()` is observable. */
internal class TdCapturingTransport : ClientNoiseTransport {
    val tunnels = mutableListOf<TdFakeTunnel>()
    override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
        TdFakeTunnel().also { tunnels += it }
}

/** A pool dialer whose dialed tunnels are observable — a teardown's `tunnelPool.close()` closes them. */
internal class TdFakePoolDialer : PoolTunnelDialer {
    val dialed = mutableListOf<TdFakeTunnel>()
    override suspend fun rendezvousSet(): List<String> = listOf("r0", "r1", "r2")
    override suspend fun dial(rendezvousId: String): NoiseTunnel = TdFakeTunnel().also { dialed += it }
}

/** Idle OOB ⇒ no mount at TRUST_CHECK ⇒ the session drives straight through to CONNECTED. */
internal class TdIdleOob : OobConfirmCoordinator {
    override val state: StateFlow<OobConfirmState> = MutableStateFlow(OobConfirmState.Idle)
    override fun approve() {}
    override fun reject() {}
    override suspend fun presentedStatic(hubId: String): ByteArray? = null
}

/**
 * A coordinator that raises a RAW (non-modeled, non-cancellation) error inside the connect drive: it reports an
 * `Awaiting` OOB state, but resolving its presented key throws — so `buildLiveOobMount` throws during the combine,
 * exercising the F1 error-path (`catch (Throwable)` ⇒ LOST + `closeActiveComponents`).
 */
internal class TdThrowingOob : OobConfirmCoordinator {
    override val state: StateFlow<OobConfirmState> = MutableStateFlow(OobConfirmState.Awaiting("hub-a", "fp"))
    override fun approve() {}
    override fun reject() {}
    override suspend fun presentedStatic(hubId: String): ByteArray? =
        throw RuntimeException("boom: a raw error in the connect drive (F1)")
}

internal class TdRecordingEnroll : EnrollConfirmCoordinator {
    var aborted = false
    override val state: StateFlow<EnrollConfirmState> = MutableStateFlow(EnrollConfirmState.Idle)
    override suspend fun confirmSavedCodes(codes: List<String>): Boolean = true
    override fun confirmSaved() {}
    override fun abort() { aborted = true }
}

internal class TdRecordingPassphrase : PassphrasePromptCoordinator {
    var clearPreArmCalled = false
    override val state: StateFlow<PassphrasePromptState> = MutableStateFlow(PassphrasePromptState.Idle)
    override suspend fun prompt(reason: UvReason): CharArray? = null
    override fun submit(passphrase: CharArray) {}
    override fun cancel() {}
    override fun preArm(passphrase: CharArray) {}
    override fun clearPreArm() { clearPreArmCalled = true }
}

internal class TdRec(
    val comps: RemoteConnectComponents,
    val sessionTransport: TdCapturingTransport,
    val poolDialer: TdFakePoolDialer,
)

/**
 * A recording [RemoteConnectComponentsFactory]: each `create` appends a [TdRec] to [recs] and returns real-ish
 * components (real [RemoteHubSession] over fake seams that GRANT → reaches CONNECTED). [oob] selects the OOB
 * coordinator (idle = drive to CONNECTED; throwing = F1). [seedKeyInFactory] pre-seeds the [DecryptedKeyHold]
 * at construction (for the error-path, where the throw fires before the test can seed post-connect).
 */
internal fun recordingFactory(
    recs: MutableList<TdRec>,
    oob: () -> OobConfirmCoordinator = { TdIdleOob() },
    seedKeyInFactory: Boolean = false,
): RemoteConnectComponentsFactory = RemoteConnectComponentsFactory { h, sessionScope ->
    val transport = TdCapturingTransport()
    val poolDialer = TdFakePoolDialer()
    val session = RemoteHubSession(
        hubId = h.hubId,
        transport = transport,
        dialer = RelayDialer { TdNoopRelay() },
        trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
        authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
        scope = sessionScope,
        backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
    )
    val keyHold = DecryptedKeyHold(nowMs = { 0L })
    if (seedKeyInFactory) keyHold.put(SEEDED_KEY.copyOf(), expiresAtMs = Long.MAX_VALUE)
    val comps = RemoteConnectComponents(
        session = session,
        oobConfirm = oob(),
        enrollConfirm = TdRecordingEnroll(),
        tunnelPool = PooledTunnelSource(dialer = poolDialer, nowMs = { 0L }),
        passphrasePrompt = TdRecordingPassphrase(),
        keyHold = keyHold,
    )
    recs += TdRec(comps, transport, poolDialer)
    comps
}

/** Build a VM wired to the recording [factory] over the CYP-419 stubs (hubs = [HUB_A], [HUB_B]). */
internal fun makeTeardownVm(scope: CoroutineScope, factory: RemoteConnectComponentsFactory): HubConnectViewModel =
    HubConnectViewModel(
        controlPlane = StubControlPlaneClient(hubs = listOf(HUB_A, HUB_B)),
        credentials = StubHubCredentialRepository(),
        connectFeed = StubLocalConnectFeed(),
        remoteComponentsFactory = factory,
        scope = scope,
    )
