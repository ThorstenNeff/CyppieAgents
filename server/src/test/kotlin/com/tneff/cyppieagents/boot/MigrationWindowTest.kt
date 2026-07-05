package com.tneff.cyppieagents.boot

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
import io.ktor.http.HttpStatusCode
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 6 S3 — Finding B: **read-only-window enforcement**. During a store's migration window
 * (BindingState.MIGRATING/READ_ONLY) the store-access accessor ([PgStoreRouting]) must keep the store **readable
 * from source A** but **reject every write** (`store_migrating` 409), so no write is lost in A after the rebind
 * onto B, nor duplicated into both. The load-bearing tooth is the **end-to-end no-lost/no-duplicate invariant**
 * across a real File→Pg window on embedded Postgres.
 */
class MigrationWindowTest {

    private fun cipher(masterKey: String): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(masterKey))

    private fun dsnRegistryFor(pgPort: Int, c: SecretCipher): DsnRegistry = DsnRegistry(null, c).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    // ---- inMigrationWindow predicate (no PG needed) ----

    @Test fun inMigrationWindow_trueOnlyForCapableStoreInWindow_falseForHomeOrActive() {
        val bindings = BindingRegistry(null)
        // a user-DB-capable store, bound + MIGRATING → in window
        bindings.bind("remote_token", "default", "pg1"); bindings.setState("remote_token", "default", BindingState.MIGRATING)
        assertTrue(PgStoreRouting.inMigrationWindow("remote_token", "default", bindings), "capable + MIGRATING → window")

        // same store flipped ACTIVE → NOT a window (writes allowed on B)
        bindings.setState("remote_token", "default", BindingState.ACTIVE)
        assertFalse(PgStoreRouting.inMigrationWindow("remote_token", "default", bindings), "ACTIVE → not a window")

        // READ_ONLY also freezes writes
        bindings.setState("remote_token", "default", BindingState.READ_ONLY)
        assertTrue(PgStoreRouting.inMigrationWindow("remote_token", "default", bindings), "READ_ONLY → window")

        // a MUST_STAY_HOME store cannot be windowed onto a user DB even if metadata says MIGRATING (residency)
        bindings.bind("dsn_registry", "default", "pg1"); bindings.setState("dsn_registry", "default", BindingState.MIGRATING)
        assertFalse(PgStoreRouting.inMigrationWindow("dsn_registry", "default", bindings), "MUST_STAY_HOME never windowed")

        // unbound capable store → no window (File, always writable)
        assertFalse(PgStoreRouting.inMigrationWindow("project_config", "default", bindings), "unbound → not a window")
    }

    // ---- RemoteTokenStore: write frozen, reads from A ----

    @Test fun remoteToken_writeDuringWindow_rejected409_notAppliedToA_readsStayFromA() = EmbeddedPostgres.start().use { pg ->
        val c = cipher(SecretCipherFactory.newBoxKeyset())
        val dsns = dsnRegistryFor(pg.port, c)
        val fileA = FileRemoteTokenStore(null).apply { put("x", "tok-xxxxxxxxxxxx1") } // source A seeded

        val bindings = BindingRegistry(null).apply {
            bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.MIGRATING)
        }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            val store = PgStoreRouting.remoteTokenStore("default", bindings, cp, c) { fileA }
            assertTrue(store is MigrationGatedRemoteTokenStore, "MIGRATING → the write-freeze gate, not a live store")

            // reads during the window come from source A (store stays readable/consistent)
            assertEquals(mapOf("x" to "tok-xxxxxxxxxxxx1"), store.all())

            // a write is typed-rejected with 409 store_migrating …
            val ex = assertFailsWith<ConflictException> { store.put("y", "tok-yyyyyyyyyyyy2") }
            assertEquals("store_migrating", ex.code)
            assertEquals(HttpStatusCode.Conflict, ex.status)
            assertFailsWith<ConflictException> { store.remove("x") } // revoke frozen too

            // … and it did NOT silently land in A (which would be lost after the rebind onto B)
            assertEquals(mapOf("x" to "tok-xxxxxxxxxxxx1"), fileA.all(), "no ghost write in source A during the window")
        }
    }

    // ---- The money tooth: no lost / no duplicate write across the whole window ----

    @Test fun remoteToken_noLostNoDuplicate_acrossMigrationWindow() = EmbeddedPostgres.start().use { pg ->
        val c = cipher(SecretCipherFactory.newBoxKeyset())
        val dsns = dsnRegistryFor(pg.port, c)

        // A = File, holds {x}. Copy A → B (Pg) — the migrator's COPY step (B now equals A at copy time).
        val fileA = FileRemoteTokenStore(null).apply { put("x", "tok-xxxxxxxxxxxx1") }
        val pgB = PgRemoteTokenStore(pg.postgresDatabase, c, "default")
        pgB.put("x", "tok-xxxxxxxxxxxx1") // B holds A's snapshot at copy time

        val bindings = BindingRegistry(null).apply {
            bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.MIGRATING)
        }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            // WINDOW: a client attempts a write. It is rejected (the 409 is the "retry after switch" signal),
            // so it is applied NOWHERE — not to A (would be lost), not to B (would duplicate on retry).
            val during = PgStoreRouting.remoteTokenStore("default", bindings, cp, c) { fileA }
            assertFailsWith<ConflictException> { during.put("y", "tok-yyyyyyyyyyyy2") }

            // ATOMIC REBIND: MIGRATING → ACTIVE. Reads/writes now flow to B (Pg).
            bindings.setState("remote_token", "default", BindingState.ACTIVE)
            val after = PgStoreRouting.remoteTokenStore("default", bindings, cp, c) { fileA }
            assertTrue(after is PgRemoteTokenStore, "ACTIVE → the live Postgres store (B)")

            // B holds EXACTLY the copied set: x survived (not lost), y is absent (the rejected write did not
            // leak into A-then-vanish, and did not double-apply into B).
            assertEquals(mapOf("x" to "tok-xxxxxxxxxxxx1"), after.all(), "no lost row, no phantom row after rebind")

            // The client retries the rejected write now that the store is ACTIVE → lands in B exactly once.
            after.put("y", "tok-yyyyyyyyyyyy2")
            assertEquals(mapOf("x" to "tok-xxxxxxxxxxxx1", "y" to "tok-yyyyyyyyyyyy2"), after.all(), "retry applies exactly once")
        }
    }

    // ---- ProjectConfigStore: config writes frozen, resolution reads from A ----

    @Test fun projectConfig_writeDuringWindow_rejected409_resolutionStaysFromA() = EmbeddedPostgres.start().use { pg ->
        val c = cipher(SecretCipherFactory.newBoxKeyset())
        val dsns = dsnRegistryFor(pg.port, c)
        val secrets = Secrets(emptyMap(), null, null, mapOf("default" to "team-fallback-key-123456"))
        val fileA = FileProjectConfigStore(null, RepoConfig("https://a.example/r.git", "main"), secrets).apply {
            setApiKey("default", "sk-source-A-key-abcdef123456")
        }

        val bindings = BindingRegistry(null).apply {
            bind("project_config", "default", "pg1"); setState("project_config", "default", BindingState.MIGRATING)
        }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            val store = PgStoreRouting.projectConfigStore("default", bindings, cp, c, RepoConfig("", "main"), secrets) { fileA }
            assertTrue(store is MigrationGatedProjectConfigStore, "MIGRATING → the write-freeze gate")

            // resolution reads through to A (spawn/boot stays correct during the window)
            assertEquals("sk-source-A-key-abcdef123456", store.resolvedApiKey("default"))
            assertEquals(RepoConfig("https://a.example/r.git", "main"), store.resolvedRepo("default"))

            // every mutation is frozen with 409 store_migrating; nothing leaks into A
            assertEquals("store_migrating", assertFailsWith<ConflictException> { store.setApiKey("default", "sk-new-key-99998888aaaa") }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { store.setRepo("default", "git@github.com:o/p.git", "dev") }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { store.remove("default") }.code)
            assertEquals("sk-source-A-key-abcdef123456", fileA.resolvedApiKey("default"), "no ghost config write in source A")
        }
    }
}
