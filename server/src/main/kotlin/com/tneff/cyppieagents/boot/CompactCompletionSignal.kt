package com.tneff.cyppieagents.boot

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * CYP-326 — the per-agent **compaction-completed** signal, the empirically-proven 2B seam. The mediator
 * reader observes a `{"type":"system","subtype":"status","compact_result":"success"}` event on an agent's
 * long-lived session (emitted when the platform injects `/compact`) and fires [onCompleted]; the
 * [CompactOrchestrator] subscribes to [events] to mark that agent's round complete.
 *
 * Unlike the token/busy holders this is an **event stream, not a latest-wins state** — each compaction is a
 * discrete occurrence (the orchestrator attributes it to the in-flight round by the send-time window). One
 * instance per [ProjectRuntime], selected by the active pointer (twin wiring of [AgentTokenUsageTracker]).
 */
class CompactCompletionSignal {

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block the reader on a slow consumer
    )

    /** agentIds that just completed a compaction, in order. The orchestrator collects these. */
    val events: Flow<String> = _events.asSharedFlow()

    /** Fired by the projector when a `compact_result:"success"` system event is seen for [agentId]. */
    fun onCompleted(agentId: String) {
        _events.tryEmit(agentId)
    }
}
