package com.tneff.cyppieagents.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tneff.cyppieagents.AgentShell
import com.tneff.cyppieagents.ShellConfig
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DEDICATED test/demo entry — NOT the prod [com.tneff.cyppieagents.MainActivity] / [com.tneff.cyppieagents.App].
 * Renders [AgentShell] in an OPERATOR context with fully STUB sources, so the operator-only Event-Log
 * windows are present and addressable for the Maestro-android flows (`maestro/eventlog-*-android.yaml`)
 * WITHOUT a live server.
 *
 * Non-productive by construction: a hardcoded demo operator token + stub sources that talk to no
 * backend, in a SEPARATE artifact (`:app:androidAppDemo`, applicationId `com.tneff.cyppieagents.demo`)
 * that is never the prod build. Operator-gating stays a real security boundary — the prod
 * `:app:androidApp` keeps `operatorToken = null` (Event-Log windows omitted). This is **not** a
 * prod-flippable switch (PO guardrail 2026-06-27). Mirrors `:app:webAppDemo`'s `EventLogDemoApp`.
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EventLogDemoApp() }
    }
}

@Composable
fun EventLogDemoApp() {
    MaterialTheme {
        AgentShell(
            modifier = Modifier.enableTestTagsAsResourceId().safeContentPadding().fillMaxSize(),
            config = ShellConfig.dev().copy(operatorToken = "demo-operator-stub"),
            sessionFactory = { StubAgentSession() },
            commApi = DemoCommApi,
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = SteadyDemoEventsSource(),
        )
    }
}

/** Minimal in-memory [CommApi] for the demo (no network). */
private val DemoCommApi = object : CommApi {
    override suspend fun channels() =
        listOf(Channel("po-frontend", "PO <-> Frontend", ChannelKind.HUB, listOf("po", "frontend")))

    override suspend fun agents() = listOf(
        Agent("po", "Product Owner", Role.PO, "po"),
        Agent("frontend", "Frontend", Role.WORKER, "frontend"),
    )

    override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()

    override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
        Message("demo", channelId, "operator", body, 1L)
}

/**
 * A live source that keeps streaming at a steady cadence, so the Live-Tail buffered-count pill appears
 * when the Maestro flow pauses mid-stream. Demo-only; the real `EventsWsClient` (CYP-40) replaces it.
 */
private class SteadyDemoEventsSource(private val stepMillis: Long = 800L) : EventLiveSource {
    override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
        emit(EventLiveEvent.Connected)
        val types = listOf(
            EventType.TURN_START, EventType.TOOL_CALL, EventType.TOOL_RESULT,
            EventType.CONTEXT_USAGE, EventType.RESULT_FINAL,
        )
        var seq = 1L
        while (true) {
            delay(stepMillis)
            emit(
                EventLiveEvent.Received(
                    Event(
                        id = "d$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", teamId = "team-1",
                        type = types[((seq - 1) % types.size).toInt()], severity = Severity.INFO,
                        correlationId = "run-1", sessionId = "sess-1",
                    ),
                ),
            )
            seq++
        }
    }
}
