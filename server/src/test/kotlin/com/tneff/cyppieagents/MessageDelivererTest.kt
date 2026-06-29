package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.support.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-132 — the MessageDeliverer injects readable hub messages into recipient sessions as inbound,
 * durably. Unit-level (RecordingSession + in-memory stores). The production boot-wiring is pinned
 * separately by [DelivererBootWiringTest] (R2).
 *
 * Determinism: the deliverer runs drains on a [Dispatchers.Unconfined] scope. `drain` has no real
 * suspension point (the in-memory mutex acquires inline; RecordingSession.sendTurn does not suspend),
 * so each drain runs **inline** — delivery is complete by the time `postAsAgent`/`register` returns.
 */
class MessageDelivererTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    /** Wires a hub + deliverer exactly as boot does (onPosted + register-listener), over in-memory stores. */
    private class Fixture(agents: List<Agent>, scope: CoroutineScope) {
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID) // operator as boot wires it
        val store = InMemoryMessageStore()
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val log = InMemoryDeliveryLog()
        val deliverer = MessageDeliverer({ state }, { state.activeProjectId }, sessions, store, log, scope)
        init {
            hub.onPosted = deliverer::onPosted
            sessions.addRegisterListener(deliverer::onSessionAttached)
        }
        fun attach(agentId: String): RecordingSession = RecordingSession(agentId).also { sessions.register(it) }
    }

    @Test
    fun poTaskIsInjectedIntoWorkerInbound() {
        val f = Fixture(agents(), scope)
        val backend = f.attach("backend")
        f.hub.postAsAgent("po", "po-backend", "build the thing", MessageMeta(kind = MessageKind.TASK))
        assertTrue(backend.received.any { it.contains("build the thing") })
    }

    @Test
    fun nonReaderGetsNoDelivery() {
        val f = Fixture(agents(), scope)
        val backend = f.attach("backend")     // reader of po-backend
        val frontend = f.attach("frontend")   // NOT a member of po-backend
        f.hub.postAsAgent("po", "po-backend", "for backend only")
        assertTrue(backend.received.any { it.contains("for backend only") }) // positive control
        assertTrue(frontend.received.isEmpty(), "a non-reader must receive no inbound")
    }

    @Test
    fun downRecipientIsPersistedAndDeliveredOnAttach() {
        val f = Fixture(agents(), scope)
        // backend session is DOWN (not registered) when the task is posted.
        f.hub.postAsAgent("po", "po-backend", "task while down", MessageMeta(kind = MessageKind.TASK))
        // The message is persisted in the store, and nothing was delivered (no session)…
        assertTrue(f.store.byChannel("po-backend").any { it.body.contains("task while down") })
        // …now attach → replay on attach (the real RB1 class; a 2-live-session test can't catch this).
        val backend = f.attach("backend")
        assertTrue(backend.received.any { it.contains("task while down") })
    }

    @Test
    fun alreadyDeliveredIsNotReinjectedOnReattach() {
        val f = Fixture(agents(), scope)
        val first = f.attach("backend")
        f.hub.postAsAgent("po", "po-backend", "one-shot task")
        assertTrue(first.received.any { it.contains("one-shot task") })
        // Detach + re-attach a FRESH session — the delivered-id cursor must not re-inject.
        f.sessions.remove("backend")
        val second = f.attach("backend")
        assertTrue(second.received.isEmpty(), "a re-attached session must not get an already-delivered message again")
    }

    @Test
    fun workerStatusIsPushedToCoordinatorInbound() {
        val f = Fixture(agents(), scope)
        val po = f.attach("po")
        f.attach("backend")
        // watch-as-inbound: the worker posts STATUS; the coordinator (a reader of the spoke) gets it pushed.
        f.hub.postAsAgent("backend", "po-backend", "status: done", MessageMeta(kind = MessageKind.STATUS))
        assertTrue(po.received.any { it.contains("status: done") })
    }

    @Test
    fun senderNeverReceivesOwnMessage() {
        val f = Fixture(agents(), scope)
        val backend = f.attach("backend")
        val po = f.attach("po")
        f.hub.postAsAgent("backend", "po-backend", "my own update")
        assertTrue(po.received.any { it.contains("my own update") }) // positive control: PO gets it
        assertFalse(backend.received.any { it.contains("my own update") }, "the sender must not receive its own message")
    }

    @Test
    fun allReadableMembersAreDelivered_notJustTheFirst() {
        // m1: po-backend members = [po, backend, operator]. Post AS operator → BOTH po and backend
        // (the two agent-readers, ≠ sender) must receive it. A deliver-to-first-only bug would drop one.
        val f = Fixture(agents(), scope)
        val po = f.attach("po")
        val backend = f.attach("backend")
        f.hub.postAsAgent(HubState.OPERATOR_ID, "po-backend", "fan out to all")
        assertTrue(po.received.any { it.contains("fan out to all") }, "po delivered")
        assertTrue(backend.received.any { it.contains("fan out to all") }, "backend delivered")
    }

    @Test
    fun foreignProjectMessageIsNeverInjected() {
        // R1 ⭐ SECURITY: a foreign-project message in a REUSED channel id must NOT be delivered — the
        // project gate (visibleMessages) is the second gate beside canRead.
        val f = Fixture(agents(), scope)
        f.store.append(
            Message(
                id = "m-foreign", channelId = "po-backend", from = "ghost",
                body = "NEEDLE_cross_project_xyz", ts = 1L, projectId = "other-project",
            ),
        )
        val backend = f.attach("backend")
        // Content pre-guard / positive control: an in-project message IS delivered.
        f.hub.postAsAgent("po", "po-backend", "legit in-project task")
        assertTrue(backend.received.any { it.contains("legit in-project task") }, "in-project delivered")
        // Raw-byte needle: the foreign-project body never reached the session.
        assertFalse(
            backend.received.any { it.contains("NEEDLE_cross_project_xyz") },
            "a foreign-project message in a reused channel must never be injected",
        )
    }

    @Test
    fun surviveStoreResetWithSurvivingLog_stillDelivers() {
        // R3: dedup is over message.id, so a store RESET (fresh empty store) with a SURVIVING DeliveryLog
        // still delivers a fresh message. A positional-ordinal cursor would skip it: run-1 advances the
        // cursor for (backend, po-backend), then the reset puts the fresh message back at ordinal 0 ≤ cursor.
        val st = HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID)
        val log = InMemoryDeliveryLog() // SURVIVES the store reset (the durable per-recipient state)

        // Run 1: deliver one message — advances any per-(agent,channel) positional cursor.
        run {
            val store = InMemoryMessageStore()
            val sessions = ConnectorSessions()
            val d = MessageDeliverer({ st }, { st.activeProjectId }, sessions, store, log, scope)
            val hub = Hub(st, store).also { it.onPosted = d::onPosted }
            sessions.addRegisterListener(d::onSessionAttached)
            sessions.register(RecordingSession("backend"))
            hub.postAsAgent("po", "po-backend", "run1 message")
        }

        // Run 2: store is RESET (fresh, empty) — only [log] survives (JsonFileMessageStore corrupt→empty).
        val store2 = InMemoryMessageStore()
        val sessions2 = ConnectorSessions()
        val d2 = MessageDeliverer({ st }, { st.activeProjectId }, sessions2, store2, log, scope)
        val hub2 = Hub(st, store2).also { it.onPosted = d2::onPosted }
        sessions2.addRegisterListener(d2::onSessionAttached)
        val backend2 = RecordingSession("backend")
        sessions2.register(backend2)
        hub2.postAsAgent("po", "po-backend", "run2 fresh message") // brand-new UUID, ordinal 0 in the reset store
        assertTrue(
            backend2.received.any { it.contains("run2 fresh message") },
            "a fresh message after a store reset must still be delivered (id-dedup, not positional)",
        )
    }
}
