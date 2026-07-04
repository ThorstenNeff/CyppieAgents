package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-198 — the durable agent-window transcript teeth. Load-bearing properties (the PO's gate): a reconnect
 * replays persisted history (non-vacuous, not the blank `replay=0` screen); history survives a server
 * restart (SQLite reopen); the seq cursor is gapless + de-duplicated across the history→live boundary; and
 * the remote signal path persists too. Masking is upstream (ClaudeCodeSession `:74`) — the store only ever
 * receives (and here only ever holds) already-masked events.
 */
class AgentEventStoreTest {

    private fun ev(i: Int): StreamJsonEvent = RateLimitEvent(sessionId = "s1", uuid = "u$i")

    @Test
    fun history_survivesRestart_gaplessSeq() = runBlocking<Unit> {
        val db = Files.createTempFile("agentev-restart", ".db")
        SqliteAgentEventStore(db).use { s -> repeat(3) { s.append("a1", "default", 1_000L + it, ev(it)) } }
        // Reopen the SAME db (a restart): the transcript is still there, gapless.
        SqliteAgentEventStore(db).use { s2 ->
            val rows = s2.query("a1", null, 100)
            assertEquals(3, rows.size, "3 events survived the restart")
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.seq }, "gapless, monotonic seq across restart")
            // Appending after restart continues the seq (never reused) — a live client's cursor stays valid.
            assertEquals(4L, s2.append("a1", "default", 5_000L, ev(9)).seq, "seq continues above the max after restart")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun subscribe_replaysFromCursor_thenLive_noGapNoDup() = runBlocking<Unit> {
        val db = Files.createTempFile("agentev-sub", ".db")
        val store = SqliteAgentEventStore(db)
        repeat(5) { store.append("a1", "default", 1_000L, ev(it)) } // seq 1..5 durable
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val got = CopyOnWriteArrayList<StoredAgentEvent>()
        // Reconnect with the client's cursor at seq=2 → must replay 3,4,5 (backfill) then stream live.
        val job = scope.launch { store.subscribe("a1", sinceSeq = 2L).collect { got.add(it) } }
        withTimeout(3_000) { while (got.size < 3) delay(10) } // replay landed
        store.append("a1", "default", 2_000L, ev(5)) // live → seq 6
        store.append("a1", "default", 2_000L, ev(6)) // live → seq 7
        withTimeout(3_000) { while (got.size < 5) delay(10) }
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), got.map { it.seq }, "replay(3,4,5) then live(6,7) — gapless, no dup across the boundary")
        job.cancel(); store.close(); scope.cancel(); Files.deleteIfExists(db)
    }

    @Test
    fun subscribe_fromStart_replaysWholeTranscript_notBlank() = runBlocking<Unit> {
        // The core reconnect fix: subscribe(since=null) gets the WHOLE persisted history (vs today's blank).
        val db = Files.createTempFile("agentev-whole", ".db")
        val store = SqliteAgentEventStore(db)
        repeat(4) { store.append("a1", "default", 1_000L, ev(it)) }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val got = CopyOnWriteArrayList<StoredAgentEvent>()
        val job = scope.launch { store.subscribe("a1", sinceSeq = null).collect { got.add(it) } }
        withTimeout(3_000) { while (got.size < 4) delay(10) }
        assertEquals(listOf(1L, 2L, 3L, 4L), got.map { it.seq }, "a fresh reconnect replays the full transcript (non-vacuous)")
        assertTrue(got.all { it.agentId == "a1" }, "scoped to the agent")
        job.cancel(); store.close(); scope.cancel(); Files.deleteIfExists(db)
    }

    @Test
    fun retention_keepsLastN_seqStaysGapless() = runBlocking<Unit> {
        val db = Files.createTempFile("agentev-ret", ".db")
        SqliteAgentEventStore(db, retainPerAgent = 3).use { s ->
            repeat(6) { s.append("a1", "default", 1_000L, ev(it)) } // seq 1..6; retention keeps the last 3
            val rows = s.query("a1", null, 100)
            assertEquals(listOf(4L, 5L, 6L), rows.map { it.seq }, "only the last 3 survive; seq unchanged (cursor stays valid)")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun perAgentIsolation_andProjectCascade() = runBlocking<Unit> {
        val db = Files.createTempFile("agentev-iso", ".db")
        SqliteAgentEventStore(db).use { s ->
            s.append("a1", "pA", 1_000L, ev(1)); s.append("a2", "pB", 1_000L, ev(2)); s.append("a1", "pA", 1_000L, ev(3))
            assertEquals(2, s.query("a1", null, 100).size, "a1's transcript excludes a2's events")
            assertEquals(2, s.deleteByProject("pA"), "cascade-delete purges the project's transcript")
            assertEquals(0, s.query("a1", null, 100).size, "a1 (project pA) purged")
            assertEquals(1, s.query("a2", null, 100).size, "a2 (project pB) intact")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun recorder_feedsStoreInOrder() = runBlocking<Unit> {
        // The observer tap → AgentEventRecorder (non-blocking) → store, in emit order.
        val db = Files.createTempFile("agentev-rec", ".db")
        val store = SqliteAgentEventStore(db)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rec = AgentEventRecorder(store, scope) { 1_000L }
        repeat(5) { rec.record("a1", "default", ev(it)) }
        withTimeout(3_000) { while (store.query("a1", null, 100).size < 5) delay(10) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.query("a1", null, 100).map { it.seq }, "recorder appends in order, gapless")
        store.close(); scope.cancel(); Files.deleteIfExists(db)
    }
}
