package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-326 — the empirically-proven completion seam: a `{"type":"system","subtype":"status",
 * "compact_result":"success"}` event (emitted when the orchestrator injects `/compact` into an agent's
 * long-lived session) → [EventProjector.onCompactCompleted]. `compacting` / `failed` / `init` must NOT fire
 * it — the 2B signal is SUCCESS only (never a faked completion, the CYP-325 honesty line).
 */
class EventProjectorCompactTest {

    private fun sys(json: String): StreamJsonEvent = CommJson.decodeFromString(json)

    private fun completedAgents(event: StreamJsonEvent): List<String> {
        val out = ArrayList<String>()
        EventProjector(ContextUsageBander(), projectId = "t", onCompactCompleted = { out.add(it) })
            .project("backend", "s", "c", event)
        return out
    }

    @Test
    fun compactResultSuccess_firesOnCompactCompleted() =
        assertEquals(listOf("backend"), completedAgents(sys("""{"type":"system","subtype":"status","compact_result":"success","session_id":"s"}""")))

    @Test
    fun compacting_status_doesNotFire() =
        assertEquals(emptyList(), completedAgents(sys("""{"type":"system","subtype":"status","status":"compacting","session_id":"s"}""")))

    @Test
    fun compactResultFailed_doesNotFire_neverFakeCompletion() =
        assertEquals(emptyList(), completedAgents(sys("""{"type":"system","subtype":"status","compact_result":"failed","session_id":"s"}""")))

    @Test
    fun systemInit_doesNotFire() =
        assertEquals(emptyList(), completedAgents(sys("""{"type":"system","subtype":"init","session_id":"s","cwd":"/x"}""")))

    // ---- the :core parse (the wire shape verified in the spike) ----

    @Test
    fun systemEvent_parsesCompactResult_andCompactCompletedGetter() {
        val ok = sys("""{"type":"system","subtype":"status","compact_result":"success","session_id":"s"}""") as SystemEvent
        assertEquals("success", ok.compactResult)
        assertTrue(ok.compactCompleted)
        val running = sys("""{"type":"system","subtype":"status","status":"compacting","session_id":"s"}""") as SystemEvent
        assertEquals("compacting", running.status)
        assertFalse(running.compactCompleted)
    }
}
