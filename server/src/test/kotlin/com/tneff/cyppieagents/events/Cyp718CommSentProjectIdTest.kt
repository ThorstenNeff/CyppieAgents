package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * CYP-718 — comm.sent must bucket into the MESSAGE's tenant, not the projector's boot constant.
 *
 * comm.sent is emitted from the SHARED chokepoint projector (CYP-255), whose `projectId` is fixed to the boot
 * project. `Hub.postAsAgent` server-stamps every message with the ACTIVE project (Hub.kt:64). If comm.sent took
 * the projector's fixed project, then after a project switch every remote / human / mediated send would land in
 * the WRONG tenant's event bucket — a multi-hub isolation break. The fix stamps `msg.projectId`.
 *
 * Here the shared projector is pinned to "boot-project" while the hub is active on a DIFFERENT tenant
 * ("active-project") — the post-switch reality. MUTATION: drop the `projectIdOverride` in
 * `EventProjector.commSent` (fall back to the projector's fixed project) → this reds, because the two differ.
 * Non-vacuous: the assertNotEquals pins that it is NOT the boot constant, so a green means the ACTIVE project
 * specifically, not "some project that happens to match".
 */
class Cyp718CommSentProjectIdTest {

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    @Test
    fun commSentCarriesTheActiveMessageProject_notTheProjectorsBootConstant() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        // The SHARED projector is fixed to the BOOT project; the ACTIVE project differs (one shared projector
        // serves whichever project is active — CYP-255).
        val projector = EventProjector(ContextUsageBander(), projectId = "boot-project")
        val hub = Hub(
            HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID, activeProjectId = "active-project"),
            InMemoryMessageStore(),
        )
        hub.onSent = { msg -> recorder.record(projector.commSent(msg.from, msg.channelId, msg.meta?.kind, msg.projectId)) }

        // Post while "active-project" is active → the Message is server-stamped projectId="active-project".
        hub.postAsAgent("backend", "po-backend", "tenant-probe")

        withTimeout(5_000) {
            while (sink.query(EventFilter.ALL, Page(limit = 100)).events.none { it.type == EventType.COMM_SENT }) delay(10)
        }
        val e = sink.query(EventFilter.ALL, Page(limit = 100)).events.single { it.type == EventType.COMM_SENT }
        assertEquals("active-project", e.projectId, "CYP-718: comm.sent must bucket into the ACTIVE project (msg.projectId)")
        assertNotEquals("boot-project", e.projectId, "must NOT carry the shared projector's fixed boot project")

        recorder.stop()
        scope.cancel()
    }
}
