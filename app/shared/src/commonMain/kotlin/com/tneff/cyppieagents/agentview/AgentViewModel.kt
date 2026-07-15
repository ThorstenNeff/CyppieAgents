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
 * CYP-333: which view the agent window renders in its content rectangle. A **client view-selection** — NOT the
 * backend hand-off state machine (the spec's `TerminalControlState`). In this slice the mediated stream-json
 * session keeps running while [TERMINAL] is shown, so no hand-off / hub-blind / frozen-token / CONTEXT_LOST is
 * claimed — that is the Backend follow-up (⟂BE-1..3).
 *
 * [TERMINAL] is the structural "second view" slot. In the interim (Auftraggeber ruling) it holds an honest
 * **worktree bash shell** (a fresh PTY over `/ws/terminal`, CYP-332/348 — `git status`/`ls`/inspect), NOT a
 * second `claude`; the same-session claude terminal fills the same slot later, with the hand-off (BE-2).
 */
enum class AgentContentMode { ORCHESTRATION, TERMINAL }

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
    /** CYP-381: the hand-off command port (`POST /api/agents/{id}/mode`). `null` → [requestMode] falls back to the
     *  pure client view-selection ([showContentMode]) — the pre-CYP-381 behaviour, used in pure-render tests. */
    private val modeRepository: ModeRepository? = null,
    /** CYP-381 IDLE-gate: the per-agent busy feed (CYP-324 `/ws/busy-state`), so a take-over requested mid-turn can
     *  bounded-defer instead of hijacking a running mediated turn. `null` → busy stays `false` (no gate). */
    busySource: BusyStateSource? = null,
    /** Whether the operator token is present → Start/Stop/Restart controls are enabled (fail-closed). */
    val canControl: Boolean = false,
    /** CYP-262 T1 robustness: watchdog window for the "Startet…" transient (see [START_PENDING_TIMEOUT_MS]).
     *  Injectable so tests can drive the fallback with a tiny value. */
    private val startPendingTimeoutMs: Long = START_PENDING_TIMEOUT_MS,
    /** CYP-335: the wall clock for the two rows that are BORN here rather than arriving on the wire — the
     *  composer's [AgentEvent.UserTurn] echo and the `conn-error` [AgentEvent.Notice]. Injected (never a clock
     *  call inside the VM body) so a test can fake it and assert a deterministic timestamp. Every other row is
     *  dated by the server (see [AgentEvent.tsMs]). */
    private val nowMs: () -> Long = platformTranscriptClock()::nowMs,
    /**
     * CYP-387: capacity of the sent-message input history (arrow-up/down recall). This is the ONE global personal
     * preference (spec §3), so it is a live `var` — [AgentShell] mirrors the current global N onto every open
     * agent VM, and the store reads it through a supplier (see [inputHistory]). `0` = recall off. Default 20.
     */
    var historyCapacity: Int = InputHistory.DEFAULT_CAPACITY,
) : ViewModel() {

    // CYP-387 (layer A, data): per-agent session-scoped ring buffer of SENT messages, appended in [onSend].
    // Capacity is read live via the [historyCapacity] supplier so a global-N change (or `0`=off) takes effect on
    // this open agent WITHOUT rebuilding the store (which would wipe its content). The arrow-key binding + cursor
    // semantics (layer B) live in the composer against the interaction spec, not here.
    private val inputHistory = InputHistory { historyCapacity }

    /** CYP-387: newest-last snapshot of messages sent to this agent, for the composer's recall navigation. */
    fun inputHistorySnapshot(): List<String> = inputHistory.entries

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
     * CYP-330 Teil 1: transient, client-only "restart requested — awaiting the server" flag driving the
     * "Neustart…" label on the [StatusIndicator]. A restart is typically **RUNNING→RUNNING**, so without its OWN
     * transient a *successful* restart produced no visible delta — the first click looked "swallowed" (the bug).
     * Armed synchronously when the operator's Restart action is accepted (after the fail-closed gate, so a no-op
     * shows nothing), cleared by the NEXT lifecycle event for this agent, a synchronous rejection, or the watchdog.
     * Honest and self-resolving exactly like [startPending] — it mirrors a request in flight, never a server fact,
     * and can't stick. Declared before [lifecycleState] because that eager flow clears it.
     */
    val restartPending: StateFlow<Boolean> get() = _restartPending
    private val _restartPending = MutableStateFlow(false)

    // CYP-330: the watchdog for an in-flight "Neustart…". Cancelled the moment the transient resolves.
    private var restartTimeoutJob: Job? = null

    /** Resolve an in-flight "Neustart…" (clear the flag) and cancel its watchdog — idempotent (see [clearStartPending]). */
    private fun clearRestartPending() {
        _restartPending.value = false
        restartTimeoutJob?.cancel()
        restartTimeoutJob = null
    }

    /**
     * CYP-333/381: the window's content view — the structured Orchestrierung transcript (default) or the real
     * Terminal. In production the toggle routes through [requestMode] (non-optimistic, CYP-355 motor): choosing
     * TERMINAL hands off to the agent's interactive `claude --resume` session (the mediated reader steps aside) and
     * this flips **only** on the server confirm. This is the local VIEW selection; the authoritative per-agent
     * `TerminalControlState` is mirrored separately over `/ws/terminal-state` (CYP-354) and drives the banners.
     */
    val contentMode: StateFlow<AgentContentMode> get() = _contentMode
    private val _contentMode = MutableStateFlow(AgentContentMode.ORCHESTRATION)

    /**
     * Switch the window's content view **locally** (no server round-trip). **Fail-closed:** opening the terminal
     * is an operator control surface (it spawns a shell in the agent's worktree), so a non-operator cannot switch
     * to [AgentContentMode.TERMINAL] — the UI also disables the segment and the server admits the socket on its own
     * bar (defence in depth). Returning to Orchestrierung is always allowed (it only changes the local view).
     *
     * The direct local-flip path, kept for the pre-CYP-381 view-selection (and pure-render tests with no wired
     * [modeRepository]). Production routes the toggle through [requestMode] (non-optimistic, IDLE-gated).
     */
    fun showContentMode(mode: AgentContentMode) {
        if (mode == AgentContentMode.TERMINAL && !canControl) return // fail-closed
        _contentMode.value = mode
    }

    /**
     * CYP-381 IDLE-gate — this agent's live busy signal (CYP-324 `/ws/busy-state`), used to bounded-defer a
     * take-over requested mid-turn. Absent event == idle (unknown ≠ busy, exactly as the title-bar `*`), so it
     * seeds `false` and never fabricates a busy state that would wrongly block a switch.
     */
    val busy: StateFlow<Boolean> =
        flow {
            emit(false)
            busySource?.events()?.filter { it.agentId == agentId }?.collect { emit(it.busy) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * CYP-381 — a hand-off command is in flight (POST issued, awaiting the server's confirm/reject). Drives a
     * transient "wird umgeschaltet…" affordance; the view does NOT flip until the confirm lands (non-optimistic).
     */
    val modeSwitching: StateFlow<Boolean> get() = _modeSwitching
    private val _modeSwitching = MutableStateFlow(false)

    /**
     * CYP-381 — request a mode transition **non-optimistically** through the [modeRepository] (`POST
     * /api/agents/{id}/mode`). The view flips **only after** the server confirms (`outcome==CONFIRMED`); a reject
     * leaves the mode unchanged and surfaces `mode_swap_failed` on the shared [lifecycleError] row (UIUX spec §3.4 —
     * the CompactVM never-optimistic discipline). Fail-closed on TERMINAL without [canControl]. Falls back to the
     * local [showContentMode] when no [modeRepository] is wired.
     *
     * **IDLE-gate (CYP-355 settled contract):** the bounded-wait is **server-side** — the POST is held synchronously
     * until the running turn settles, returning CONFIRMED (switched) or REJECTED(`BUSY_TIMEOUT`) after ~30s. So the
     * client does NOT defer the POST; it fires at once and shows a *waiting* state ([modeSwitching]) — the UI reads
     * [busy] to say "wartet bis Turn fertig" instead of a generic "switching…". The switch only completes when the
     * server settles → never a silent mid-turn hijack, with the bound owned by the server.
     */
    fun requestMode(target: AgentContentMode) {
        if (target == AgentContentMode.TERMINAL && !canControl) return // fail-closed (defence in depth)
        val repo = modeRepository ?: run { showContentMode(target); return } // pre-CYP-381 local view-selection
        // Arm the waiting UI SYNCHRONOUSLY (instant "switching…"/"wartet…" feedback, like CYP-330's restartPending) —
        // but do NOT flip the view: that waits for the server confirm below (non-optimistic). Clear any prior error.
        _lifecycleError.value = null
        _modeSwitching.value = true
        viewModelScope.launch {
            // The POST is held by the server through its bounded-wait (up to ~30s); modeSwitching == the waiting UI.
            runCatching { repo.setMode(agentId, target) }
                .onSuccess { confirm -> _contentMode.value = confirm.confirmed } // flip AFTER confirm (non-optimistic)
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // REJECTED / transport failure → stay in the current mode; surface `mode_swap_failed` on the
                    // shared lifecycleError row (UIUX §3.4, §9 — reuses the row, no separate error node).
                    _lifecycleError.value = "mode_swap_failed"
                }
            _modeSwitching.value = false
        }
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
                // CYP-262/330: any lifecycle event for this agent resolves an in-flight transient — "Startet…" AND
                // "Neustart…" (and cancels its watchdog — the server confirmed, so the fallback isn't needed). A
                // successful RUNNING→RUNNING restart still emits a lifecycle event, so the "Neustart…" flash resolves.
                clearStartPending()
                clearRestartPending()
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

    /** CYP-335: distinguishes successive connection-loss notices (see the `catch` below). */
    private var connErrorSeq = 0

    /**
     * CYP-335 — **the second clock.** Stream rows are stamped by the *server*; [AgentEvent.UserTurn] and the
     * `conn-error` [AgentEvent.Notice] are born here and can only read the *client's* clock.
     *
     * The tempting fix — estimate `skew = lastEventTs − clientNow` and stamp client rows on the server's base —
     * **is wrong, and wrong in the normal case.** An event timestamp is only a *lower bound* on `serverNow`:
     * from the client, "the browser runs 5 minutes fast" and "the last event is 5 minutes old" are the same
     * observation. So an agent that has been idle since yesterday 22:14 makes the estimate 11 hours negative,
     * and a turn typed at 09:02 this morning renders as `22:14` — with a **perfectly correct** browser clock.
     * The error grows with idle time, and the monotonicity invariant stays green throughout, because the column
     * does still ascend. That invariant is necessary, not sufficient; it says nothing about whether the stamp is
     * *true*. Measured, then removed — see `AgentClientStampTest.replayedOldHistory_doesNotBackdateANewTurn`.
     *
     * What remains is the clamp: a client row never renders below the row above it. It costs nothing and it
     * catches the one thing the client *can* observe — its own clock moving backwards (an NTP correction; wall
     * clock time is not monotonic, cf. doc 06 §6).
     *
     * **Known, accepted defect — state it plainly.** Without the estimate, a fast browser does not merely date a
     * turn imprecisely: it **inverts the column**. The agent's reply renders *beneath* your question carrying an
     * *earlier* time (`14:08` question, `14:03` reply), because a server stamp is a fact and is never clamped.
     * The trade is deliberate: the error is now bounded by the clock skew (minutes) instead of by the agent's
     * idle time (hours), and it needs a wrong clock instead of striking the normal case. Closing it needs the
     * server to state its own `serverNowMs` when the client attaches — only the *send* instant is replay-immune,
     * an event's own timestamp is not. Tracked as **CYP-346** (`:core` + server). Do not re-derive a skew from
     * event timestamps here; that is exactly the defect above.
     */
    private fun clientStampMs(): Long {
        val now = nowMs()
        val previous = _transcript.value.lastOrNull()?.tsMs ?: return now
        return maxOf(now, previous)
    }

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
                //
                // CYP-335: the id must be UNIQUE, not the constant "conn-error" it used to be. Notices are
                // de-duplicated by id in [foldEvent]; a repeated notice would be swallowed and the surviving row
                // would keep asserting the FIRST failure's time — a timestamp that lies. Unreachable as this
                // `catch` sits outside the `collect` (the coroutine ends here, so there is no second pass today),
                // but the row must not depend on that for its honesty: wrap this in a retry loop tomorrow and the
                // constant id turns into a silently-wrong clock.
                _transcript.update {
                    foldEvent(it, AgentEvent.Notice("conn-error-${connErrorSeq++}", "Verbindung zum Agenten verloren", clientStampMs()))
                }
            }
        }
    }

    /** CYP-323: monotonic per-turn counter → a stable, unique id for each locally-echoed human turn. The id stays
     *  clock-free on purpose (CYP-335's [nowMs] dates the row but must never key it): a counter is collision-free
     *  even within one millisecond, which keeps [foldEvent]'s id-dedup honest and preserves chronological order. */
    private var userTurnSeq = 0

    /**
     * A human turn: posted to the agent's channel via the Hub-mediated session (never stdin).
     *
     * CYP-323: the turn is echoed into the local transcript FIRST — chronologically before the agent's reply,
     * which arrives asynchronously on [session.events]. The composer turn is stdin-only and the stream does not
     * replay it, so this echo is the only source of the user-turn row (see [AgentEvent.UserTurn]).
     */
    fun onSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // CYP-387: record the actually-sent message for arrow-up/down recall (the sole sent-message choke point).
        inputHistory.record(trimmed)
        // F4 (CYP-580, honest delivery): the echo reflects OBSERVED deliverability, not just our own action. A turn
        // composed while the per-agent socket is not LIVE is NOT delivered (best case it buffers, worst case it is
        // dropped in the client channel or skipped server-side in the restart window, AgentSocket:141) — mark it so
        // rather than rendering it as sent. A LIVE send stays best-effort "sent" (true end-to-end confirmation needs
        // a server ack — a separate item; we never fabricate a confirmed "delivered").
        val delivered = connection.value == com.tneff.cyppieagents.comm.ConnectionStatus.LIVE
        _transcript.update {
            foldEvent(it, AgentEvent.UserTurn(id = "user-${userTurnSeq++}", text = trimmed, tsMs = clientStampMs(), delivered = delivered))
        }
        session.sendMessage(trimmed)
    }

    /**
     * Last lifecycle-control error as the server [ApiError] code (`already_running` / `spawn_failed` /
     * `operator_required` / `agent_not_found`) or `lifecycle_failed` for anything else; `null` when the
     * last action succeeded. The header surfaces it honestly (CYP-73: "409/403/503 sauber im UI").
     */
    val lifecycleError: StateFlow<String?> get() = _lifecycleError
    private val _lifecycleError = MutableStateFlow<String?>(null)

    /** Which transient a lifecycle action arms: Start → "Startet…", Restart → "Neustart…", Stop → none. */
    private enum class Pending { NONE, START, RESTART }

    /** Operator-only: (re)spawn / stop the agent's connector. Fail-closed — a no-op without control. */
    fun start() = lifecycleAction(Pending.START) { it.start(agentId) }
    fun stop() = lifecycleAction(Pending.NONE) { it.stop(agentId) }
    fun restart() = lifecycleAction(Pending.RESTART) { it.restart(agentId) }

    private fun lifecycleAction(pending: Pending = Pending.NONE, block: suspend (AgentLifecycleApi) -> Unit) {
        // Fail-closed defence in depth: the server enforces the operator gate (403); the UI also
        // disables the controls and the VM refuses to act without [canControl] + a wired [lifecycle].
        if (!canControl) return
        val api = lifecycle ?: return
        // CYP-262/330: arm the transient ("Startet…" for Start, "Neustart…" for Restart) only AFTER the fail-closed
        // gate — so a no-op (no control / no port) never shows feedback. Set synchronously for INSTANT feedback (so
        // the first click is never "swallowed"), and arm a watchdog so an accepted-but-never-confirmed action (POST
        // 2xx, then no `/ws/lifecycle` event) can't leave the transient stuck — after the window it falls back to the
        // last resolved state (§9-2 "always resolves, never hangs"). A lifecycle event or a sync failure cancels the
        // watchdog first (the normal paths). Restart is typically RUNNING→RUNNING, so the transient IS the visible
        // acknowledgement of a successful restart.
        when (pending) {
            Pending.START -> {
                _startPending.value = true
                startTimeoutJob?.cancel()
                startTimeoutJob = viewModelScope.launch {
                    delay(startPendingTimeoutMs)
                    _startPending.value = false // fallback only; a resolved transient would have cancelled this job
                }
            }
            Pending.RESTART -> {
                _restartPending.value = true
                restartTimeoutJob?.cancel()
                restartTimeoutJob = viewModelScope.launch {
                    delay(startPendingTimeoutMs)
                    _restartPending.value = false // fallback only
                }
            }
            Pending.NONE -> {}
        }
        viewModelScope.launch {
            _lifecycleError.value = null
            runCatching { block(api) }.onFailure { e ->
                if (e is CancellationException) throw e
                // A synchronous rejection (e.g. spawn_failed 503) resolves the transient at once — never leave
                // "Startet…"/"Neustart…" hanging on a request that already failed; the state stays honest.
                clearStartPending()
                clearRestartPending()
                // CYP-598-A: surface the REAL cause, never a generic blur. A response-bearing reject passes its
                // honest server code through (already_running / spawn_failed / operator_required / agent_not_found …);
                // a code-less non-2xx keeps the generic fallback. A TRANSPORT failure (server unreachable / refused /
                // timeout / TLS / reset) threw BEFORE any response — it is NOT an AgentLifecycleHttpException — so it
                // gets its own distinct `unreachable` cause instead of collapsing to the opaque `lifecycle_failed`
                // token (the CYP-598 masking defect: "Action failed" hid whether the server was even reachable).
                _lifecycleError.value = when (e) {
                    is AgentLifecycleHttpException -> e.code ?: "lifecycle_failed"
                    else -> "unreachable"
                }
            }
        }
    }
}
