package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.connector.RecordingSessionObserver
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-351 (second half) — the Event-Log's `process.exit` must carry the **exit code**, and a non-zero exit
 * must be a `WARN`.
 *
 * [EventProjector.processExit] always graded a non-zero exit as WARN and put `exitCode` into the detail. Both
 * were unreachable: the only caller, [RecordingSessionObserver.onProcessExit], had no exit code to give and
 * passed a hard-coded `null`. So every crash was logged as an INFO with no code, while the code that would
 * have said otherwise sat there looking correct — Doc 06 §4 promises `process.exit` (Code).
 *
 * This test drives the real call site, not the projector in isolation: a projector-only assertion would have
 * passed before the fix and proved nothing about what actually reaches the log.
 *
 * Mutation probe: restore `projector.processExit(agentId, sessionId, null)` → the WARN and the code both go.
 */
class ProcessExitCodeRecordedTest {

    /** Captures the drafts the recorder drains, and stamps them just enough to satisfy the contract. */
    private class CapturingSink : EventSink {
        val drafts = CopyOnWriteArrayList<EventDraft>()
        private var seq = 0L

        override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> {
            this.drafts.addAll(drafts)
            return drafts.map { d ->
                Event(
                    id = "e${++seq}", ts = seq, seq = seq, agentId = d.agentId, projectId = d.projectId,
                    type = d.type, severity = d.severity, detail = d.detail,
                )
            }
        }

        override suspend fun query(filter: EventFilter, page: Page): EventPage = error("unused")
        override fun subscribe(filter: EventFilter): Flow<Event> = emptyFlow()
        override suspend fun deleteByProject(projectId: String): Int = 0
    }

    private fun exitDraftFor(exitCode: Int?): EventDraft = runBlocking {
        val sink = CapturingSink()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val projector = EventProjector(ContextUsageBander(), projectId = "team")

        RecordingSessionObserver(recorder, projector).onProcessExit("backend", "sess-1", exitCode)

        recorder.stop() // closes the queue, drains it into the sink, awaits the writer
        sink.drafts.single { it.type == EventType.PROCESS_EXIT }
    }

    @Test
    fun nonZeroExit_isWarn_andCarriesTheCode() {
        val draft = exitDraftFor(3)
        assertEquals(Severity.WARN, draft.severity, "a crash is not an INFO")
        assertEquals(JsonPrimitive(3), draft.detail["exitCode"], "the exit code must reach the log")
    }

    @Test
    fun cleanExit_isInfo_andCarriesTheZero() {
        // A *confirmed* clean exit — the only case with enough evidence to be routine.
        val draft = exitDraftFor(0)
        assertEquals(Severity.INFO, draft.severity)
        assertEquals(JsonPrimitive(0), draft.detail["exitCode"])
    }

    /**
     * The guard on the line that made the defect. `if ((exitCode ?: 0) != 0)` reads "unknown" as "exited
     * cleanly" and files an unreadable death as INFO — invisible to an operator filtering `severity >= warn`
     * (Doc 06 §3). Re-introduce the `?: 0` and this test goes red; that is its whole purpose.
     */
    @Test
    fun unknownExit_isAtLeastWarn_neverInfo_andInventsNoCode() {
        val draft = exitDraftFor(null)
        assertNotEquals(Severity.INFO, draft.severity, "unknown must never be filed as clean")
        assertTrue(
            draft.severity == Severity.WARN || draft.severity == Severity.ERROR,
            "unknown fails closed to at least WARN, was ${draft.severity}",
        )
        assertNull(draft.detail["exitCode"], "a code we never observed must not be fabricated")
    }
}
