package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.agentevents.AgentEventStore
import com.tneff.cyppieagents.agentevents.MigrationGatedAgentEventStore
import com.tneff.cyppieagents.agentevents.PgAgentEventStore
import com.tneff.cyppieagents.agentevents.SqliteAgentEventStore
import com.tneff.cyppieagents.boot.PgStoreRouting
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.db.DsnDescriptor
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.db.DsnTierOrigin
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.routing.ConflictException
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 6 S5 — the high-volume append-only stores on Postgres. The load-bearing tooth is **parity
 * Pg==Sqlite**: the SAME drafts, stamped by identical deterministic [TimeSource]s, produce byte-identical
 * [Event]s AND identical **paginated** query pages + cursors across both impls (batch/pagination parity — the
 * PO's "volume counts" ask). Plus MigrationTarget round-trip, routing, and the S3 migration-window freeze.
 */
class PgEventStoresTest {

    /** Deterministic [TimeSource]: fixed wall-clock + a private monotonic seq → two instances stamp identically. */
    private class FixedTimeSource(private val fixedNow: Long = 1_000L) : TimeSource {
        private val seq = AtomicLong(0)
        override fun now(): Long = fixedNow
        override fun nextSeq(): Long = seq.incrementAndGet()
        override fun resumeAtLeast(seq: Long) { this.seq.updateAndGet { if (seq > it) seq else it } }
    }

    private fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))

    private fun dsnRegistryFor(pgPort: Int): DsnRegistry = DsnRegistry(null, cipher()).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    private val types = listOf(EventType.TURN_START, EventType.TOOL_CALL, EventType.RESULT_FINAL)
    private fun drafts(n: Int, project: String = "p"): List<EventDraft> = (1..n).map {
        EventDraft(agentId = "a${it % 3}", projectId = project, type = types[it % 3], severity = Severity.INFO)
    }

    // ============================ EventSink (event_log) ============================

    @Test fun eventLog_parity_pgEqualsSqlite_batchAndPagination() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val db = Files.createTempFile("evparity", ".db")
        SqliteEventSink(db, FixedTimeSource(), Random(7)).use { sq ->
            val pgSink = PgEventSink(pg.postgresDatabase, FixedTimeSource(), Random(7))
            val ds = drafts(250)
            // identical batch appends → identical stamped events (same fixed clock + seq + rnd seed)
            assertEquals(sq.appendBatch(ds), pgSink.appendBatch(ds), "batch append stamps identically")

            // paginated browse parity: page-by-page, same events AND same next-cursor at limit 100
            var cs: Long? = null; var cp: Long? = null
            var pages = 0
            do {
                val ps = sq.query(EventFilter(projectId = "p"), Page(cs, 100))
                val pp = pgSink.query(EventFilter(projectId = "p"), Page(cp, 100))
                assertEquals(ps.events, pp.events, "page $pages events identical")
                assertEquals(ps.nextAfterSeq, pp.nextAfterSeq, "page $pages next-cursor identical")
                cs = ps.nextAfterSeq; cp = pp.nextAfterSeq; pages++
            } while (cs != null)
            assertEquals(3, pages, "250 rows / 100 → 3 pages (100,100,50) on BOTH")

            // filter-axis parity
            assertEquals(
                sq.query(EventFilter(agentId = "a1", projectId = "p"), Page(null, 1000)).events,
                pgSink.query(EventFilter(agentId = "a1", projectId = "p"), Page(null, 1000)).events,
                "agentId filter parity",
            )
            assertEquals(
                sq.query(EventFilter(type = EventType.TOOL_CALL, projectId = "p"), Page(null, 1000)).events,
                pgSink.query(EventFilter(type = EventType.TOOL_CALL, projectId = "p"), Page(null, 1000)).events,
                "type filter parity",
            )

            // deleteByProject: fail-closed on blank, scoped otherwise (parity)
            assertEquals(0, pgSink.deleteByProject(""), "blank projectId wipes nothing")
            pgSink.appendBatch(drafts(5, "other"))
            assertEquals(250, pgSink.deleteByProject("p"), "only project p removed")
            assertEquals(5, pgSink.query(EventFilter(projectId = "other"), Page(null, 1000)).events.size, "project 'other' survived")
        }
        Files.deleteIfExists(db)
    } }

    @Test fun eventLog_migrationTargetRoundtrip() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val sink = PgEventSink(pg.postgresDatabase, FixedTimeSource(), Random(1))
        val appended = sink.appendBatch(drafts(30))
        sink.importRows(sink.exportRows()) // idempotent replace with the same rows
        assertEquals(appended, sink.query(EventFilter(projectId = "p"), Page(null, 1000)).events, "export→import preserves rows exactly")
    } }

    @Test fun eventLog_routing_and_migrationWindow() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val dsns = dsnRegistryFor(pg.port)
        val dbFile = Files.createTempFile("evroute", ".db")
        val fileFallback = SqliteEventSink(dbFile, FixedTimeSource(), Random(3))

        // unbound → File
        val unbound = BindingRegistry(null)
        ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
            assertTrue(PgStoreRouting.eventSink("default", unbound, cp, { PgEventSink(it, FixedTimeSource(), Random(3)) }, { fileFallback }) is SqliteEventSink)
        }
        // bound + ACTIVE → Pg (memoized factory → one instance)
        val active = BindingRegistry(null).apply { bind("event_log", "default", "pg1"); setState("event_log", "default", BindingState.ACTIVE) }
        var pgSink: EventSink? = null
        val pgFactory: (DataSource) -> EventSink = { ds -> pgSink ?: PgEventSink(ds, FixedTimeSource(), Random(3)).also { pgSink = it } }
        ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.eventSink("default", active, cp, pgFactory) { fileFallback }
            assertTrue(s is PgEventSink)
            s.appendBatch(drafts(3))
            assertEquals(3, s.query(EventFilter(projectId = "p"), Page(null, 100)).events.size)
        }
        // MIGRATING → the write-freeze gate: reads from A, writes 409
        fileFallback.appendBatch(drafts(2)) // source A has content
        val migrating = BindingRegistry(null).apply { bind("event_log", "default", "pg1"); setState("event_log", "default", BindingState.MIGRATING) }
        ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.eventSink("default", migrating, cp, pgFactory) { fileFallback }
            assertTrue(s is MigrationGatedEventSink)
            assertEquals(2, s.query(EventFilter(projectId = "p"), Page(null, 100)).events.size) // reads from A
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.appendBatch(drafts(1)) }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.deleteByProject("p") }.code)
        }
        Files.deleteIfExists(dbFile)
    } }

    // ============================ AgentEventStore (agent_events) ============================

    private fun ev(i: Int): StreamJsonEvent = RateLimitEvent(sessionId = "s1", uuid = "u$i")

    @Test fun agentEvents_parity_pgEqualsSqlite_seqRetentionQuery() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val db = Files.createTempFile("aeparity", ".db")
        SqliteAgentEventStore(db, retainPerAgent = 3).use { sq ->
            val pgStore = PgAgentEventStore(pg.postgresDatabase, retainPerAgent = 3)
            // append 6 for a1 (retention keeps last 3) + 2 for a2, to BOTH
            repeat(6) { sq.append("a1", "pA", 1_000L + it, ev(it)); pgStore.append("a1", "pA", 1_000L + it, ev(it)) }
            sq.append("a2", "pB", 2_000L, ev(99)); pgStore.append("a2", "pB", 2_000L, ev(99))

            // seq is DB-generated but gapless+monotonic from 1 on both → identical query results
            assertEquals(sq.query("a1", null, 100), pgStore.query("a1", null, 100), "a1 retained-window parity (seq gapless)")
            assertEquals(3, pgStore.query("a1", null, 100).size, "retention kept last 3")
            assertEquals(sq.query("a1", 4L, 100), pgStore.query("a1", 4L, 100), "cursor (seq>4) parity")

            // deleteByProject scope parity
            assertEquals(sq.deleteByProject("pB"), pgStore.deleteByProject("pB"), "delete count parity")
            assertEquals(sq.query("a1", null, 100), pgStore.query("a1", null, 100), "a1 (pA) untouched by pB delete")
        }
        Files.deleteIfExists(db)
    } }

    @Test fun agentEvents_subscribe_replayThenLive_gapless() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val store = PgAgentEventStore(pg.postgresDatabase)
        repeat(3) { store.append("a1", "p", 1_000L, ev(it)) } // durable seq 1..3
        // subscribe replays the durable tail (onSubscription), oldest→newest, gapless
        val replayed = store.subscribe("a1", sinceSeq = 0L).take(3).toList()
        assertEquals(listOf(1L, 2L, 3L), replayed.map { it.seq }, "replay is gapless + ordered")
    } }

    @Test fun agentEvents_migrationTargetRoundtrip() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val store = PgAgentEventStore(pg.postgresDatabase, retainPerAgent = 0) // no trim → all rows kept
        repeat(5) { store.append("a1", "p", 1_000L + it, ev(it)) }
        val before = store.query("a1", null, 100)
        store.importRows(store.exportRows())
        assertEquals(before, store.query("a1", null, 100), "export→import preserves rows + seq exactly")
        // identity was realigned → a subsequent append does not collide with the imported max seq
        assertEquals(6L, store.append("a1", "p", 9_000L, ev(9)).seq, "seq continues above the imported max")
    } }

    @Test fun agentEvents_routing_and_migrationWindow() = EmbeddedPostgres.start().use { pg -> runBlocking<Unit> {
        val dsns = dsnRegistryFor(pg.port)
        val fileFallback = SqliteAgentEventStore(Files.createTempFile("aeroute", ".db"))

        val unbound = BindingRegistry(null)
        ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
            assertTrue(PgStoreRouting.agentEventStore("default", unbound, cp, { PgAgentEventStore(it) }, { fileFallback }) is SqliteAgentEventStore)
        }
        val active = BindingRegistry(null).apply { bind("agent_events", "default", "pg1"); setState("agent_events", "default", BindingState.ACTIVE) }
        var pgStore: AgentEventStore? = null
        val pgFactory: (DataSource) -> AgentEventStore = { ds -> pgStore ?: PgAgentEventStore(ds).also { pgStore = it } }
        ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.agentEventStore("default", active, cp, pgFactory) { fileFallback }
            assertTrue(s is PgAgentEventStore)
            s.append("a1", "p", 1_000L, ev(1))
            assertEquals(1, s.query("a1", null, 100).size)
        }
        // MIGRATING → gate: reads from A, append/delete 409
        fileFallback.append("a1", "p", 1_000L, ev(0)) // A has content
        val migrating = BindingRegistry(null).apply { bind("agent_events", "default", "pg1"); setState("agent_events", "default", BindingState.MIGRATING) }
        ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.agentEventStore("default", migrating, cp, pgFactory) { fileFallback }
            assertTrue(s is MigrationGatedAgentEventStore)
            assertEquals(1, s.query("a1", null, 100).size) // reads from A
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.append("a1", "p", 2_000L, ev(2)) }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.deleteByProject("p") }.code)
        }
    } }
}
