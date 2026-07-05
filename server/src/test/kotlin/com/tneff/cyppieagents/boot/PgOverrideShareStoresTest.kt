package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.ChannelShareRecord
import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.FileChannelShareStore
import com.tneff.cyppieagents.comm.PgChannelShareStore
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.db.DsnDescriptor
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.db.DsnTierOrigin
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.routing.ConflictException
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
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
 * CYP-220 Phase 6 S4 — the first non-secret user-DB-capable stores on Postgres: [PgAgentOverrideStore] and
 * [PgChannelShareStore]. Same pattern as S2: **parity Pg==File** (the strongest proof — the identical scripted
 * contract passes against both), **routing via [PgStoreRouting]** (unbound→File, bound+ACTIVE→Pg), **projectId
 * scope**, **fail-closed**, and the **S3 migration-window freeze** (MIGRATING→reads-from-A + writes 409).
 */
class PgOverrideShareStoresTest {

    private fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))

    private fun dsnRegistryFor(pgPort: Int): DsnRegistry = DsnRegistry(null, cipher()).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    // ============================ AgentOverrideStore ============================

    /** The identical scripted contract must pass against BOTH the File and the Pg impl → parity. */
    private fun agentOverrideContract(s: AgentOverrideStore) {
        // put: first write, then blank name PRESERVES the stored value (data-safety merge)
        assertEquals(AgentOverride(name = "Backend"), s.put("p", "backend", "Backend", null, null, null))
        assertEquals(AgentOverride(name = "Backend", color = "#f00"), s.put("p", "backend", "  ", "#f00", null, null))

        // setAvatar preset preserves the string fields; the polymorphic avatar round-trips (CommJson)
        assertEquals(
            AgentOverride(name = "Backend", color = "#f00", avatar = AgentAvatar.Preset("bottts", "backend")),
            s.setAvatar("p", "backend", AgentAvatar.Preset("bottts", "backend")),
        )
        // put PRESERVES the avatar (only setAvatar clears it)
        assertEquals(AgentAvatar.Preset("bottts", "backend"), s.put("p", "backend", null, null, "You are backend", null).avatar)
        // upload variant round-trips, then explicit clear
        assertEquals(AgentAvatar.Upload("a1b2c3"), s.setAvatar("p", "backend", AgentAvatar.Upload("a1b2c3")).avatar)
        assertNull(s.setAvatar("p", "backend", null).avatar)
        assertEquals(AgentOverride(name = "Backend", color = "#f00", persona = "You are backend"), s.overrideOf("p", "backend"))

        // allFor + removeAgent
        s.put("p", "frontend", "Frontend", null, null, null)
        assertEquals(setOf("backend", "frontend"), s.allFor("p").keys)
        assertTrue(s.removeAgent("p", "frontend"))
        assertFalse(s.removeAgent("p", "frontend")) // idempotent
        assertEquals(setOf("backend"), s.allFor("p").keys)

        // cascade: fail-closed on blank, scoped otherwise
        assertEquals(0, s.removeProject(""))
        assertEquals(1, s.removeProject("p"))
        assertTrue(s.allFor("p").isEmpty())
    }

    @Test fun agentOverride_parity_pgEqualsFile() = EmbeddedPostgres.start().use { pg ->
        agentOverrideContract(FileAgentOverrideStore(null))
        agentOverrideContract(PgAgentOverrideStore(pg.postgresDatabase))
    }

    @Test fun agentOverride_projectScope_noCrossProjectBleed() = EmbeddedPostgres.start().use { pg ->
        val s = PgAgentOverrideStore(pg.postgresDatabase)
        s.put("projA", "backend", "A-Backend", null, null, null)
        s.put("projB", "backend", "B-Backend", null, null, null)
        assertEquals("A-Backend", s.overrideOf("projA", "backend")?.name)
        assertEquals("B-Backend", s.overrideOf("projB", "backend")?.name)
        // deleting A's cascade leaves B untouched
        assertEquals(1, s.removeProject("projA"))
        assertNull(s.overrideOf("projA", "backend"))
        assertEquals("B-Backend", s.overrideOf("projB", "backend")?.name)
    }

    @Test fun agentOverride_migrationTargetRoundTrip() = EmbeddedPostgres.start().use { pg ->
        val src = PgAgentOverrideStore(pg.postgresDatabase)
        src.put("p", "backend", "Backend", "#f00", null, null)
        src.setAvatar("p", "backend", AgentAvatar.Upload("ref9"))
        src.put("p", "frontend", "Frontend", null, null, null)
        val rows = src.exportRows()
        src.importRows(rows) // idempotent replace with the same rows
        assertEquals(2, src.allFor("p").size)
        assertEquals(AgentAvatar.Upload("ref9"), src.overrideOf("p", "backend")?.avatar)
    }

    @Test fun agentOverride_routing_and_migrationWindow() = EmbeddedPostgres.start().use { pg ->
        val dsns = dsnRegistryFor(pg.port)

        // unbound → File
        val unbound = BindingRegistry(null)
        ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
            assertTrue(PgStoreRouting.agentOverrideStore("default", unbound, cp) { FileAgentOverrideStore(null) } is FileAgentOverrideStore)
        }
        // bound + ACTIVE → Pg (and it works against the real embedded PG)
        val active = BindingRegistry(null).apply { bind("agent_override", "default", "pg1"); setState("agent_override", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.agentOverrideStore("default", active, cp) { FileAgentOverrideStore(null) }
            assertTrue(s is PgAgentOverrideStore)
            s.put("default", "backend", "Backend", null, null, null)
            assertEquals("Backend", s.overrideOf("default", "backend")?.name)
        }
        // MIGRATING → the write-freeze gate: reads from A, writes 409, nothing lands in A
        val fileA = FileAgentOverrideStore(null).apply { put("default", "backend", "FromA", null, null, null) }
        val migrating = BindingRegistry(null).apply { bind("agent_override", "default", "pg1"); setState("agent_override", "default", BindingState.MIGRATING) }
        ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.agentOverrideStore("default", migrating, cp) { fileA }
            assertTrue(s is MigrationGatedAgentOverrideStore)
            assertEquals("FromA", s.overrideOf("default", "backend")?.name) // reads from source A
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.put("default", "backend", "X", null, null, null) }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.removeProject("default") }.code)
            assertEquals("FromA", fileA.overrideOf("default", "backend")?.name) // no ghost write in A
        }
    }

    /**
     * S4 must-fix (PO-Assistant, empirically proven): [PgAgentOverrideStore.put]/[setAvatar] must be an **atomic**
     * read-merge-write, like File's single-lock RMW. Repro: 40× barrier-synced `put(name)` ∥ `setAvatar(preset)`
     * on the SAME `(project, agent)`. A non-atomic two-tx RMW loses one field every time (measured 40/40); the
     * one-tx `SELECT … FOR UPDATE` fix serializes them → BOTH survive, lost == 0.
     */
    @Test fun agentOverride_concurrentPutAndSetAvatar_noLostUpdate() = EmbeddedPostgres.start().use { pg ->
        val store = PgAgentOverrideStore(pg.postgresDatabase)
        val pool = Executors.newFixedThreadPool(2)
        var lost = 0
        try {
            repeat(40) { i ->
                val agentId = "agent$i"
                val preset = AgentAvatar.Preset("bottts", agentId)
                val barrier = CyclicBarrier(2)
                val f1 = pool.submit { barrier.await(5, TimeUnit.SECONDS); store.put("p", agentId, "Name$i", null, null, null) }
                val f2 = pool.submit { barrier.await(5, TimeUnit.SECONDS); store.setAvatar("p", agentId, preset) }
                f1.get(15, TimeUnit.SECONDS); f2.get(15, TimeUnit.SECONDS)
                val fin = store.overrideOf("p", agentId)
                if (fin?.name != "Name$i" || fin.avatar != preset) lost++ // a lost update drops one field
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, lost, "lost updates across 40 barrier-synced put∥setAvatar races (non-atomic RMW ⇒ 40/40)")
    }

    // ============================ ChannelShareStore ============================

    private fun channelShareContract(s: ChannelShareStore) {
        val rec = s.share("chan1", "projA", setOf("projB", "projC"))
        assertEquals(ChannelShareRecord("chan1", "projA", setOf("projB", "projC"), setOf("projA"), 1000L), rec)
        assertEquals(rec, s.record("chan1"))

        // sharedInboundChannelIds: only grantees reach in; owner is not a grantee; blank active reaches nothing
        assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projB"))
        assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projC"))
        assertEquals(emptySet(), s.sharedInboundChannelIds("projA"))
        assertEquals(emptySet(), s.sharedInboundChannelIds(""))

        // per-channel granularity: a second channel is independent
        s.share("chan2", "projA", setOf("projB"))
        assertEquals(setOf("chan1", "chan2"), s.sharedInboundChannelIds("projB"))
        assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projC"))

        // empty-grantees (only the owner) → no-op hole → revoke semantics
        val emptied = s.share("chan2", "projA", setOf("projA"))
        assertEquals(emptySet(), emptied.sharedWith)
        assertNull(s.record("chan2"))
        assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projB"))

        // revoke closes the gate; idempotent
        assertTrue(s.revoke("chan1"))
        assertNull(s.record("chan1"))
        assertFalse(s.revoke("chan1"))
        assertEquals(emptySet(), s.sharedInboundChannelIds("projB"))
    }

    @Test fun channelShare_parity_pgEqualsFile() = EmbeddedPostgres.start().use { pg ->
        channelShareContract(FileChannelShareStore(null, clock = { 1000L }))
        channelShareContract(PgChannelShareStore(pg.postgresDatabase, clock = { 1000L }))
    }

    @Test fun channelShare_migrationTargetRoundTrip() = EmbeddedPostgres.start().use { pg ->
        val src = PgChannelShareStore(pg.postgresDatabase, clock = { 1000L })
        src.share("chan1", "projA", setOf("projB"))
        src.share("chan2", "projA", setOf("projC", "projD"))
        val rows = src.exportRows()
        src.importRows(rows)
        assertEquals(ChannelShareRecord("chan2", "projA", setOf("projC", "projD"), setOf("projA"), 1000L), src.record("chan2"))
        assertEquals(setOf("chan1"), src.sharedInboundChannelIds("projB"))
    }

    @Test fun channelShare_routing_and_migrationWindow() = EmbeddedPostgres.start().use { pg ->
        val dsns = dsnRegistryFor(pg.port)

        val unbound = BindingRegistry(null)
        ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
            assertTrue(PgStoreRouting.channelShareStore("default", unbound, cp) { FileChannelShareStore(null) } is FileChannelShareStore)
        }
        val active = BindingRegistry(null).apply { bind("channel_share", "default", "pg1"); setState("channel_share", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, active, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.channelShareStore("default", active, cp) { FileChannelShareStore(null) }
            assertTrue(s is PgChannelShareStore)
            s.share("chan1", "projA", setOf("projB"))
            assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projB"))
        }
        // MIGRATING → gate: reads from A, share/revoke rejected 409, no ghost write in A
        val fileA = FileChannelShareStore(null, clock = { 1000L }).apply { share("chan1", "projA", setOf("projB")) }
        val migrating = BindingRegistry(null).apply { bind("channel_share", "default", "pg1"); setState("channel_share", "default", BindingState.MIGRATING) }
        ConnectionProvider(dsns, migrating, maxPoolSize = 2).use { cp ->
            val s = PgStoreRouting.channelShareStore("default", migrating, cp) { fileA }
            assertTrue(s is MigrationGatedChannelShareStore)
            assertEquals(setOf("chan1"), s.sharedInboundChannelIds("projB")) // reads from source A
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.share("chan9", "projA", setOf("projZ")) }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { s.revoke("chan1") }.code)
            assertNull(fileA.record("chan9")) // no ghost write in A
            assertEquals(setOf("chan1"), fileA.sharedInboundChannelIds("projB")) // revoke did not touch A
        }
    }
}
