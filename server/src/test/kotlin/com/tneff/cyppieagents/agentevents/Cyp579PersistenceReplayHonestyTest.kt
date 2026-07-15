package com.tneff.cyppieagents.agentevents

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-579 — the persistence-replay-honesty teeth (regression tests for the 3 silent-swallow findings on the
 * CYP-571 lane). The fix is diagnosability-first: a durable-append failure is now WARN-logged (not silently
 * swallowed), and an undecodable stored row is replayed as a VISIBLE "unrenderable" placeholder at its own seq
 * (not silently skipped). Retry/dead-letter (true losslessness for the append path) is a ratified follow-on.
 */
class Cyp579PersistenceReplayHonestyTest {

    private fun attach(loggerName: String): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(loggerName) as Logger).addAppender(appender)
        return appender
    }

    // ---- #1: the recorder's durable-append failure is LOGGED, not silently swallowed ----
    @Test
    fun recorder_appendFailure_isWarnLogged_notSilent_CYP579() = runBlocking<Unit> {
        val appender = attach("agentevents.recorder")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val attempted = CompletableDeferred<Unit>()
        val store = object : AgentEventStore {
            override suspend fun append(agentId: String, projectId: String, tsMs: Long, event: StreamJsonEvent): StoredAgentEvent {
                attempted.complete(Unit)
                throw RuntimeException("simulated SQLITE_BUSY / disk-full on append")
            }
            override fun subscribe(agentId: String, sinceSeq: Long?) = emptyFlow<StoredAgentEvent>()
            override suspend fun query(agentId: String, sinceSeq: Long?, limit: Int) = emptyList<StoredAgentEvent>()
            override suspend fun deleteByProject(projectId: String) = 0
        }
        AgentEventRecorder(store, scope).record("a1", "default", RateLimitEvent(sessionId = "s", uuid = "u1"))
        withTimeout(3_000) { attempted.await() }
        delay(300) // let onFailure fire
        // Mutation: drop the `.onFailure { log.warn(...) }` in AgentEventRecorder ⇒ no WARN captured ⇒ RED.
        val warn = appender.list.firstOrNull { it.level == Level.WARN && it.formattedMessage.contains("append FAILED") }
        assertNotNull(warn, "a durable-append failure MUST be WARN-logged (not silently swallowed) — CYP-575 class fix")
        assertTrue(warn.formattedMessage.contains("a1"), "the WARN names the affected agent for triage")
        scope.cancel()
    }

    // ---- #2/#3: an undecodable stored row is replayed as a VISIBLE placeholder at the same seq, not skipped ----
    @Test
    fun query_undecodableRow_emitsPlaceholderAtSameSeq_notSilentSkip_CYP579() = runBlocking<Unit> {
        val appender = attach("agentevents.sqlite")
        val db = Files.createTempFile("cyp579-decode", ".db")
        val store = SqliteAgentEventStore(db)
        // seq1 valid (via the store's own append) …
        store.append("a1", "default", 1_000L, RateLimitEvent(sessionId = "s", uuid = "u1"))
        // … seq2 = a corrupt/schema-drift row injected via a raw connection (the store only writes valid JSON) …
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            c.prepareStatement("INSERT INTO agent_events(agent_id,project_id,ts,event_json) VALUES (?,?,?,?)").use { ps ->
                ps.setString(1, "a1"); ps.setString(2, "default"); ps.setLong(3, 2_000L)
                ps.setString(4, "{\"type\":\"future_variant_from_a_newer_build\",\"uuid\":\"u2\"}") // unknown discriminator → decode throws
                ps.executeUpdate()
            }
        }
        // … seq3 valid.
        store.append("a1", "default", 3_000L, RateLimitEvent(sessionId = "s", uuid = "u3"))

        val rows = store.query("a1", null, 100)
        // The corrupt seq2 is NOT skipped — every seq 1..3 is present (Mutation: revert to getOrNull-skip ⇒ seq2 gone ⇒ RED).
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.seq }, "no silent gap — the undecodable row is replayed at its own seq")
        val placeholder = rows.first { it.seq == 2L }
        val ev = placeholder.event
        assertTrue(ev is UserEvent, "the placeholder is a UserEvent (renders via the injected-message path)")
        assertEquals(UNRENDERABLE_INJECTED_SOURCE, ev.injectedSource, "injectedSource set ⇒ StreamJsonMapper renders it as a VISIBLE IncomingSystem row, not a silent drop")
        // … and the drop-cause is WARN-logged for triage.
        assertTrue(
            appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("undecodable agent_event seq=2") },
            "the undecodable row is WARN-logged with its seq",
        )
        store.close(); Files.deleteIfExists(db)
    }

    // ---- the placeholder contract itself (single-sourced, seq-stable) ----
    @Test
    fun placeholder_carriesSameSeq_uniqueUuid_injectedSource_CYP579() {
        val p = unrenderableEventPlaceholder(42L)
        assertTrue(p is UserEvent)
        assertEquals(UNRENDERABLE_INJECTED_SOURCE, p.injectedSource)
        assertEquals("${UNRENDERABLE_UUID_PREFIX}42", p.uuid, "unique per-seq uuid ⇒ foldEvent id-dedup keeps one row across replays")
        assertTrue(p.message.content.any { it is com.tneff.cyppieagents.model.TextBlock }, "carries a text block the mapper renders")
    }

    @AfterTest
    fun detachAppenders() {
        (LoggerFactory.getLogger("agentevents.recorder") as Logger).detachAndStopAllAppenders()
        (LoggerFactory.getLogger("agentevents.sqlite") as Logger).detachAndStopAllAppenders()
    }
}
