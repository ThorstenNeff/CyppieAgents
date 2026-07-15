package com.tneff.cyppieagents.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.net.hub.HubTransport
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
import com.tneff.cyppieagents.net.hub.mux.ClientMuxSession
import com.tneff.cyppieagents.net.hub.mux.MuxedStreamSource
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.operator.vault.EnrollOutcome
import com.tneff.cyppieagents.net.hub.operator.vault.OperatorEnrollController
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CYP-419 (Epic CYP-395 S-L) — the hubConnect flow state, modelled on the [AuthViewModel]/`AuthGate` idiom (one
 * sealed state driving a `when`, no nav library). Covers the post-login machine: load hubs → **empty ⇒ Seq A**
 * register + credentials + ready (first-start); **non-empty ⇒ Seq B** select + mode + connect. Login itself (A1/B1)
 * is the reused `AuthGate`, wrapped by the host composable — this state begins once a session is verified.
 *
 * **Honesty is encoded here, not hoped for:** the connect cause is taken verbatim from the [LocalConnectFeed]
 * (never guessed); Registry-presence (`HubDescriptor.online`) is separate from the connect progress (H1);
 * `connected` is set ONLY on the feed's `Connected` (never before LIVE); the Q5 credential gate lets `UNREACHABLE`
 * proceed with a WARN but blocks `INVALID`.
 */
class HubConnectViewModel(
    private val controlPlane: ControlPlaneClient,
    private val credentials: HubCredentialRepository,
    private val connectFeed: LocalConnectFeed,
    /** CYP-471 §7 — the remote (Noise-E2E) connect feed. Stub-driven until RR5 (defaulted so existing callers are unaffected). */
    private val remoteConnectFeed: RemoteConnectFeed = StubRemoteConnectFeed(),
    /** CYP-510 — the live OOB-confirm source (trust layer's OobConfirmState + approve/reject + presented key).
     *  `null` ⇒ INERT: the remote connect path is byte-identical to today (no live FirstUse screen). */
    private val oobConfirm: OobConfirmCoordinator? = null,
    /** CYP-513 — the LIVE per-connect components factory (activation): produces the session + its ①²-shared OOB
     *  coordinator together (display == pinned). When present it takes precedence over [remoteConnectFeed] +
     *  [oobConfirm] (the flag-gated App.kt wiring); `null` ⇒ INERT (the CYP-510/471 feed path). */
    private val remoteComponentsFactory: RemoteConnectComponentsFactory? = null,
    /** Hostname default for the editable hub-name field (Q1); the host injects the real device hostname. */
    private val defaultHubName: String = "mein-hub",
    /** M2 Seam-3 (c) — the operator's **CP-scoped** credential the bridged request carries to the hub (NEVER the static
     *  god-token, Reviewer Axis-1). HELD as a seam (`{ null }`) until Backend's tunnel-scoped listener contract lands;
     *  App.kt then injects the real CP-scoped provider. INERT default keeps the datapath proof credential-agnostic. */
    private val remoteSessionToken: () -> String? = { null },
    /** CYP-620 — the mux-transport gate (default = the real deploy flag). Injectable so a test drives the flag-ON path
     *  without the env-based `expect fun`. */
    private val muxEnabled: () -> Boolean = { remoteMuxTransportEnabled() },
    /** CYP-620 — a test hook fired when a `ClientMuxSession` is (re)built over a carrier (default no-op). Lets a test
     *  pin the flag-gating + the reference-identity guard (one attach per new tunnel, not per healthy re-emission). */
    private val onMuxAttached: (NoiseTunnel) -> Unit = {},
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow<HubConnectUiState>(HubConnectUiState.Preparing)
    val state: StateFlow<HubConnectUiState> = _state.asStateFlow()

    /** A0/entry: load the hub list; route to Seq A (empty ⇒ first-start register) or Seq B (non-empty ⇒ select). */
    fun start() {
        _state.value = HubConnectUiState.LoadingHubs
        runScope.launch {
            val hubs = runCatching { controlPlane.hubs() }.getOrElse {
                // H5: the CP must be reachable for the first sign-in / hub list — an honest error, never a hang.
                _state.value = HubConnectUiState.HubsUnreachable
                return@launch
            }
            // CYP-530 (PO flow-decision): list+select model — register is VESTIGIAL (hubs self-admit to the CP). An
            // empty list is the HONEST empty HubList ("noch keine Hubs" + Refresh, Δ2), NEVER the dormant Register
            // screen. Register/Seq-A stay in the code (unreachable in M1; retained for BYOA-later), just not the empty
            // destination — so the operator never lands on a register CTA that leads nowhere.
            _state.value = HubConnectUiState.HubList(hubs)
        }
    }

    fun retryLoadHubs() = start()

    // --- Seq A: registration (A2) ---

    fun onHubNameChange(name: String) {
        val s = _state.value
        if (s is HubConnectUiState.Register && s.phase != RegisterPhase.REGISTERING) {
            _state.value = s.copy(name = name, phase = RegisterPhase.EDITING)
        }
    }

    /** Register this hub with the CP (device-code automatic on desktop, Q7). On success → credential step (A3). */
    fun register() {
        val s = _state.value as? HubConnectUiState.Register ?: return
        if (s.name.isBlank()) return
        _state.value = s.copy(phase = RegisterPhase.REGISTERING)
        runScope.launch {
            runCatching { controlPlane.registerHub(s.name) }
                .onSuccess {
                    val masked = runCatching { credentials.current() }.getOrNull()
                    _state.value = HubConnectUiState.Credentials(masked, CredentialPhase.ENTERING)
                }
                .onFailure { _state.value = s.copy(phase = RegisterPhase.ERROR) }
        }
    }

    // --- Seq A: credentials (A3) ---

    /** Store + validate the Anthropic credential. The tri-state outcome (§7/S-3) drives the phase; masking is
     *  server-side (H3 — the client never renders plaintext). `hinterlegt` (masked) is set even on INVALID. */
    fun submitCredential(apiKey: String) {
        val s = _state.value as? HubConnectUiState.Credentials ?: return
        if (apiKey.isBlank()) return
        _state.value = s.copy(phase = CredentialPhase.VALIDATING)
        runScope.launch {
            val outcome = runCatching { credentials.submit(apiKey) }.getOrElse {
                // A submit that itself throws is treated as UNREACHABLE (couldn't verify), never as INVALID (H3).
                _state.value = s.copy(phase = CredentialPhase.UNREACHABLE)
                return@launch
            }
            _state.value = HubConnectUiState.Credentials(
                masked = outcome.masked,
                phase = when (outcome.validation) {
                    CredentialValidation.VALIDATED -> CredentialPhase.VALIDATED
                    CredentialValidation.INVALID -> CredentialPhase.INVALID
                    CredentialValidation.UNREACHABLE -> CredentialPhase.UNREACHABLE
                },
            )
        }
    }

    /** Q5 gate: proceed to A4 only when credentials are **hinterlegt** and NOT `INVALID`; `UNREACHABLE` may proceed
     *  (WARN — the key may be valid, Anthropic transiently down). Returns false (no transition) when blocked. */
    fun continueToReady(): Boolean {
        val s = _state.value as? HubConnectUiState.Credentials ?: return false
        val stored = s.masked != null
        val blocked = s.phase == CredentialPhase.INVALID || !stored || s.phase == CredentialPhase.ENTERING
        if (blocked) return false
        _state.value = HubConnectUiState.Ready
        return true
    }

    // --- Seq B: hub selection (B2) → mode (B3) ---

    fun selectHub(hub: HubDescriptor) {
        if (_state.value is HubConnectUiState.HubList) _state.value = HubConnectUiState.ChoosingMode(hub)
    }

    /** Empty-state / "add another hub" → the register flow (Seq A from A2). */
    fun registerNewHub() {
        _state.value = HubConnectUiState.Register(defaultHubName, RegisterPhase.EDITING)
    }

    // --- Seq B / §6: local connect ---

    /** B3 "Verbinden" → the honest §6 progress. The cause on failure comes from the feed — never guessed here. */
    fun connectLocal() {
        val hub = (_state.value as? HubConnectUiState.ChoosingMode)?.hub
            ?: (_state.value as? HubConnectUiState.Connecting)?.hub
            ?: return
        _state.value = HubConnectUiState.Connecting(hub, ConnectProgress.Attempting)
        runScope.launch {
            connectFeed.connect(hub).collect { progress ->
                _state.value = HubConnectUiState.Connecting(hub, progress)
            }
        }
    }

    // --- CYP-471 §7: remote (Noise-E2E) connect + Q5 hub-switch ---

    /** The active remote collect job — held so a hub switch (Q5) tears it down before starting a new one. */
    private var remoteJob: Job? = null

    /** CYP-513 — the active LIVE components (Q5): closed on a hub switch / leave so the old Noise session tears down. */
    private var activeComponents: RemoteConnectComponents? = null

    /** M2 Seam-3 (a) — the tunnel-backed transport, built ONCE on CONNECTED from the live session; closed on switch/leave. */
    private var remoteTransport: HubTransport? = null

    // CYP-620 Step-2b (mux datapath) — the current mux source, SWAPPED across reconnects while the transport (and its
    // loopback ports) stay STABLE. A [MutableStateFlow] so the swap is cross-coroutine-safe: the transport's accept-loops
    // read `.value`, the connect-collect writes it (a plain var would risk a stale/invisible read → a broken reconnect
    // datapath). [currentMuxCarrier] is the reference-identity guard — read/written ONLY in the single connect-collect
    // coroutine, so a plain var is safe: rebuild the mux only when `session.tunnel` is a NEW instance (first connect OR
    // reconnect), never on a healthy `combine` re-emission. [currentMuxSession] is held so a hub-switch closes it cleanly.
    private val currentMuxSource = MutableStateFlow<MuxedStreamSource?>(null)
    private var currentMuxCarrier: NoiseTunnel? = null
    private var currentMuxSession: ClientMuxSession? = null

    /**
     * B3 Remote "Verbinden" / Q5 "auf Hub wechseln" → the honest §7 remote progress against
     * [RemoteConnectFeed]. **Exactly one hub (Q5, CI-6):** any active remote session is torn down first
     * (its collect is cancelled → the live feed closes the Noise session on cancellation) — nothing is carried
     * across. `connected` is reached ONLY on `RemoteConnState.CONNECTED`; the cause on failure is the feed's
     * `RemoteFailure`, never guessed.
     */
    fun connectRemote() {
        val hub = (_state.value as? HubConnectUiState.ChoosingMode)?.hub
            ?: (_state.value as? HubConnectUiState.RemoteConnecting)?.hub
            ?: (_state.value as? HubConnectUiState.SetPassphrase)?.hub // AC-1/2: reconnect from the set-passphrase step
            ?: return
        connectRemoteInternal(hub, preArm = null)
    }

    /** [connectRemote] core. [preArm] (AC-2) arms the freshly-built session's coordinator AFTER the prior teardown
     *  ([closeActiveComponents]' P1 clear runs first, so the enroll passphrase can't be wiped) and BEFORE the new
     *  session authenticates ⇒ the first UV reuses it (no 2nd prompt); the UV zeroizes it after (P2). */
    private fun connectRemoteInternal(hub: HubDescriptor, preArm: CharArray?) {
        remoteJob?.cancel() // Q5: exactly-one-hub — tear the current remote session down before the new one.
        closeActiveComponents() // CYP-513: close the previous LIVE Noise session (Q5, nothing carried across)
        _state.value = HubConnectUiState.RemoteConnecting(hub, RemoteSessionState(hub.hubId, RemoteConnState.RELAY_DIALING))
        val factory = remoteComponentsFactory
        val coordinator = oobConfirm
        remoteJob = runScope.launch {
            // F1 (silent-swallow fix): guard the whole connect drive. A RAW throw (NOT a modeled RemoteFailure) from
            // factory.create / session.start / the combine flow would otherwise escape this launch and strand the UI
            // on the RELAY_DIALING spinner forever (the CYP-575 hang class). The catch below surfaces an honest state.
            try {
            when {
                factory != null -> {
                    // CYP-513 LIVE: per-connect components — the session's TofuHubTrust and this OOB coordinator
                    // share ONE of(hub)+PendingOobConfirmations (①②, display == pinned; approve/reject wake the
                    // trust's own waiter). Drive the session; surface the FirstUse confirm at TRUST_CHECK while Awaiting.
                    val comps = factory.create(hub, this)
                    activeComponents = comps
                    preArm?.let { comps.passphrasePrompt?.preArm(it) } // AC-2: reuse the enroll passphrase for the FIRST UV
                    comps.session.start()
                    try {
                        // 4-way: session truth + OOB (TRUST_CHECK) + the §2 first-enroll reveal + the CYP-542/B1
                        // auth-time passphrase prompt (during AUTHENTICATING). The prompt flow is Idle when INERT (no
                        // coordinator wired) ⇒ byte-identical to the pre-B1 3-way surface.
                        val promptFlow = comps.passphrasePrompt?.state ?: MutableStateFlow(PassphrasePromptState.Idle)
                        combine(comps.session.state, comps.oobConfirm.state, comps.enrollConfirm.state, promptFlow) { rs, oob, enroll, prompt ->
                            surfaceRemote(hub, rs, buildLiveOobMount(comps.oobConfirm, hub, oob), enroll, prompt, comps.enroll)
                            rs
                        }.collect { rs ->
                                // M2 Seam-3 (a): build the tunnel-backed transport ONCE on CONNECTED. CYP-537 (M2-A):
                                // the source is the N-tunnel POOL (`tunnelPool.acquire()` — a distinct authenticated
                                // tunnel per concurrent connection, F-M2-1 fix); if no pool (thru-cut/stub), fall back
                                // to the single-flight session tunnel (auto-rebinds on relay-drop; stable loopback
                                // port, Seam-6). null on non-Desktop (Path-A). Torn down in closeActiveComponents.
                                if (rs.conn == RemoteConnState.CONNECTED) {
                                    // CYP-620 Step-2b (mux mode, flag-gated): (re)span a ClientMuxSession over the CURRENT
                                    // carrier when `session.tunnel` is a NEW instance — first connect OR reconnect, by
                                    // reference identity so a healthy `combine` re-emission (oob/enroll/prompt change while
                                    // still CONNECTED) does NOT rebuild. run() ends on carrier-drop → reportDropped() tells
                                    // RemoteHubSession to re-dial → the next CONNECTED spans a fresh mux over the new tunnel.
                                    val carrier = comps.session.tunnel
                                    if (muxEnabled() && carrier != null && carrier !== currentMuxCarrier) {
                                        currentMuxCarrier = carrier
                                        val mux = ClientMuxSession(carrier, runScope)
                                        currentMuxSession = mux
                                        currentMuxSource.value = MuxedStreamSource(mux)
                                        onMuxAttached(carrier)
                                        runScope.launch { mux.run(); comps.session.reportDropped() }
                                    }
                                    // Build the loopback transport ONCE (stable ports — never rebuilt, or the workspace's
                                    // base-urls would break). Its acquireTunnel delegates to the CURRENT source: the mux
                                    // (the StateFlow, swapped across reconnects) when the flag is on — CONTROL→streamClass-0
                                    // via MuxedStreamSource (CAUTION-1); else the N-tunnel pool (CYP-537); else the
                                    // single-flight session tunnel (thru-cut/stub). A null mid-reconnect → CYP-619
                                    // hold(DATA)/fail-closed(CONTROL) in the transport (unchanged).
                                    if (remoteTransport == null) {
                                        val pool = comps.tunnelPool
                                        remoteTransport = buildRemoteHubTransport(
                                            acquireTunnel =
                                                if (muxEnabled()) { { lane -> currentMuxSource.value?.acquire(lane) } }
                                                else if (pool != null) { { lane -> pool.acquire(lane) } }
                                                else { { _ -> activeComponents?.session?.tunnel } },
                                            sessionToken = remoteSessionToken,
                                            scope = runScope,
                                        )
                                    }
                                }
                            }
                    } finally {
                        withContext(NonCancellable) { comps.session.close() } // Q5 teardown on cancel / switch
                    }
                }
                coordinator != null -> {
                    // CYP-510: the fake/stub feed + a live OOB coordinator (tests / pre-activation).
                    combine(remoteConnectFeed.connect(hub), coordinator.state) { rs, oob -> rs to oob }
                        .collect { (rs, oob) -> surfaceRemote(hub, rs, buildLiveOobMount(coordinator, hub, oob)) }
                }
                else -> {
                    // INERT (CYP-471): no OOB source ⇒ byte-identical to the stub feed path.
                    remoteConnectFeed.connect(hub).collect { rs ->
                        _state.value = HubConnectUiState.RemoteConnecting(hub, rs)
                    }
                }
            }
            } catch (c: CancellationException) {
                throw c // Q5 teardown / hub-switch — never swallowed (the cancel path closes the session via finally)
            } catch (e: Throwable) {
                // F1: an unexpected RAW error in the connect drive ⇒ honest terminal LOST (never a stuck
                // RELAY_DIALING spinner). No typed RemoteFailure is fabricated — the cause is genuinely unknown, so
                // LOST(failure=null) is the honest state (the ConnectingView renders it as ended, with a retry).
                // Assist Finding-2: set LOST FIRST — a throw from the best-effort teardown below (closing a
                // half-built session in the error path) must NOT undo it and re-strand the spinner (the very hang
                // class this closes). The teardown is then best-effort; its own failure can't erase the LOST above.
                _state.value = HubConnectUiState.RemoteConnecting(hub, RemoteSessionState(hub.hubId, RemoteConnState.LOST))
                runCatching { closeActiveComponents() }
            }
        }
    }

    /** Surface priority (phase order): OOB Awaiting ⇒ the mandatory confirm at TRUST_CHECK; the B1 auth-time passphrase
     *  prompt (during AUTHENTICATING) ⇒ [HubConnectUiState.PassphrasePrompt]; §2 first-enroll Revealing ⇒ the
     *  RecoveryCodesReveal; **AC-1** DeviceNotEnrolled (+ an enroll controller) ⇒ the set-passphrase step (never the
     *  retry-reloop); else the session truth. */
    private fun surfaceRemote(
        hub: HubDescriptor,
        rs: RemoteSessionState,
        mount: OobConfirmMount?,
        enroll: EnrollConfirmState = EnrollConfirmState.Idle,
        passphrase: PassphrasePromptState = PassphrasePromptState.Idle,
        enrollController: OperatorEnrollController? = null,
    ) {
        _state.value = when {
            mount != null ->
                HubConnectUiState.RemoteConnecting(hub, RemoteSessionState(hub.hubId, RemoteConnState.TRUST_CHECK), oobConfirm = mount)
            passphrase is PassphrasePromptState.Prompting ->
                HubConnectUiState.PassphrasePrompt(hub, passphrase.reason) // B1: the real UV is asking for the App-Passphrase
            enroll is EnrollConfirmState.Revealing -> HubConnectUiState.RevealCodes(hub, enroll.codes)
            rs.failure == RemoteFailure.DeviceNotEnrolled && enrollController != null ->
                // AC-1: this device isn't set up ⇒ the enroll step, NOT the :346 retry-reloop. Keep an in-progress
                // enroll sub-state (ENROLLING/ERROR set by setEnrollPassphrase) — only INITIATE ENTERING, never clobber.
                (_state.value as? HubConnectUiState.SetPassphrase) ?: HubConnectUiState.SetPassphrase(hub, EnrollPhase.ENTERING)
            else -> HubConnectUiState.RemoteConnecting(hub, rs)
        }
    }

    // --- CYP-542 / B1: auth-time passphrase prompt (AC-4) + set-passphrase enroll (AC-1/AC-2) ---

    /** AC: the operator entered their App-Passphrase in the CYP-460 dialog → resolve the pending UV prompt with it.
     *  The UV opens the vault; a wrong passphrase re-prompts inline (the dialog stays up), never a session teardown. */
    fun submitPassphrase(passphrase: CharArray) {
        activeComponents?.passphrasePrompt?.submit(passphrase)
    }

    /** AC-4: the operator cancelled the passphrase prompt → resolve `null` ⇒ Denied(CANCELLED) ⇒ the session bubbles
     *  to LOST/OperatorUvFailed (abort is the ONLY non-lockout path that tears the session down). */
    fun cancelPassphrase() {
        activeComponents?.passphrasePrompt?.cancel()
    }

    /** AC-1: the strong one-click diceware default for the set-passphrase field (or `null` if the EFF asset is
     *  unavailable ⇒ the dialog offers only type-your-own). The caller (dialog) zeroizes it after use. */
    fun suggestPassphrase(): CharArray? = activeComponents?.enroll?.suggestPassphrase()

    /**
     * AC-1/AC-2: seal the device-key vault under the chosen App-Passphrase, then auto-reconnect. On [EnrollOutcome.Enrolled]
     * the passphrase is **pre-armed** (AC-2: the first auth UV reuses it, no 2nd prompt; the UV zeroizes it after — the
     * flow must NOT zeroize a pre-armed array, P2) and [connectRemote] auto-drives AUTH→CONNECTED. A typed refusal
     * (TooWeak / Blocklisted / MigrationFailed / AlreadyEnrolled) surfaces the ERROR phase; the un-armed passphrase is
     * zeroized here (H-1). The floor is CORE-enforced inside [OperatorEnrollController.enroll] (F-#4), never a UI gate.
     */
    fun setEnrollPassphrase(passphrase: CharArray) {
        val s = _state.value as? HubConnectUiState.SetPassphrase ?: return
        val comps = activeComponents ?: return
        val controller = comps.enroll ?: return
        _state.value = s.copy(phase = EnrollPhase.ENROLLING, outcome = null)
        runScope.launch {
            when (val outcome = controller.enroll(passphrase)) {
                EnrollOutcome.Enrolled ->
                    // AC-2: reconnect reusing the just-set passphrase as the FIRST UV — armed AFTER the failed
                    // session's teardown (P1-clear can't wipe it); the coordinator+UV own+zeroize it (P2, not here).
                    connectRemoteInternal(s.hub, preArm = passphrase)
                else -> {
                    passphrase.fill('\u0000') // failure path: preArm did NOT take it ⇒ zeroize now (H-1)
                    _state.value = HubConnectUiState.SetPassphrase(s.hub, EnrollPhase.ERROR, outcome)
                }
            }
        }
    }

    /** AC-1: leave the set-passphrase step without enrolling (back to the hub list). Tears the failed session down and
     *  clears any pending pre-arm (P1) via [closeActiveComponents]. */
    fun cancelEnroll() = backToHubList()

    /**
     * CYP-525 §2: the operator confirmed they saved their backup codes → resolve the enroll confirmer `true` so
     * `ClientOperatorAuth` sends `SavedAck` and reads the hub's finalize grant → CONNECTED. The ONLY forward door out
     * of [HubConnectUiState.RevealCodes]. **Within-flow, no durable persist.** H3 (the valid-complete-set gate) is
     * enforced in `ClientOperatorAuth` BEFORE the reveal is ever shown, so this cannot ack an invalid set.
     */
    fun acknowledgeCodes() {
        activeComponents?.enrollConfirm?.confirmSaved()
    }

    /**
     * M2 Seam-3 (a)+(b) — the CONNECTED remote-workspace hand-off, or `null` (Local / not-yet-CONNECTED ⇒ AgentShell's
     * LOCAL default, the INERT invariant). Non-null iff the live session is CONNECTED with a built transport.
     */
    fun remoteHandoff(): RemoteWorkspaceHandoff? {
        val s = _state.value as? HubConnectUiState.RemoteConnecting ?: return null
        if (s.remote.conn != RemoteConnState.CONNECTED) return null
        val comps = activeComponents ?: return null
        return RemoteWorkspaceHandoff(s.hub.name, remoteTransport, comps.session.state, ::backToHubList)
    }

    /** Q5/CYP-513: close + drop the active LIVE components so the old Noise session tears down (nothing carried). */
    private fun closeActiveComponents() {
        val previous = activeComponents ?: return
        activeComponents = null
        remoteTransport?.close() // M2 Seam-3: tear down the loopback transport (acceptor + owned client) with the session
        remoteTransport = null
        // CYP-620 Step-2b: drop the swappable mux source + carrier-identity guard on switch/leave; the current mux
        // session is closed in the NonCancellable block below (its GoAway flushes to the still-live carrier BEFORE
        // session.close() tears the tunnel).
        currentMuxSource.value = null
        currentMuxCarrier = null
        val muxToClose = currentMuxSession
        currentMuxSession = null
        previous.passphrasePrompt?.clearPreArm() // CYP-542/B1 P1: zeroize+drop any un-consumed pre-arm on switch/leave
        previous.keyHold?.clear() // CYP-542/B1 (Assist BLOCK-1): zeroize the decrypted device key NOW (H-1 — never wait
        // for the lazy put/get-expiry; a switch/leave/cancel in the ≤120s window must not drop it GC-reachable)
        previous.enrollConfirm.abort() // CYP-525 §2: a switch/leave during the reveal aborts enroll (fail-closed, no SavedAck)
        runScope.launch {
            withContext(NonCancellable) {
                muxToClose?.close() // CYP-620 Step-2b: flush the GoAway to the still-live carrier, then stop the writer
                previous.tunnelPool?.close() // CYP-537: tear down all N pool tunnels (Q5 — nothing carried across)
                previous.session.close()
            }
        }
    }

    /** Q5/H-1: the flow's ViewModel is being destroyed (screen gone / idle) — tear the live session down so the decrypted
     *  device key is zeroized (closeActiveComponents' BLOCK-1 zeroize) rather than lingering until GC. */
    override fun onCleared() {
        closeActiveComponents()
        super.onCleared()
    }

    /**
     * Q5 "auf Hub wechseln" step 1 — leave the current (remote) connection to pick another hub. **Tears the active
     * remote session down FIRST** (exactly-one-hub, CI-6 — nothing carried across) and returns to the hub list;
     * the subsequent [selectHub] + [connectRemote] establishes the new one.
     */
    fun backToHubList() {
        remoteJob?.cancel()
        remoteJob = null
        closeActiveComponents() // CYP-513: tear down the LIVE Noise session before returning to the list (Q5)
        _state.value = HubConnectUiState.LoadingHubs
        runScope.launch {
            val hubs = runCatching { controlPlane.hubs() }.getOrElse {
                _state.value = HubConnectUiState.HubsUnreachable
                return@launch
            }
            _state.value = HubConnectUiState.HubList(hubs)
        }
    }
}
