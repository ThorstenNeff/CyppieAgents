package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.DeliveryLog
import com.tneff.cyppieagents.comm.JsonFileDeliveryLog
import com.tneff.cyppieagents.comm.MigrationGatedDeliveryLog
import com.tneff.cyppieagents.comm.PgDeliveryLog
import com.tneff.cyppieagents.connector.JsonFileSessionStore
import com.tneff.cyppieagents.connector.MigrationGatedSessionStore
import com.tneff.cyppieagents.connector.PgSessionStore
import com.tneff.cyppieagents.connector.SessionEntry
import com.tneff.cyppieagents.connector.SessionStore
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.db.DsnDescriptor
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.db.DsnTierOrigin
import com.tneff.cyppieagents.routing.ConflictException
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 6 S6 — the last Pg stores: [PgSessionStore] + [PgDeliveryLog]. Parity Pg==File; the createdAt-
 * preserve upsert (the only RMW) proven atomic by a pre-seeded concurrency tooth; delivery proven append-only/
 * idempotent; plus MigrationTarget round-trip, routing, and the S3 migration-window freeze.
 */
class PgSessionDeliveryStoresTest {

    private fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
    private fun dsnRegistryFor(pgPort: Int): DsnRegistry = DsnRegistry(null, cipher()).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    // ============================ SessionStore ============================

    private fun sessionContract(s: SessionStore) {
        assertNull(s.find("p", "a"))
        s.upsert("p", "a", "s1", 100)
        assertEquals(SessionEntry("p", "a", "s1", 100, 100), s.find("p", "a"))
        // refresh the SAME session → createdAt preserved, lastActivity advances
        s.upsert("p", "a", "s1", 200)
        assertEquals(SessionEntry("p", "a", "s1", 100, 200), s.find("p", "a"))
        // a NEW session id → createdAt resets to now
        s.upsert("p", "a", "s2", 300)
        assertEquals(SessionEntry("p", "a", "s2", 300, 300), s.find("p", "a"))
        // scope: another agent is independent
        s.upsert("p", "b", "sb", 50)
        assertEquals("s2", s.find("p", "a")?.sessionId)
        // clear is scoped
        s.clear("p", "a")
        assertNull(s.find("p", "a"))
        assertEquals("sb", s.find("p", "b")?.sessionId)
    }

    @Test fun session_parity_pgEqualsFile() {
        EmbeddedPostgres.start().use { pg ->
            val f = Files.createTempFile("sessparity", ".json").toFile()
            sessionContract(JsonFileSessionStore(f))
            sessionContract(PgSessionStore(pg.postgresDatabase))
            f.delete()
        }
    }

    @Test fun session_upsertCreatedAtPreserve_atomicUnderConcurrency() {
        EmbeddedPostgres.start().use { pg ->
            val store = PgSessionStore(pg.postgresDatabase)
            val pool = Executors.newFixedThreadPool(2)
            var lost = 0
            try {
                repeat(40) { i ->
                    val agentId = "agent$i"
                    store.upsert("p", agentId, "s1", 100) // pre-seed: existing row, createdAt=100
                    val barrier = CyclicBarrier(2)
                    // two concurrent refreshes of the SAME session id → BOTH must preserve createdAt=100
                    val f1 = pool.submit { barrier.await(5, TimeUnit.SECONDS); store.upsert("p", agentId, "s1", 200) }
                    val f2 = pool.submit { barrier.await(5, TimeUnit.SECONDS); store.upsert("p", agentId, "s1", 300) }
                    f1.get(15, TimeUnit.SECONDS); f2.get(15, TimeUnit.SECONDS)
                    if (store.find("p", agentId)?.createdAt != 100L) lost++ // the CASE lost the preserved createdAt
                }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(0, lost, "createdAt preserved across 40 concurrent same-session refreshes (atomic ON CONFLICT CASE)")
        }
    }

    @Test fun session_migrationTargetRoundtrip() {
        EmbeddedPostgres.start().use { pg ->
            val store = PgSessionStore(pg.postgresDatabase)
            store.upsert("pA", "a1", "s1", 100); store.upsert("pB", "a2", "s2", 200)
            store.importRows(store.exportRows())
            assertEquals(SessionEntry("pA", "a1", "s1", 100, 100), store.find("pA", "a1"))
            assertEquals(SessionEntry("pB", "a2", "s2", 200, 200), store.find("pB", "a2"))
        }
    }

    @Test fun session_routing_and_migrationWindow() {
        EmbeddedPostgres.start().use { pg ->
            val dsns = dsnRegistryFor(pg.port)
            val fileA = JsonFileSessionStore(Files.createTempFile("sessroute", ".json").toFile())

            val unbound = BindingRegistry(null)
            ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
                assertTrue(PgStoreRouting.sessionStore("default", unbound, cp) { fileA } is JsonFileSessionStore)
            }
            val active = BindingRegistry(null).apply { bind("session", "default", "pg1"); setState("session", "default", BindingState.ACTIVE) }
            ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
                val s = PgStoreRouting.sessionStore("default", active, cp) { fileA }
                assertTrue(s is PgSessionStore)
                s.upsert("default", "a1", "s1", 1); assertEquals("s1", s.find("default", "a1")?.sessionId)
            }
            // MIGRATING → gate: find reads A, upsert/clear 409
            fileA.upsert("default", "a1", "fromA", 9)
            val migrating = BindingRegistry(null).apply { bind("session", "default", "pg1"); setState("session", "default", BindingState.MIGRATING) }
            ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
                val s = PgStoreRouting.sessionStore("default", migrating, cp) { fileA }
                assertTrue(s is MigrationGatedSessionStore)
                assertEquals("fromA", s.find("default", "a1")?.sessionId) // reads from A
                assertEquals("store_migrating", assertFailsWith<ConflictException> { s.upsert("default", "a1", "x", 1) }.code)
                assertEquals("store_migrating", assertFailsWith<ConflictException> { s.clear("default", "a1") }.code)
                assertEquals("fromA", fileA.find("default", "a1")?.sessionId) // no ghost write in A
            }
        }
    }

    // ============================ DeliveryLog ============================

    private fun deliveryContract(d: DeliveryLog) {
        assertFalse(d.isDelivered("p", "a", "m1"))
        d.markDelivered("p", "a", "m1")
        assertTrue(d.isDelivered("p", "a", "m1"))
        d.markDelivered("p", "a", "m1") // idempotent set-add
        assertTrue(d.isDelivered("p", "a", "m1"))
        // per-key scope: a different agent / message / project is independent
        assertFalse(d.isDelivered("p", "b", "m1"))
        assertFalse(d.isDelivered("p", "a", "m2"))
        assertFalse(d.isDelivered("q", "a", "m1"))
        d.markDelivered("p", "b", "m1")
        assertTrue(d.isDelivered("p", "b", "m1"))
        assertTrue(d.isDelivered("p", "a", "m1")) // still there
    }

    @Test fun delivery_parity_pgEqualsFile() {
        EmbeddedPostgres.start().use { pg ->
            val f = Files.createTempFile("delparity", ".log").toFile()
            deliveryContract(JsonFileDeliveryLog(f))
            deliveryContract(PgDeliveryLog(pg.postgresDatabase))
            f.delete()
        }
    }

    @Test fun delivery_migrationTargetRoundtrip() {
        EmbeddedPostgres.start().use { pg ->
            val log = PgDeliveryLog(pg.postgresDatabase)
            log.markDelivered("p", "a", "m1"); log.markDelivered("p", "a", "m2"); log.markDelivered("p", "b", "m1")
            log.importRows(log.exportRows())
            assertTrue(log.isDelivered("p", "a", "m1")); assertTrue(log.isDelivered("p", "a", "m2")); assertTrue(log.isDelivered("p", "b", "m1"))
            assertFalse(log.isDelivered("p", "a", "m3"))
        }
    }

    @Test fun delivery_routing_and_migrationWindow() {
        EmbeddedPostgres.start().use { pg ->
            val dsns = dsnRegistryFor(pg.port)
            val fileA = JsonFileDeliveryLog(Files.createTempFile("delroute", ".log").toFile())

            val unbound = BindingRegistry(null)
            ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
                assertTrue(PgStoreRouting.deliveryLog("default", unbound, cp) { fileA } is JsonFileDeliveryLog)
            }
            val active = BindingRegistry(null).apply { bind("delivery", "default", "pg1"); setState("delivery", "default", BindingState.ACTIVE) }
            ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
                val d = PgStoreRouting.deliveryLog("default", active, cp) { fileA }
                assertTrue(d is PgDeliveryLog)
                d.markDelivered("default", "a1", "m1"); assertTrue(d.isDelivered("default", "a1", "m1"))
            }
            // MIGRATING → gate: isDelivered reads A, markDelivered 409
            fileA.markDelivered("default", "a1", "fromA")
            val migrating = BindingRegistry(null).apply { bind("delivery", "default", "pg1"); setState("delivery", "default", BindingState.MIGRATING) }
            ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
                val d = PgStoreRouting.deliveryLog("default", migrating, cp) { fileA }
                assertTrue(d is MigrationGatedDeliveryLog)
                assertTrue(d.isDelivered("default", "a1", "fromA")) // reads from A
                assertEquals("store_migrating", assertFailsWith<ConflictException> { d.markDelivered("default", "a1", "m9") }.code)
                assertFalse(fileA.isDelivered("default", "a1", "m9")) // no ghost write in A
            }
        }
    }
}
