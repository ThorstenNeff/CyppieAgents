package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CoroutineScope

/**
 * CYP-142 (S4.0) — the `:server` factory that builds a [ClaudeCodeSession] from the HUB types, mapping
 * them onto the shared core's seams: `registry`+`onSessionBound`→`onBind`, `registry`→`onUnbind`,
 * `router`→`onTurnResult`, `recorder`+`projector`→[RecordingSessionObserver]. Same positional signature
 * as the pre-S4.0 session ctor, so the local connector + its tests wire identically (behavior unchanged).
 * The remote bridge builds its own [ClaudeCodeSession] with wire-relay/no-op seams (no hub types).
 */
fun claudeCodeServerSession(
    agentId: String,
    process: AgentProcess,
    registry: SessionRegistry,
    router: MediationRouter,
    turnQueue: SessionTurnQueue,
    scope: CoroutineScope,
    recorder: EventRecorder? = null,
    projector: EventProjector? = null,
    onSessionBound: ((String) -> Unit)? = null,
): ClaudeCodeSession = ClaudeCodeSession(
    agentId = agentId,
    process = process,
    turnQueue = turnQueue,
    scope = scope,
    observer = if (recorder != null && projector != null) RecordingSessionObserver(recorder, projector) else null,
    onBind = { sid -> registry.bind(sid, agentId); onSessionBound?.invoke(sid) },
    onUnbind = { sid -> registry.unbind(sid) },
    onTurnResult = { router.onResult(it) },
)

/**
 * CYP-142 (S4.0) — the `:server` implementation of the [SessionObserver] seam: the CYP-37 observability
 * tap. It projects the masked event into Event-Log drafts and records them (non-blocking trySend). The
 * remote bridge supplies `null` (or, later, a wire self-report tap for G4) — so the hub recorder/projector
 * stay out of `:connector-core` and out of user infra.
 */
class RecordingSessionObserver(
    private val recorder: EventRecorder,
    private val projector: EventProjector,
) : SessionObserver {
    override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
        projector.project(agentId, sessionId, correlationId, event).forEach { recorder.record(it) }
    }

    override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {
        recorder.record(projector.turnStart(agentId, sessionId, correlationId))
    }

    override fun onProcessExit(agentId: String, sessionId: String?) {
        recorder.record(projector.processExit(agentId, sessionId, null))
    }

    override fun onStopped(agentId: String) {
        recorder.record(projector.agentStopped(agentId))
    }
}
