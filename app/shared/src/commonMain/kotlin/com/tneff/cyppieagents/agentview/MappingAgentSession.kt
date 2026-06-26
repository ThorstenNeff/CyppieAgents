package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The real [AgentSession]: maps a stream of frozen `:core` [StreamJsonEvent]s into UI [AgentEvent]s
 * via [StreamJsonMapper], and turns a human message into a [UserTurn] for the outbound sink.
 *
 * Transport-agnostic on purpose. The Hub-WS adapter supplies:
 *  - [source]: the per-agent, already-masked [StreamJsonEvent] stream (server → client), and
 *  - [sink]: a function that writes a [UserTurn] back (client → server; the mediator injects it
 *    on the CLI stdin).
 *
 * `sendMessage` stays Hub-mediated — it hands a [UserTurn] to [sink], never touching stdin (05 §2).
 *
 * NOTE (flagged to PO/backend): the per-agent event-stream WS endpoint that produces [source] /
 * accepts [sink] is not part of the CYP-9 `:core` freeze (server side untouched). Its route +
 * frame contract must be reconciled before the Ktor adapter is wired; until then the app uses
 * [StubAgentSession]. This class is the mapping seam that adapter will plug into.
 */
class MappingAgentSession(
    private val source: Flow<StreamJsonEvent>,
    private val sink: (UserTurn) -> Unit,
) : AgentSession {

    // Fresh mapper per collection so the tool-id linkage state is never shared across collectors.
    override val events: Flow<AgentEvent> = flow {
        val mapper = StreamJsonMapper()
        source.collect { wire -> mapper.map(wire).forEach { emit(it) } }
    }

    override fun sendMessage(text: String) = sink(UserTurn(text))
}
