package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.StreamJsonEvent

/**
 * CYP-142 (S4.0) — the observability seam for a [ClaudeCodeSession], replacing the session's direct
 * `EventRecorder`/`EventProjector` (hub) coupling so the session can live in `:connector-core`. `:server`
 * provides an impl wrapping the CYP-37 recorder+projector tap; the remote bridge passes `null` (or a wire
 * self-report tap for G4 later). All calls are non-blocking (no Observer-Effect on the stream).
 *
 * Note: it observes the **already-masked** event (Gate #3 masking happens in the session, in the shared
 * core, BEFORE this seam) — so a bridge consumer can never see an unmasked event through it either.
 */
interface SessionObserver {
    /** Each masked event, at the SINGLE post-mask point before the stream forks to UI + hub (CYP-37). */
    fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent)

    /** A new work-run starts (a turn was injected). */
    fun onTurnStart(agentId: String, sessionId: String?, correlationId: String)

    /**
     * The process has terminated, **observed** — the caller has awaited `AgentProcess.awaitExitCode()` before
     * calling this. Never on a deliberate [ClaudeCodeSession.close]; that path cancels the reader first and
     * reports [onStopped] instead. Every call here is therefore an unbidden death.
     *
     * The old KDoc read *"The process exited on its own (stdout completed)"* — the parenthesis admitted the
     * inference the name denied. Stdout completing is the end of the **stream**, not of the process; a process
     * can close stdout and keep running. Since CYP-351 the caller confirms with `waitFor()` before speaking, so
     * the name `onProcessExit` finally says what it does. It never did before.
     *
     * [exitCode] is `0` for a clean exit, non-zero for a crash or signal, and `null` when the process has no
     * observable status (a test double, a remote self-report). `null` means **unknown**, never "clean": a
     * consumer that reads it as `0` cannot tell a finished agent from a dead one, and will file a crash as
     * routine.
     */
    fun onProcessExit(agentId: String, sessionId: String?, exitCode: Int?)

    /** The session was deliberately closed/stopped. */
    fun onStopped(agentId: String)
}
