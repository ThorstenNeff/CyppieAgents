package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stand-in [CommLiveSource] for ungated development (CYP-21): no `/ws/comm` yet. Emits a Connected
 * event then a couple of scripted pushes on [channelId] so the live timeline path is exercised.
 * Replaced by the Ktor `/ws/comm` adapter (CYP-18) — the only follow-up commit; this seam is why.
 */
class StubCommLiveSource(
    private val channelId: String = "po-frontend",
) : CommLiveSource {

    override fun events(): Flow<CommLiveEvent> = flow {
        emit(CommLiveEvent.Connected)
        delay(STEP)
        emit(CommLiveEvent.MessageReceived(msg("live-1", "frontend", "bin dran am Renderer-Slice", ts = 10_000)))
        delay(STEP)
        emit(CommLiveEvent.MessageReceived(msg("live-2", "po", "danke — Status?", kind = MessageKind.STATUS, ts = 10_001)))
    }

    private fun msg(id: String, from: String, body: String, ts: Long, kind: MessageKind? = null): Message =
        Message(
            id = id,
            channelId = channelId,
            from = from,
            body = body,
            ts = ts,
            meta = kind?.let { MessageMeta(kind = it) },
        )

    private companion object {
        const val STEP = 400L
    }
}
