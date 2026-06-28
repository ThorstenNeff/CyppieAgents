package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.Event
import java.util.random.RandomGenerator

/**
 * The single stamping step (PRD §3.2/§5): turn a content-free [EventDraft] into a fully-ordered
 * [Event] by assigning `ts` ([TimeSource.now]), `seq` ([TimeSource.nextSeq], monotonic) and a ULID.
 * Both sink implementations call this **under their write lock**, so `seq` order equals insert order
 * equals total order — owned in exactly one place, never at the callers.
 */
internal fun TimeSource.stamp(draft: EventDraft, rnd: RandomGenerator): Event {
    val ts = now()
    return Event(
        id = Ulid.generate(ts, rnd),
        ts = ts,
        seq = nextSeq(),
        sourceTs = draft.sourceTs,
        agentId = draft.agentId,
        projectId = draft.projectId,
        sessionId = draft.sessionId,
        correlationId = draft.correlationId,
        type = draft.type,
        rawType = draft.rawType,
        severity = draft.severity,
        detail = draft.detail,
    )
}
