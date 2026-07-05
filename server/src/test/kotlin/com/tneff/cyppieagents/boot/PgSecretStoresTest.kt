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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 6 S2 — the secret stores on real embedded Postgres. Core tooth **plaintext-NEVER-on-PG**: a real
 * API key / token, once written, appears in the row ONLY as ciphertext bytes (the plaintext's bytes are not a
 * subsequence of any stored cell). Plus restart-decrypt (fresh instance + same master key) and File-parity.
 */
class PgSecretStoresTest {

    private fun cipher(masterKey: String): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(masterKey))

    /** True if [sub]'s bytes occur contiguously in the receiver — used to prove a plaintext is NOT stored. */
    private fun ByteArray.containsSub(sub: ByteArray): Boolean {
        if (sub.isEmpty() || sub.size > size) return false
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return true
        }
        return false
    }

    /** Concatenate every column of every row as raw bytes — the whole at-rest footprint of the table. */
    private fun DataSource.dumpTableBytes(table: String, cols: Int): ByteArray {
        connection.use { c ->
            c.prepareStatement("SELECT * FROM $table").use { st ->
                st.executeQuery().use { rs ->
                    val out = ArrayList<Byte>()
                    while (rs.next()) for (i in 1..cols) rs.getBytes(i)?.let { out.addAll(it.toList()) }
                    return out.toByteArray()
                }
            }
        }
    }

    // ---- RemoteTokenStore ----

    @Test fun remoteToken_plaintextNeverOnPg_roundtrips_restartDecrypts() = EmbeddedPostgres.start().use { pg ->
        val mk = SecretCipherFactory.newBoxKeyset()
        val secret = "tok-SUPER-SECRET-abc123xyz789"
        PgRemoteTokenStore(pg.postgresDatabase, cipher(mk), "default").put("frontend", secret)

        // plaintext NEVER on PG: the whole table's stored bytes do not contain the plaintext token.
        assertFalse(pg.postgresDatabase.dumpTableBytes("remote_token", 3).containsSub(secret.encodeToByteArray()), "plaintext token found in a PG row!")

        // restart-decrypt: a fresh instance + a fresh cipher from the SAME master key decrypts prior rows.
        val reopened = PgRemoteTokenStore(pg.postgresDatabase, cipher(mk), "default", migrate = false)
        assertEquals(secret, reopened.all()["frontend"])
        reopened.remove("frontend")
        assertTrue(reopened.all().isEmpty())
    }

    @Test fun remoteToken_parityWithFile() = EmbeddedPostgres.start().use { pg ->
        val mk = SecretCipherFactory.newBoxKeyset()
        val file = FileRemoteTokenStore(null)
        val pgs = PgRemoteTokenStore(pg.postgresDatabase, cipher(mk), "default")
        listOf(file, pgs).forEach { s ->
            s.put("a", "tok-aaaaaaaaaaaa1"); s.put("b", "tok-bbbbbbbbbbbb2")
            assertEquals(mapOf("a" to "tok-aaaaaaaaaaaa1", "b" to "tok-bbbbbbbbbbbb2"), s.all())
            s.remove("a")
            assertEquals(mapOf("b" to "tok-bbbbbbbbbbbb2"), s.all())
        }
    }

    // ---- ProjectConfigStore ----

    @Test fun projectConfig_apiKey_plaintextNeverOnPg_roundtrips_restartDecrypts() = EmbeddedPostgres.start().use { pg ->
        val mk = SecretCipherFactory.newBoxKeyset()
        val secrets = Secrets(emptyMap(), null, null)
        val key = "sk-ant-SUPER-SECRET-key-99998888"
        val store = PgProjectConfigStore(pg.postgresDatabase, cipher(mk), RepoConfig("", "main"), secrets)
        store.setApiKey("default", key)

        assertEquals(key, store.resolvedApiKey("default"))
        assertTrue(store.apiKeyView("default").set)
        assertFalse(store.apiKeyView("default").masked!!.contains(key), "view must be masked, not the key")
        assertFalse(pg.postgresDatabase.dumpTableBytes("project_config", 5).containsSub(key.encodeToByteArray()), "plaintext API key found in a PG row!")

        val reopened = PgProjectConfigStore(pg.postgresDatabase, cipher(mk), RepoConfig("", "main"), secrets, migrate = false)
        assertEquals(key, reopened.resolvedApiKey("default"))
    }

    @Test fun projectConfig_setRepo_preservesApiKey_andScopedFallback() = EmbeddedPostgres.start().use { pg ->
        val mk = SecretCipherFactory.newBoxKeyset()
        val secrets = Secrets(emptyMap(), null, null, mapOf("default" to "team-fallback-key-123456"))
        val store = PgProjectConfigStore(pg.postgresDatabase, cipher(mk), RepoConfig("https://fallback.example/r.git", "main"), secrets)

        // fallback when no row
        assertEquals(RepoConfig("https://fallback.example/r.git", "main"), store.resolvedRepo("default"))
        assertEquals("team-fallback-key-123456", store.resolvedApiKey("default"))

        store.setApiKey("default", "sk-explicit-key-abcdef123456")
        store.setRepo("default", "git@github.com:org/proj.git", "dev") // partial-column upsert must NOT wipe the key
        assertEquals("sk-explicit-key-abcdef123456", store.resolvedApiKey("default"), "setRepo preserved the API key")
        assertEquals(RepoConfig("git@github.com:org/proj.git", "dev"), store.resolvedRepo("default"))

        // remove is scoped + fail-closed
        assertFalse(store.remove(""), "blank projectId removes nothing")
        assertTrue(store.remove("default"))
        // the explicit override is gone → resolution falls back to the team key (row deleted, fallback resumes)
        assertEquals("team-fallback-key-123456", store.resolvedApiKey("default"))
        assertEquals(RepoConfig("https://fallback.example/r.git", "main"), store.resolvedRepo("default"))
    }

    // ---- boot-factory routing (File vs Pg per binding) ----

    @Test fun routing_unboundUsesFile_boundActiveUsesPg() = EmbeddedPostgres.start().use { pg ->
        val mk = SecretCipherFactory.newBoxKeyset()
        val c = cipher(mk)
        val dsns = DsnRegistry(null, c).apply {
            put(DsnDescriptor("pg1", "local", "localhost", pg.port, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
        }

        // unbound → File fallback (the pool is never touched)
        val unbound = BindingRegistry(null)
        ConnectionProvider(dsns, unbound, maxPoolSize = 2).use { cp ->
            val store = PgStoreRouting.remoteTokenStore("default", unbound, cp, c) { FileRemoteTokenStore(null) }
            assertTrue(store is FileRemoteTokenStore, "unbound → File fallback")
        }

        // bound + ACTIVE → Pg (and it actually works against the real embedded PG)
        val bound = BindingRegistry(null).apply { bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.ACTIVE) }
        ConnectionProvider(dsns, bound, maxPoolSize = 2).use { cp ->
            val store = PgStoreRouting.remoteTokenStore("default", bound, cp, c) { FileRemoteTokenStore(null) }
            assertTrue(store is PgRemoteTokenStore, "bound+ACTIVE → Postgres")
            store.put("x", "tok-routed-to-pg-1"); assertEquals("tok-routed-to-pg-1", store.all()["x"])
        }
    }

    @Test fun routing_mustStayHomeStore_neverRoutesToPg_evenBoundActive() = EmbeddedPostgres.start().use { pg ->
        // FAIL-CLOSED residency enforcement (P6-S2 finding): even if a MUST_STAY_HOME store is bound + ACTIVE
        // (metadata), the placement point must REFUSE a user-DB route — never Pg. Guard-out mutation → RED.
        val mk = SecretCipherFactory.newBoxKeyset()
        val c = cipher(mk)
        val dsns = DsnRegistry(null, c).apply {
            put(DsnDescriptor("pg1", "local", "localhost", pg.port, "postgres", "postgres", sslMode = "disable", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "pw")
        }
        val bindings = BindingRegistry(null).apply {
            // a bootstrap store bound + ACTIVE to a user DB (the leak P6-S1 classifies against)
            bind("dsn_registry", "default", "pg1"); setState("dsn_registry", "default", BindingState.ACTIVE)
            // and a genuinely offloadable store, also bound + ACTIVE
            bind("remote_token", "default", "pg1"); setState("remote_token", "default", BindingState.ACTIVE)
        }
        ConnectionProvider(dsns, bindings, maxPoolSize = 2).use { cp ->
            assertNull(PgStoreRouting.activeDataSource("dsn_registry", "default", bindings, cp), "MUST_STAY_HOME never routes to a user DB, even bound+ACTIVE")
            // non-vacuous: the guard is residency-specific, not a blanket null — a capable store DOES route to Pg
            assertNotNull(PgStoreRouting.activeDataSource("remote_token", "default", bindings, cp))
            Unit
        }
    }
}
