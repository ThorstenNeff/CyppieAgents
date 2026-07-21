package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import com.tneff.cyppieagents.acl.StubAclHub
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.agentview.StubAgentWritableApi
import com.tneff.cyppieagents.agentview.StubModeRepository
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.workspace.StubHubCapacitySource
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

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { EventLogDemoApp() }
}

/**
 * DEDICATED test/demo entry — NOT the prod web [App]. Renders [AgentShell] in an OPERATOR context with
 * fully STUB sources, so the operator-only Event-Log windows are present and addressable for the
 * Maestro-web flows (`maestro/eventlog-*.yaml`) WITHOUT a live server.
 *
 * Non-productive by construction: a hardcoded demo operator token + stub sources that talk to no
 * backend, in a SEPARATE artifact (`:app:webAppDemo`) that is never the prod build. Operator-gating
 * stays a real security boundary — the prod `:app:webApp` keeps `operatorToken = null` (Event-Log
 * windows omitted). This is **not** a prod-flippable switch (PO guardrail 2026-06-27).
 *
 * **"Fully stub" is a promise this entry has to keep for every port it uses (CYP-339).** A port left at
 * `null` does not fall back to a stub — it resolves to the HTTP/WS repository, which on a backendless
 * demo means an empty window, not an obvious failure. Two ports were still `null`:
 *  - `agentManagementRepository` drives the **dynamic agent-window list**, so the demo showed no agent
 *    windows at all and `maestro/smoke-web.yaml` (which addresses `agent.backend.*`) could not pass.
 *  - `aclApi`/`aclLiveSource` left the ACL matrix on "Couldn't load" behind a stale-connection banner.
 * The KDoc of both parameters still promised an in-memory stub; the default flipped to HTTP when CYP-97
 * (agents) and CYP-48 (ACL) landed and this entry was never pulled along.
 */
@Composable
fun EventLogDemoApp() {
    MaterialTheme {
        // One instance: StubAclHub is both the data port and the live source, so a demo `setAcl` echoes
        // its own broadcast exactly as the real hub's /ws/comm does. Two instances would not see each other.
        val aclHub = remember { StubAclHub() }
        AgentShell(
            modifier = Modifier.enableTestTagsAsResourceId().safeContentPadding().fillMaxSize(),
            config = ShellConfig.dev().copy(operatorToken = "demo-operator-stub"),
            sessionFactory = { StubAgentSession() },
            // CYP-738 (live-wire): the writable-agents seam is now ARMED in the shell — a null port resolves to the
            // live HttpAgentWritableApi, which on this backendless demo hits a dead endpoint and fail-closes every
            // agent composer to a read-only HINT (no input). Typing to an agent is the demo's core interaction (and
            // the Maestro-web flows'), so keep it editable: stub every seeded agent writable (the "fully stub"
            // promise this entry makes for every port — a null port does NOT fall back to a stub).
            agentWritableApi = StubAgentWritableApi(StubAgentManagementRepository.DEFAULT_AGENTS.map { it.id }),
            commApi = DemoCommApi,
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = SteadyDemoEventsSource(),
            // CYP-417: no server in the demo → the stub capacity source (default flows: no capacity, no rejects) so
            // the pill is honestly absent + no overload banner, instead of the live source hitting a dead socket.
            capacitySource = StubHubCapacitySource(),
            // Seeds po/frontend/backend — the agents the Maestro web flows address by id.
            agentManagementRepository = remember { StubAgentManagementRepository() },
            aclApi = aclHub,
            aclLiveSource = aclHub,
            // CYP-381: the demo has no server, so inject the local always-confirm stub for the hand-off command —
            // the mode toggle flips offline (matching every other stubbed surface) instead of erroring on the POST.
            modeRepository = StubModeRepository(),
        )
    }
}

/**
 * Minimal in-memory [CommApi] for the demo (no network). Its roster mirrors [StubAgentManagementRepository]'s
 * seed, so the comm panel names and colours every agent that actually has a window (CYP-339) — a demo whose
 * channel list disagrees with its window list teaches QA the wrong thing.
 */
private val DemoCommApi = object : CommApi {
    override suspend fun channels() = listOf(
        Channel("po-frontend", "PO <-> Frontend", ChannelKind.HUB, listOf("po", "frontend")),
        Channel("po-backend", "PO <-> Backend", ChannelKind.HUB, listOf("po", "backend")),
    )

    override suspend fun agents() = listOf(
        Agent("po", "Product Owner", Role.PO, "po"),
        Agent("frontend", "Frontend", Role.WORKER, "frontend"),
        Agent("backend", "Backend", Role.WORKER, "backend"),
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
                        id = "d$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", projectId = "team-1",
                        type = types[((seq - 1) % types.size).toInt()], severity = Severity.INFO,
                        correlationId = "run-1", sessionId = "sess-1",
                    ),
                ),
            )
            seq++
        }
    }
}
