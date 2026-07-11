package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.SessionStore
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.ModeChangeOutcome
import com.tneff.cyppieagents.model.ModeChangeRejection
import com.tneff.cyppieagents.model.ModeChangeResponse
import com.tneff.cyppieagents.model.ResumeOutcome
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalMode
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.routing.NotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * CYP-355 (BE-2) — the **hand-off motor**: the single-writer that moves ONE agent between mediated
 * (stream-json, [LifecycleManager]) and interactive (`claude --resume`, [PtyManager]) — and back — as one
 * atomic, non-optimistic transition.
 *
 * ## The load-bearing invariant: never mediated AND PTY at once
 *
 * A hand-off is one act that spans two managers, so it holds the **shared** [AgentTransitionLock] for the whole
 * transition — the SAME per-agent lock [LifecycleManager] takes (CYP-368). That is why the motor drives the
 * primitives directly (`sessions.removeAndAwait`, `ptyManager.spawnInteractive`) rather than calling
 * `LifecycleManager.stop()`, which would re-enter the non-reentrant lock and deadlock. Under the lock the two
 * sides are **strictly sequenced**: the source is torn down to completion (`removeAndAwait` awaits the process's
 * death; `ptyManager.close` destroys the PTY) BEFORE the target is spawned — so there is never a window in which
 * both a mediated session and a PTY are live for one agent (the two-process violation from the CYP-344 spike).
 *
 * ## Non-optimistic confirm
 *
 * The motor confirms only after the target is **live**: the spawn did not throw, and the process survived a
 * liveness-settle window (it did not die immediately — a stale exec, a dead `--resume`). A failure **rolls back
 * to the prior mode** and returns `REJECTED` — the client never flips optimistically. The confirm signal is
 * honestly asymmetric (an interactive TUI emits no stream-json `system/init`, so →TERMINAL confirm is liveness,
 * not a semantic bind); the memory-survival question is answered separately by the CYP-356 [ResumeOutcome], never
 * by terminal scrollback (the CYP-331 honesty trap).
 *
 * ## IDLE-gate
 *
 * →TERMINAL bounded-defers on a mediated turn in flight (CYP-324 busy), so a hand-off never silently hijacks a
 * turn; if the agent stays busy past [idleDeferBoundMs] the request is `REJECTED(BUSY_TIMEOUT)` and the mode is
 * unchanged. (→ORCHESTRATION has no mediated turn to protect — the mediated session is already gone.)
 */
class HandoffMotor(
    private val projectId: String,
    private val scope: CoroutineScope,
    private val transitions: AgentTransitionLock,
    private val sessions: ConnectorSessions,
    /** Late-bound: the single host [PtyManager] is constructed after the runtimes, so it is resolved per call
     *  (every hand-off happens post-boot, once it is wired). */
    private val ptyManager: () -> PtyManager,
    /** The connector's mediated spawn (`connector.open(id, worktree, projectId)`) — the SAME seam
     *  [LifecycleManager] uses, so the resumed hand-back reuses CYP-167/330's resume machinery by construction. */
    private val spawnMediated: (agentId: String, worktreeName: String) -> ConnectorSession,
    /** agentId → worktree sub-folder (null == unknown agent → 404). */
    private val worktreeOf: (agentId: String) -> String?,
    /** Durable session id for the `--resume` spawn (CYP-167 store); null store / no entry → fresh interactive. */
    private val sessionStore: SessionStore?,
    private val busyState: AgentBusyStateTracker,
    private val terminalControl: TerminalControlStateTracker,
    private val resumeSignal: ResumeOutcomeSignal,
    /** CYP-355 — the operator-configurable IDLE-defer bound (default 30 s, single-sourced in [DEFAULT_IDLE_DEFER_MS]). */
    private val idleDeferBoundMs: () -> Long = { DEFAULT_IDLE_DEFER_MS },
    private val now: () -> Long = { System.currentTimeMillis() },
    /** The interactive launch command for a given durable sid. Default = the production `claude --resume`
     *  ([resumeCommandFor], NEVER `--fork-session`); tests inject a fake TUI. */
    private val interactiveCommand: (sid: String?) -> List<String> = { resumeCommandFor(it) },
    private val livenessSettleMs: Long = LIVENESS_SETTLE_MS,
    private val resumeOutcomeWaitMs: Long = RESUME_OUTCOME_WAIT_MS,
) {
    private val log = LoggerFactory.getLogger("handoff")

    /**
     * Move [agentId] to [target], synchronously through the whole transition. [requestedBy] is the operator
     * identity that becomes `heldBy` while interactive. Throws [NotFoundException] for an unknown agent.
     */
    suspend fun requestMode(agentId: String, target: TerminalMode, requestedBy: String): ModeChangeResponse {
        val worktree = worktreeOf(agentId)
            ?: throw NotFoundException("unknown agent '$agentId'", code = "agent_not_found")
        return transitions.withAgent(agentId) {
            val current = terminalControl.snapshot().firstOrNull { it.agentId == agentId }
                ?: AgentTerminalControlEvent(agentId, TerminalControlState.MEDIATED)
            when (target) {
                TerminalMode.TERMINAL -> toTerminal(agentId, worktree, requestedBy, current)
                TerminalMode.ORCHESTRATION -> toOrchestration(agentId, worktree, requestedBy, current)
            }
        }
    }

    private suspend fun toTerminal(
        agentId: String,
        worktree: String,
        requestedBy: String,
        current: AgentTerminalControlEvent,
    ): ModeChangeResponse {
        when (current.state) {
            TerminalControlState.INTERACTIVE -> return reject(ModeChangeRejection.ALREADY_IN_TARGET, current)
            TerminalControlState.HANDING_OVER, TerminalControlState.HANDING_BACK ->
                return reject(ModeChangeRejection.IN_TRANSITION, current)
            else -> {} // MEDIATED / CONTEXT_LOST → proceed
        }

        // IDLE-gate: signal the pending hand-off, then bounded-defer on a mediated turn in flight — never hijack it.
        terminalControl.set(agentId, TerminalControlState.HANDING_OVER, heldBy = requestedBy, since = now())
        if (!awaitIdle(agentId, idleDeferBoundMs())) {
            terminalControl.set(agentId, TerminalControlState.MEDIATED)
            return reject(ModeChangeRejection.BUSY_TIMEOUT, mediated(agentId))
        }

        // Invariant: tear the mediated session down TO COMPLETION before the PTY exists.
        sessions.removeAndAwait(agentId)
        busyState.reset(agentId)

        // Spawn the interactive PTY with `claude --resume <sid>` (NEVER --fork-session — see [resumeCommand]).
        val sid = sessionStore?.find(projectId, agentId)?.sessionId
        val exit = CompletableDeferred<Int>()
        val spawned = runCatching {
            ptyManager().spawnInteractive(agentId, DEFAULT_COLS, DEFAULT_ROWS, interactiveCommand(sid)) { code -> exit.complete(code) }
        }
        if (spawned.isFailure || !settledAlive(exit)) {
            spawned.exceptionOrNull()?.let { log.error("→TERMINAL spawn threw for agent={}: {}", agentId, it.message) }
            ptyManager().close(agentId) // ensure no half-spawned PTY lingers
            restoreMediated(agentId, worktree)
            return reject(ModeChangeRejection.SPAWN_FAILED, mediated(agentId))
        }

        val since = now()
        val confirmed = AgentTerminalControlEvent(agentId, TerminalControlState.INTERACTIVE, requestedBy, since)
        terminalControl.set(agentId, TerminalControlState.INTERACTIVE, requestedBy, since)
        return ModeChangeResponse(ModeChangeOutcome.CONFIRMED, confirmed)
    }

    private suspend fun toOrchestration(
        agentId: String,
        worktree: String,
        requestedBy: String,
        current: AgentTerminalControlEvent,
    ): ModeChangeResponse {
        when (current.state) {
            TerminalControlState.MEDIATED, TerminalControlState.CONTEXT_LOST ->
                return reject(ModeChangeRejection.ALREADY_IN_TARGET, current)
            TerminalControlState.HANDING_OVER, TerminalControlState.HANDING_BACK ->
                return reject(ModeChangeRejection.IN_TRANSITION, current)
            else -> {} // INTERACTIVE → proceed
        }
        terminalControl.set(agentId, TerminalControlState.HANDING_BACK, heldBy = current.heldBy, since = current.since)

        // Arm the resume-outcome await BEFORE respawning, so the proactive CYP-330 probe's outcome isn't missed.
        val outcome: Deferred<ResumeOutcome?> = scope.async { resumeSignal.await(projectId, agentId, resumeOutcomeWaitMs) }

        // Invariant: destroy the PTY TO COMPLETION before the mediated session exists. (`close` destroys → the
        // TUI's `claude --resume` saves its transcript under the SAME sid — the coexistence proof — so the
        // respawn below resumes it.)
        ptyManager().close(agentId)

        val respawn = runCatching { spawnMediated(agentId, worktree) }
        if (respawn.isFailure) {
            log.error("→ORCHESTRATION mediated respawn threw for agent={}: {}", agentId, respawn.exceptionOrNull()?.message)
            outcome.cancel()
            return restoreInteractive(agentId, requestedBy) // roll back to the prior (INTERACTIVE) mode
        }
        val session = respawn.getOrThrow()
        val exit = CompletableDeferred<Int>()
        session.addExitListener { code -> exit.complete(code ?: -1) }
        sessions.register(session)
        if (!settledAlive(exit)) {
            outcome.cancel()
            sessions.removeAndAwait(agentId)
            return restoreInteractive(agentId, requestedBy)
        }

        // Mediated is live. A stale `--resume` self-heals to a fresh session → CONTEXT_LOST (the proactive probe
        // fires it without a turn); a live resume keeps context → MEDIATED. The mode is CONFIRMED either way — the
        // hand-off succeeded; the memory loss is orthogonal and rides control.state (CYP-356 / PO-ratified).
        val state = if (outcome.await() == ResumeOutcome.CONTEXT_LOST)
            TerminalControlState.CONTEXT_LOST else TerminalControlState.MEDIATED
        val confirmed = AgentTerminalControlEvent(agentId, state)
        terminalControl.set(agentId, state)
        return ModeChangeResponse(ModeChangeOutcome.CONFIRMED, confirmed)
    }

    /** Restore the mediated session after a failed →TERMINAL spawn (best-effort; the agent must not be sessionless). */
    private fun restoreMediated(agentId: String, worktree: String) {
        runCatching { sessions.register(spawnMediated(agentId, worktree)) }
            .onFailure { log.error("failed to restore mediated session for agent={}: {}", agentId, it.message) }
        terminalControl.set(agentId, TerminalControlState.MEDIATED)
    }

    /** Roll back to the prior INTERACTIVE mode after a failed →ORCHESTRATION respawn (best-effort). */
    private suspend fun restoreInteractive(agentId: String, requestedBy: String): ModeChangeResponse {
        val sid = sessionStore?.find(projectId, agentId)?.sessionId
        val restored = runCatching {
            ptyManager().spawnInteractive(agentId, DEFAULT_COLS, DEFAULT_ROWS, interactiveCommand(sid)) {}
        }
        return if (restored.isSuccess) {
            val since = now()
            terminalControl.set(agentId, TerminalControlState.INTERACTIVE, requestedBy, since)
            reject(ModeChangeRejection.SPAWN_FAILED, AgentTerminalControlEvent(agentId, TerminalControlState.INTERACTIVE, requestedBy, since))
        } else {
            log.error("failed to restore interactive PTY for agent={}: {}", agentId, restored.exceptionOrNull()?.message)
            terminalControl.set(agentId, TerminalControlState.MEDIATED)
            reject(ModeChangeRejection.SPAWN_FAILED, mediated(agentId))
        }
    }

    /** True once [agentId] is idle, or immediately if already idle; false if it stays busy past [boundMs]. */
    private suspend fun awaitIdle(agentId: String, boundMs: Long): Boolean {
        if (!busyState.isBusy(agentId)) return true
        val events = busyState.events as SharedFlow<AgentBusyStateEvent>
        return withTimeoutOrNull(boundMs) {
            events
                // re-check AFTER subscribing so an idle that fired between the check above and here isn't lost.
                .onSubscription { if (!busyState.isBusy(agentId)) emit(AgentBusyStateEvent(agentId, false)) }
                .first { it.agentId == agentId && !it.busy }
            true
        } ?: false
    }

    /** True iff the process is still alive after the liveness-settle window (its exit did not fire within it). */
    private suspend fun settledAlive(exit: CompletableDeferred<Int>): Boolean =
        withTimeoutOrNull(livenessSettleMs) { exit.await(); false } ?: true

    private fun reject(reason: ModeChangeRejection, control: AgentTerminalControlEvent) =
        ModeChangeResponse(ModeChangeOutcome.REJECTED, control, reason)

    private fun mediated(agentId: String) = AgentTerminalControlEvent(agentId, TerminalControlState.MEDIATED)

    companion object {
        /** The production interactive launch command. **NEVER `--fork-session`** (CYP-344 (a): a fork would branch
         *  the transcript and break the same-session round-trip). No `--dangerously-skip-permissions` either — a
         *  human is at the TUI and approves interactively (unlike the headless mediated session, CYP-321). */
        fun resumeCommandFor(sid: String?): List<String> =
            if (sid != null) listOf("claude", "--resume", sid) else listOf("claude")

        /** CYP-355 — operator-configurable IDLE-defer bound (PO-ratified default 30 s). Single-sourced here. */
        const val DEFAULT_IDLE_DEFER_MS = 30_000L

        /** How long a freshly-spawned target must stay alive to count as "live" for the non-optimistic confirm. */
        const val LIVENESS_SETTLE_MS = 750L

        /** How long a hand-back waits for the resume outcome (the proactive CYP-330 probe) to classify context. */
        const val RESUME_OUTCOME_WAIT_MS = 3_000L

        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
