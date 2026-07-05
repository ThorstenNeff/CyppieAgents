package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.FileChannelShareStore
import com.tneff.cyppieagents.comm.JsonFileDeliveryLog
import com.tneff.cyppieagents.comm.MigrationGatedDeliveryLog
import com.tneff.cyppieagents.comm.PgDeliveryLog
import com.tneff.cyppieagents.connector.JsonFileSessionStore
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-220 Live-Bind-Wiring W1 — the combined enforcement test for the [StoreRouter] seam. Proves the guards fire
 * **per-op**: inert with no bindings (no behavior change), residency/fail-safe on secret stores, and — the money
 * tooth — a **post-boot MIGRATING flip is caught by the next per-op resolution** (the boot-cached-handle bypass,
 * the logged S3-finding, is structurally impossible because the router re-resolves every access).
 */
class StoreRouterEnforcementTest {

    private fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
    private val secrets = Secrets(emptyMap(), operatorToken = "op", apiKey = null)
    private val repo = RepoConfig("https://ex/r.git", "main")

    private fun dsnRegistryFor(pgPort: Int, c: SecretCipher): DsnRegistry = DsnRegistry(null, c).apply {
        put(DsnDescriptor("pg1", "local", "localhost", pgPort, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
    }

    @Test fun inert_noBindings_everyAccessorReturnsFile() {
        // The boot default: an empty binding registry → no store is bound → every accessor returns the File
        // fallback. NO behavior change until an operator binds a store (a later admin-API slice).
        val c = cipher()
        val empty = BindingRegistry(null)
        ConnectionProvider(DsnRegistry(null, c), empty, maxPoolSize = 1).use { cp ->
            val router = StoreRouter(empty, cp, c, secrets, repo)
            assertTrue(router.remoteTokenStore("default") { FileRemoteTokenStore(null) } is FileRemoteTokenStore)
            assertTrue(router.projectConfigStore("default") { FileProjectConfigStore(null, repo, secrets) } is FileProjectConfigStore)
            assertTrue(router.agentOverrideStore("default") { FileAgentOverrideStore(null) } is FileAgentOverrideStore)
            assertTrue(router.channelShareStore("default") { FileChannelShareStore(null) } is FileChannelShareStore)
            assertTrue(router.sessionStore("default") { JsonFileSessionStore(Files.createTempFile("w1s", ".json").toFile()) } is JsonFileSessionStore)
            assertTrue(router.deliveryLog("default") { JsonFileDeliveryLog(Files.createTempFile("w1d", ".log").toFile()) } is JsonFileDeliveryLog)
        }
    }

    @Test fun failSafeCipher_nullCipher_secretStoreStaysFile_evenBoundActive() = EmbeddedPostgres.start().use { pg ->
        // A secret store bound + ACTIVE, but NO master key (cipher = null) → it must stay File: a secret must
        // never be routed to a user DB without encryption. Fail-safe, independent of the binding.
        val c = cipher()
        val dsns = dsnRegistryFor(pg.port, c)
        val bindings = BindingRegistry(null).apply { bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            val router = StoreRouter(bindings, cp, cipher = null, secrets, repo)
            assertTrue(router.remoteTokenStore("default") { FileRemoteTokenStore(null) } is FileRemoteTokenStore, "null cipher → secret store never routes to Pg")
        }
    }

    @Test fun perOp_migrationFlip_isCaughtByNextResolution_secretStore() = EmbeddedPostgres.start().use { pg ->
        val c = cipher()
        val dsns = dsnRegistryFor(pg.port, c)
        val fileA = FileRemoteTokenStore(null).apply { put("x", "tok-source-A-1234") }
        val bindings = BindingRegistry(null).apply { bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            val router = StoreRouter(bindings, cp, c, secrets, repo)

            // ACTIVE: a per-op resolution is the live Pg store; writes land on Pg.
            val active = router.remoteTokenStore("default") { fileA }
            assertTrue(active is PgRemoteTokenStore)
            active.put("y", "tok-on-pg-5678"); assertEquals("tok-on-pg-5678", active.all()["y"])

            // POST-BOOT MIGRATING flip. The money tooth: the NEXT per-op resolution is the write-freeze gate —
            // a boot-cached handle would keep writing to Pg (the S3-finding bypass); re-resolving catches it.
            bindings.setState("remote_token", "default", BindingState.MIGRATING)
            val afterFlip = router.remoteTokenStore("default") { fileA }
            assertTrue(afterFlip is MigrationGatedRemoteTokenStore, "per-op re-resolution sees the flip (no cached-handle bypass)")
            assertEquals(mapOf("x" to "tok-source-A-1234"), afterFlip.all(), "reads come from source A during the window")
            assertEquals("store_migrating", assertFailsWith<ConflictException> { afterFlip.put("z", "tok-lost") }.code)
            assertEquals(mapOf("x" to "tok-source-A-1234"), fileA.all(), "no ghost write in A")

            // Rebind ACTIVE → the next resolution is Pg again (per-op).
            bindings.setState("remote_token", "default", BindingState.ACTIVE)
            assertTrue(router.remoteTokenStore("default") { fileA } is PgRemoteTokenStore)
        }
    }

    @Test fun perOp_migrationFlip_isCaughtByNextResolution_nonSecretStore() = EmbeddedPostgres.start().use { pg ->
        val c = cipher()
        val dsns = dsnRegistryFor(pg.port, c)
        val fileA = JsonFileDeliveryLog(Files.createTempFile("w1del", ".log").toFile()).apply { markDelivered("default", "a", "m-A") }
        val bindings = BindingRegistry(null).apply { bind("delivery", "default", "pg1"); setState("delivery", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            val router = StoreRouter(bindings, cp, c, secrets, repo)
            assertTrue(router.deliveryLog("default") { fileA } is PgDeliveryLog)

            bindings.setState("delivery", "default", BindingState.MIGRATING)
            val gated = router.deliveryLog("default") { fileA }
            assertTrue(gated is MigrationGatedDeliveryLog)
            assertTrue(gated.isDelivered("default", "a", "m-A"), "reads come from source A")
            assertEquals("store_migrating", assertFailsWith<ConflictException> { gated.markDelivered("default", "a", "m-new") }.code)
        }
    }
}
