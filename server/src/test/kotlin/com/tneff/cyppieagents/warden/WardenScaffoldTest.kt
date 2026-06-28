package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.scanner.EventLogSignalSink
import com.tneff.cyppieagents.scanner.Signal
import com.tneff.cyppieagents.scanner.SignalSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scaffold proof for the Decide+Act stage (07/S11, CYP-62):
 *  - the Warden routes a reconstructed [Signal] to the [Policy] that handles its type, ignores
 *    non-signal events, and isolates a throwing policy;
 *  - the [MediatorActuator] is the **only** write toward an agent — a nudge becomes a user-turn on the
 *    agent's session (the Mediator stdin) and an action Event on the bus, with no message body;
 *  - escalation never touches the agent.
 *
 * The write-boundary is structural: the [Warden] and [Policy] here are handed no session — the only
 * agent-affecting capability they can reach is the [Actuator]. The Reviewer focus ("kein Schreibpfad
 * am Actuator vorbei") is satisfied by construction.
 */
class WardenScaffoldTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        val turns = CopyOnWriteArrayList<String>()
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) { turns.add(turn.text) }
        override fun close() {}
    }

    private class CapturingSignalSink : SignalSink {
        val emitted = CopyOnWriteArrayList<Signal>()
        override suspend fun emit(signal: Signal) { emitted.add(signal) }
    }

    private class RecordingPolicy(
        private val type: String,
        private val act: (suspend (Signal, Actuator) -> Unit)? = null,
    ) : Policy {
        val seen = CopyOnWriteArrayList<Signal>()
        override fun handles(signalType: String) = signalType == type
        override suspend fun onSignal(signal: Signal, act: Actuator) {
            seen.add(signal)
            this.act?.invoke(signal, act)
        }
    }

    private object NoopActuator : Actuator {
        override suspend fun nudge(agentId: String, text: String) {}
        override suspend fun escalateToPO(agentId: String, reason: String) {}
    }

    private var seq = 0L
    private fun signalEvent(wire: String, agent: String = "backend", evidence: JsonObject = JsonObject(emptyMap())) = Event(
        id = "s${seq++}", ts = 0, seq = seq, agentId = agent, teamId = "default",
        type = EventType.UNKNOWN, rawType = wire, severity = Severity.WARN, detail = evidence,
    )
    private fun domainEvent() = Event(
        id = "d${seq++}", ts = 0, seq = seq, agentId = "backend", teamId = "default",
        type = EventType.ERROR_RATELIMIT, severity = Severity.WARN,
        detail = buildJsonObject { put("status", "blocked") },
    )

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ---- Warden routing -----------------------------------------------------------------------
    @Test
    fun routesSignalToHandlingPolicy_ignoresOthersAndDomainEvents() = runBlocking {
        val scope = scope()
        try {
            val stallPolicy = RecordingPolicy("stall.suspected")
            val warden = Warden(InMemoryEventSink(ManualTimeSource()), listOf(stallPolicy), NoopActuator, scope)

            warden.dispatch(signalEvent("stall.suspected", agent = "backend"))
            assertEquals(1, stallPolicy.seen.size)
            assertEquals("backend", stallPolicy.seen.single().agentId)

            // A signal type this policy doesn't handle → not routed to it.
            warden.dispatch(signalEvent("nudge.sent"))
            // A plain domain event → not a signal at all → ignored.
            warden.dispatch(domainEvent())
            assertEquals(1, stallPolicy.seen.size, "only the handled signal type reaches the policy")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingPolicyIsIsolated_peerStillRuns() = runBlocking {
        val scope = scope()
        try {
            val boom = RecordingPolicy("stall.suspected") { _, _ -> throw IllegalStateException("buggy policy") }
            val good = RecordingPolicy("stall.suspected")
            val warden = Warden(InMemoryEventSink(ManualTimeSource()), listOf(boom, good), NoopActuator, scope)
            warden.dispatch(signalEvent("stall.suspected"))
            assertEquals(1, good.seen.size, "a throwing policy must not starve its peers")
        } finally {
            scope.cancel()
        }
    }

    // ---- Actuator = the only agent write ------------------------------------------------------
    @Test
    fun nudge_injectsUserTurnOnSession_andEmitsNudgeSent_withoutText() = runBlocking {
        val scope = scope()
        try {
            val sessions = ConnectorSessions().apply { register(FakeSession("backend")) }
            val cap = CapturingSignalSink()
            val actuator = MediatorActuator(sessions, cap, teamId = "default")

            // Decide→Act through the seam: a policy acts ONLY via the actuator it is handed.
            val policy = RecordingPolicy("stall.suspected") { s, act -> act.nudge(s.agentId, "Bitte mach weiter.") }
            Warden(InMemoryEventSink(ManualTimeSource()), listOf(policy), actuator, scope)
                .dispatch(signalEvent("stall.suspected", agent = "backend"))

            val fake = sessions.session("backend") as FakeSession
            assertEquals(listOf("Bitte mach weiter."), fake.turns, "nudge must inject a user-turn on the Mediator stdin")
            assertTrue(cap.emitted.any { it.type == "nudge.sent" && it.agentId == "backend" }, "nudge.sent action event emitted")
            // metadata-only: the nudge body must not leak onto the bus.
            assertFalse(cap.emitted.toString().contains("mach weiter"), "nudge text must not be recorded")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nudge_withNoLiveSession_writesNothing_andEmitsNothing() = runBlocking {
        val scope = scope()
        try {
            val cap = CapturingSignalSink()
            // No session registered for "ghost".
            MediatorActuator(ConnectorSessions(), cap, teamId = "default").nudge("ghost", "x")
            assertTrue(cap.emitted.isEmpty(), "no live session → nothing sent → no nudge.sent (fail-closed honesty)")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun escalateToPO_emitsErrorEventWithReason_andNeverWritesToAgent() = runBlocking {
        val scope = scope()
        try {
            val sink = InMemoryEventSink(ManualTimeSource())
            val recorder = EventRecorder(sink, scope).also { it.start() }
            val sessions = ConnectorSessions().apply { register(FakeSession("backend")) }
            val actuator = MediatorActuator(sessions, EventLogSignalSink(recorder), teamId = "default")

            actuator.escalateToPO("backend", "exhausted nudges")

            val ev = withTimeout(5_000) {
                var found: Event? = null
                while (found == null) {
                    found = sink.query(EventFilter.ALL, Page(limit = 100)).events.firstOrNull { it.rawType == "po.escalated" }
                    if (found == null) delay(20)
                }
                found
            }
            assertEquals(Severity.ERROR, ev.severity, "escalation is an error-severity event (07 §4)")
            assertEquals("exhausted nudges", ev.detail["reason"]?.jsonPrimitive?.content)
            assertTrue((sessions.session("backend") as FakeSession).turns.isEmpty(), "escalation must never write to the agent")
        } finally {
            scope.cancel()
        }
    }

    // ---- Signal.fromEvent round-trip ----------------------------------------------------------
    @Test
    fun fromEvent_mapsSignalEventsAndRejectsDomainEvents() {
        val s = Signal.fromEvent(signalEvent("stall.suspected", agent = "frontend", evidence = buildJsonObject { put("status", "rejected") }))
        assertEquals("stall.suspected", s?.type)
        assertEquals("frontend", s?.agentId)
        assertEquals("rejected", s?.evidence?.get("status")?.jsonPrimitive?.content)
        assertEquals(null, Signal.fromEvent(domainEvent()), "a domain event is not a signal")
    }
}
