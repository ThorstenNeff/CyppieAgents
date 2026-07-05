package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.boot.PgStoreRouting
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.db.DsnDescriptor
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.db.DsnTierOrigin
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 6 S6 — the durable ReportStore. Parity InMemory==File==Pg (append-only immutable snapshots,
 * monotonic rep-N, newest-first list, get); File durability across a restart; the rep-N import-realign
 * (S5 discipline); and generate() concurrency (distinct rep-N under the in-process counter mutex, no PK
 * collision) — the PO's gate list.
 */
class PgReportStoreTest {

    /** A shared, unchanging generator: with a fixed sink the build() output is deterministic across impls/calls. */
    private fun generator(): ReportGenerator {
        val sink = InMemoryEventSink(SystemTimeSource())
        val state = HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")), HubState.OPERATOR_ID, "default")
        return ReportGenerator(sink, state, Hub(state, InMemoryMessageStore()))
    }

    /** Fresh incrementing clock per impl → identical generatedAt sequence, so snapshots compare equal across impls. */
    private fun clock(): () -> Long { var t = 1000L; return { ++t } }

    private fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
    private fun dsnRegistryFor(pgPort: Int): DsnRegistry = DsnRegistry(null, cipher()).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    private fun req(type: ReportType) = GenerateReportRequest(type)

    /** The identical scripted contract against every impl → parity. */
    private fun contract(store: ReportStore) = runBlocking {
        val s0 = store.generate(req(ReportType.STATUS)); assertEquals("rep-0", s0.id)
        val s1 = store.generate(req(ReportType.DEFECTS)); assertEquals("rep-1", s1.id)
        assertEquals(listOf("rep-1", "rep-0"), store.list().map { it.id }) // newest-first
        assertEquals(s0, store.get("rep-0"))
        assertEquals(s1, store.get("rep-1"))
        assertFailsWith<NotFoundException> { store.get("rep-99") }
        Unit
    }

    @Test fun parity_inMemory_file_pg() {
        EmbeddedPostgres.start().use { pg ->
            val gen = generator()
            contract(InMemoryReportStore(gen, "default", clock()))
            contract(FileReportStore(gen, "default", Files.createTempFile("repparity", ".json").toFile(), clock()))
            contract(PgReportStore(gen, "default", pg.postgresDatabase, clock()))
        }
    }

    @Test fun file_durable_acrossRestart_resumesCounter() {
        runBlocking {
            val gen = generator()
            val f = Files.createTempFile("repdurable", ".json").toFile()
            FileReportStore(gen, "default", f, clock()).let { s -> s.generate(req(ReportType.STATUS)); s.generate(req(ReportType.USAGE)) }
            // "restart": a fresh File store on the SAME file → history survives, counter resumes above the max id
            val reopened = FileReportStore(gen, "default", f, clock())
            assertEquals(listOf("rep-1", "rep-0"), reopened.list().map { it.id }, "history survived restart")
            assertEquals("rep-2", reopened.generate(req(ReportType.DEFECTS)).id, "counter resumed above the persisted max")
            f.delete()
        }
    }

    @Test fun pg_importRealignsCounter_freshStoreGeneratesAboveImportedMax() {
        EmbeddedPostgres.start().use { src ->
            EmbeddedPostgres.start().use { dst ->
                runBlocking {
                    val gen = generator()
                    val source = PgReportStore(gen, "default", src.postgresDatabase, clock())
                    repeat(3) { source.generate(req(ReportType.STATUS)) } // rep-0, rep-1, rep-2
                    // fresh target (counter 0) imports ids that are AHEAD of it → the realign must lift the counter,
                    // else the next generate reuses rep-0 (PK collision).
                    val target = PgReportStore(gen, "default", dst.postgresDatabase, clock())
                    target.importRows(source.exportRows())
                    assertEquals(listOf("rep-2", "rep-1", "rep-0"), target.list().map { it.id }, "imported snapshots preserved")
                    assertEquals("rep-3", target.generate(req(ReportType.USAGE)).id, "counter realigned above the imported max")
                }
            }
        }
    }

    @Test fun pg_generateConcurrent_distinctIds_noCollision() {
        EmbeddedPostgres.start().use { pg ->
            runBlocking {
                val store = PgReportStore(generator(), "default", pg.postgresDatabase, clock())
                val ids = ConcurrentHashMap.newKeySet<String>()
                repeat(20) {
                    // two concurrent generate() on ONE instance → distinct rep-N (in-process counter under mutex)
                    val pair = coroutineScope {
                        val a = async(Dispatchers.IO) { store.generate(req(ReportType.STATUS)).id }
                        val b = async(Dispatchers.IO) { store.generate(req(ReportType.STATUS)).id }
                        a.await() to b.await()
                    }
                    assertNotEquals(pair.first, pair.second, "concurrent generate stamped the SAME id")
                    ids.add(pair.first); ids.add(pair.second)
                }
                assertEquals(40, ids.size, "all 40 ids distinct — no counter race, no PK collision")
                assertEquals(40, store.list().size, "all 40 snapshots persisted")
            }
        }
    }

    @Test fun pg_routing_and_migrationWindow() {
        EmbeddedPostgres.start().use { pg ->
            runBlocking {
                val gen = generator()
                val dsns = dsnRegistryFor(pg.port)
                val fileA = FileReportStore(gen, "default", Files.createTempFile("reproute", ".json").toFile(), clock())

                val unbound = BindingRegistry(null)
                ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
                    assertTrue(PgStoreRouting.reportStore("default", unbound, cp, { PgReportStore(gen, "default", it, clock()) }, { fileA }) is FileReportStore)
                }
                val active = BindingRegistry(null).apply { bind("report", "default", "pg1"); setState("report", "default", BindingState.ACTIVE) }
                var pgStore: ReportStore? = null
                val pgFactory: (DataSource) -> ReportStore = { ds -> pgStore ?: PgReportStore(gen, "default", ds, clock()).also { pgStore = it } }
                ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
                    val s = PgStoreRouting.reportStore("default", active, cp, pgFactory) { fileA }
                    assertTrue(s is PgReportStore)
                    s.generate(req(ReportType.STATUS)); assertEquals(1, s.list().size)
                }
                // MIGRATING → gate: list/get read A, generate 409
                fileA.generate(req(ReportType.STATUS)) // A has a report (rep-0)
                val migrating = BindingRegistry(null).apply { bind("report", "default", "pg1"); setState("report", "default", BindingState.MIGRATING) }
                ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
                    val s = PgStoreRouting.reportStore("default", migrating, cp, pgFactory) { fileA }
                    assertTrue(s is MigrationGatedReportStore)
                    assertEquals(1, s.list().size) // reads from A
                    assertEquals("store_migrating", assertFailsWith<ConflictException> { s.generate(req(ReportType.STATUS)) }.code)
                }
            }
        }
    }
}
