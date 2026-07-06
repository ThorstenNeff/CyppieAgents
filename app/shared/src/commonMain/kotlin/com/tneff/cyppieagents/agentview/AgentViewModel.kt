package com.tneff.cyppieagents.agentview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** CYP-262 T1 robustness: default watchdog window for the transient "Startet…" spawn feedback. If an accepted Start
 *  produces neither a terminal lifecycle event nor a synchronous failure within this window, the flag falls back to
 *  the last resolved state — a stuck spinner can't outlive a real spawn (spawns confirm in seconds). */
const val START_PENDING_TIMEOUT_MS: Long = 30_000L

/**
 * Drives the agent window: collects the [AgentSession] event stream, folds it into the rendered
 * transcript via [foldEvent], and forwards human turns to the session.
 *
 * Folding logic lives in [foldEvent] (pure, unit-tested); this class only wires it to the stream.
 */
class AgentViewModel(
    private val session: AgentSession,
    private val agentId: String = "",
    /** Operator-gated lifecycle actions; `null` in pure-render tests / when no control path is wired. */
    private val lifecycle: AgentLifecycleApi? = null,
    /** Non-gated lifecycle-state source (snapshot + `/ws/lifecycle` deltas); `null` → state stays UNKNOWN. */
    lifecycleSource: AgentLifecycleSource? = null,
    /** Whether the operator token is present → Start/Stop/Restart controls are enabled (fail-closed). */
    val canControl: Boolean = false,
    /** CYP-262 T1 robustness: watchdog window for the "Startet…" transient (see [START_PENDING_TIMEOUT_MS]).
     *  Injectable so tests can drive the fallback with a tiny value. */
    private val startPendingTimeoutMs: Long = START_PENDING_TIMEOUT_MS,
) : ViewModel() {

    private val _transcript = MutableStateFlow<List<AgentEvent>>(emptyList())
    val transcript: StateFlow<List<AgentEvent>> = _transcript.asStateFlow()

    /**
     * CYP-204: live connection state for the reconnecting indicator, straight from the [AgentSession]
     * (the WS adapter auto-reconnects from the seq cursor). LIVE for stubs. The transcript is NOT cleared on
     * a drop — the [foldEvent] transcript survives, and on reconnect the server replays gapless from the cursor.
     */
    val connection: StateFlow<com.tneff.cyppieagents.comm.ConnectionStatus> = session.connection

    /**
     * CYP-262 Teil 1: transient, client-only "start requested — awaiting the server" flag driving the
     * "Startet…" label on the [StatusIndicator]. Armed when the operator's Start action is accepted (the
     * fire-and-forget POST, only after the fail-closed gate), cleared by the NEXT lifecycle event for this
     * agent (RUNNING on success, STOPPED/ERROR on failure) or a synchronous start rejection. Honest: it
     * mirrors the request in flight, NEVER claims RUNNING before the server does, and always resolves on the
     * terminal event — it can't stick. Declared before [lifecycleState] because that eager flow clears it.
     */
    val startPending: StateFlow<Boolean> get() = _startPending
    private val _startPending = MutableStateFlow(false)

    // CYP-262 T1 robustness: the watchdog for an in-flight "Startet…". Cancelled the moment the transient resolves.
    private var startTimeoutJob: Job? = null

    /** Resolve an in-flight "Startet…" (clear the flag) and cancel its watchdog. Called by the terminal lifecycle
     *  event and a synchronous start failure — idempotent, so a late watchdog tick is a harmless no-op. */
    private fun clearStartPending() {
        _startPending.value = false
        startTimeoutJob?.cancel()
        startTimeoutJob = null
    }

    /**
     * Server-reported lifecycle state for THIS agent (CYP-73), non-gated display: seeded from the
     * public-agent-list snapshot, then refined live by `/ws/lifecycle` deltas. Starts [UNKNOWN] until
     * the snapshot lands (never guesses a server fact).
     */
    val lifecycleState: StateFlow<AgentLifecycleState> =
        flow {
            emit(lifecycleSource?.snapshot()?.get(agentId) ?: AgentLifecycleState.UNKNOWN)
            lifecycleSource?.events()?.filter { it.agentId == agentId }?.collect { event ->
                // CYP-262: any terminal lifecycle event for this agent resolves an in-flight "Startet…" (and cancels
                // its watchdog — the server confirmed, so the fallback isn't needed).
                clearStartPending()
                emit(event.state)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentLifecycleState.UNKNOWN)

    /**
     * Agent overall status (CYP-12 "Ebene B") derived from the transcript. WAITING_FOR_INPUT /
     * OFFLINE are not derived here (see [deriveStatus]); they come from explicit signals / the
     * session layer.
     */
    val status: StateFlow<AgentStatus> = _transcript
        .map { deriveStatus(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentStatus.IDLE)

    init {
        viewModelScope.launch {
            try {
                session.events.collect { event ->
                    _transcript.update { foldEvent(it, event) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // CYP-204: transient WS drops are handled by the self-reconnecting [AgentSession] (cursor-resume
                // + the [connection] indicator), so this catch is now only a last-resort safety net for a fatal,
                // non-reconnecting stream error — stay alive and say so honestly instead of crashing the window.
                _transcript.update { foldEvent(it, AgentEvent.Notice("conn-error", "Verbindung zum Agenten verloren")) }
            }
        }
    }

    /** A human turn: posted to the agent's channel via the Hub-mediated session (never stdin). */
    fun onSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        session.sendMessage(trimmed)
    }

    /**
     * Last lifecycle-control error as the server [ApiError] code (`already_running` / `spawn_failed` /
     * `operator_required` / `agent_not_found`) or `lifecycle_failed` for anything else; `null` when the
     * last action succeeded. The header surfaces it honestly (CYP-73: "409/403/503 sauber im UI").
     */
    val lifecycleError: StateFlow<String?> get() = _lifecycleError
    private val _lifecycleError = MutableStateFlow<String?>(null)

    /** Operator-only: (re)spawn / stop the agent's connector. Fail-closed — a no-op without control. */
    fun start() = lifecycleAction(pending = true) { it.start(agentId) }
    fun stop() = lifecycleAction { it.stop(agentId) }
    fun restart() = lifecycleAction { it.restart(agentId) }

    private fun lifecycleAction(pending: Boolean = false, block: suspend (AgentLifecycleApi) -> Unit) {
        // Fail-closed defence in depth: the server enforces the operator gate (403); the UI also
        // disables the controls and the VM refuses to act without [canControl] + a wired [lifecycle].
        if (!canControl) return
        val api = lifecycle ?: return
        // CYP-262: arm the transient "Startet…" only for Start, and only AFTER the fail-closed gate — so a
        // no-op (no control / no port) never shows spawn feedback. Set synchronously for instant feedback, and
        // arm a watchdog so an accepted-but-never-confirmed spawn (POST 2xx, then no `/ws/lifecycle` event) can't
        // leave "Startet…" stuck — after the window it falls back to the last resolved state (§9-2 "always resolves,
        // never hangs"). A terminal event or a synchronous failure cancels the watchdog first (the normal paths).
        if (pending) {
            _startPending.value = true
            startTimeoutJob?.cancel()
            startTimeoutJob = viewModelScope.launch {
                delay(startPendingTimeoutMs)
                _startPending.value = false // fallback only; a resolved transient would have cancelled this job
            }
        }
        viewModelScope.launch {
            _lifecycleError.value = null
            runCatching { block(api) }.onFailure { e ->
                if (e is CancellationException) throw e
                // A synchronous start rejection (e.g. spawn_failed 503) resolves the transient at once — never
                // leave "Startet…" hanging on a request that already failed; the state stays STOPPED honestly.
                clearStartPending()
                // Surface the server's reason code (409/403/503/404) honestly; generic fallback otherwise.
                _lifecycleError.value = (e as? AgentLifecycleHttpException)?.code ?: "lifecycle_failed"
            }
        }
    }
}
