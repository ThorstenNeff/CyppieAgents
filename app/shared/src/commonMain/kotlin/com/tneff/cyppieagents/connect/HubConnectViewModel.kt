package com.tneff.cyppieagents.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    /** Hostname default for the editable hub-name field (Q1); the host injects the real device hostname. */
    private val defaultHubName: String = "mein-hub",
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
            _state.value = if (hubs.isEmpty()) {
                HubConnectUiState.Register(defaultHubName, RegisterPhase.EDITING)
            } else {
                HubConnectUiState.HubList(hubs)
            }
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
            ?: return
        remoteJob?.cancel() // Q5: exactly-one-hub — tear the current remote session down before the new one.
        _state.value = HubConnectUiState.RemoteConnecting(hub, RemoteSessionState(hub.hubId, RemoteConnState.RELAY_DIALING))
        val coordinator = oobConfirm
        remoteJob = runScope.launch {
            if (coordinator == null) {
                // INERT (CYP-510): no live OOB source ⇒ byte-identical to the CYP-471 path.
                remoteConnectFeed.connect(hub).collect { rs ->
                    _state.value = HubConnectUiState.RemoteConnecting(hub, rs)
                }
            } else {
                // CYP-510: combine the session feed with the live OobConfirmState. While a FirstUse confirm is
                // Awaiting (the session is suspended in trust.resolve), surface the mandatory OOB screen at
                // TRUST_CHECK regardless of the underlying dial state; otherwise reflect the feed's truth.
                combine(remoteConnectFeed.connect(hub), coordinator.state) { rs, oob -> rs to oob }
                    .collect { (rs, oob) ->
                        val mount = buildLiveOobMount(coordinator, hub, oob)
                        _state.value = if (mount != null) {
                            HubConnectUiState.RemoteConnecting(
                                hub, RemoteSessionState(hub.hubId, RemoteConnState.TRUST_CHECK), oobConfirm = mount,
                            )
                        } else {
                            HubConnectUiState.RemoteConnecting(hub, rs)
                        }
                    }
            }
        }
    }

    /**
     * Q5 "auf Hub wechseln" step 1 — leave the current (remote) connection to pick another hub. **Tears the active
     * remote session down FIRST** (exactly-one-hub, CI-6 — nothing carried across) and returns to the hub list;
     * the subsequent [selectHub] + [connectRemote] establishes the new one.
     */
    fun backToHubList() {
        remoteJob?.cancel()
        remoteJob = null
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
