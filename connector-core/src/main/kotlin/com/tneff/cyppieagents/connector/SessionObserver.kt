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

    /** The process exited on its own (stdout completed). */
    fun onProcessExit(agentId: String, sessionId: String?)

    /** The session was deliberately closed/stopped. */
    fun onStopped(agentId: String)
}
