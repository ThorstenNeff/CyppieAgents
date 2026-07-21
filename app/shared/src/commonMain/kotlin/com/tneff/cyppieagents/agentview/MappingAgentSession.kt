package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * The real [AgentSession]: maps a stream of frozen `:core` [StoredAgentEvent]s into UI [AgentEvent]s
 * via [StreamJsonMapper], and turns a human message into a [UserTurn] for the outbound sink.
 *
 * Transport-agnostic on purpose. The Hub-WS adapter supplies:
 *  - [source]: the per-agent, already-masked [StoredAgentEvent] stream (server → client), and
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
    private val source: Flow<StoredAgentEvent>,
    private val sink: (UserTurn) -> Unit,
    /** CYP-204: the WS adapter's live connection state (default LIVE for tests that pass only a source). */
    override val connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.LIVE),
    /**
     * CYP-383: the localized "agent ready" label, resolved by the composable caller (AgentShell) via
     * `stringResource` and threaded into each fresh [StreamJsonMapper] — the mapper holds no user-facing literal.
     */
    private val readyNoticeText: String,
    /**
     * CYP-386: the localized "turn error" label, resolved by the composable caller (AgentShell) via `stringResource`
     * and threaded into each fresh [StreamJsonMapper] — like [readyNoticeText], no user-facing literal lives here.
     */
    private val turnErrorLabel: String,
) : AgentSession {

    // Fresh mapper per collection so the tool-id linkage (and once-per-session ready) state is never shared.
    override val events: Flow<AgentEvent> = flow {
        val mapper = StreamJsonMapper(readyNoticeText, turnErrorLabel)
        // CYP-335: the envelope's server-stamped `tsMs` dates every row the wire event produces.
        source.collect { stored -> mapper.map(stored.event, stored.tsMs).forEach { emit(it) } }
    }

    override fun sendMessage(text: String) = sink(UserTurn(text))
}
