package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
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
 * CYP-721 — comm.received must bucket into the delivered MESSAGE's tenant, not the projector's boot constant.
 *
 * The F3 sibling of CYP-718. `MessageDeliverer` records `comm.received` from the SHARED chokepoint projector
 * (CYP-255), whose `projectId` is fixed to the boot project. After a project switch, a boot-stamped
 * `comm.received` would land in the WRONG tenant's event log (multi-hub isolation break). The fix stamps the
 * delivered message's `projectId` (`MessageDeliverer` now passes `m.projectId`).
 *
 * Here the shared projector is pinned to "boot-project" while the recorded event's message project is
 * "active-project" (the post-switch reality). MUTATION: drop the `projectIdOverride` in `commReceived` (fall
 * back to the projector's fixed project) → this reds. `assertNotEquals` pins it is NOT the boot constant.
 */
class Cyp721CommReceivedProjectIdTest {

    @Test
    fun commReceivedCarriesTheMessageProject_notTheProjectorsBootConstant() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val projector = EventProjector(ContextUsageBander(), projectId = "boot-project")

        // The exact call MessageDeliverer makes: (recipient, messageId, channel, the delivered message's project).
        recorder.record(projector.commReceived("backend", "msg-1", "po-backend", "active-project"))

        withTimeout(5_000) {
            while (sink.query(EventFilter.ALL, Page(limit = 100)).events.none { it.type == EventType.COMM_RECEIVED }) delay(10)
        }
        val e = sink.query(EventFilter.ALL, Page(limit = 100)).events.single { it.type == EventType.COMM_RECEIVED }
        assertEquals("active-project", e.projectId, "CYP-721: comm.received must bucket into the delivered message's project")
        assertNotEquals("boot-project", e.projectId, "must NOT carry the shared projector's fixed boot project")

        recorder.stop()
        scope.cancel()
    }
}
