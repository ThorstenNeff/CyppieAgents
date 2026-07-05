package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.boot.PgProjectRegistry
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.PrintWriter
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 3 — the File→PG migration mechanic + the restart-decrypt end-to-end (the Test-flagged
 * KMS/DEK durability concern), both against a REAL embedded Postgres.
 */
class ProjectRegistryMigrationTest {

    private fun fileRegistry(): ProjectRegistry {
        val f = Files.createTempFile("pr-src", ".json").toFile()
        return ProjectRegistry(f, "default", "Default").apply {
            create(CreateProjectRequest("beta", "Beta"))
            create(CreateProjectRequest("gamma", "Gamma"))
            setActive("beta")
        }
    }

    /** Wraps a DataSource so a test can BREAK it mid-migration (to exercise the rollback path). */
    private class ToggleableDataSource(private val delegate: DataSource) : DataSource by delegate {
        @Volatile var broken = false
        override fun getConnection(): Connection = if (broken) throw SQLException("injected") else delegate.connection
        override fun getConnection(username: String?, password: String?): Connection =
            if (broken) throw SQLException("injected") else delegate.getConnection(username, password)
    }

    @Test fun migrate_happy_copiesVerifiesRebinds() = EmbeddedPostgres.start().use { pg ->
        val source = fileRegistry()
        val target = PgProjectRegistry(pg.postgresDatabase, "default", seedIfEmpty = false)
        val bindings = BindingRegistry(null)
        val receipt = ProjectRegistryMigrator(bindings).migrate(source, target, "projectregistry", "default", "pg1")

        assertTrue(receipt.ok && receipt.checksumMatch)
        assertEquals(3, receipt.sourceRows); assertEquals(3, receipt.targetRows)
        // the PG target now mirrors the source (order + active pointer)
        assertEquals(listOf("default", "beta", "gamma"), target.projects().map { it.id })
        assertEquals("beta", target.activeProjectId())
        // atomic rebind complete: the store is bound to the PG instance, ACTIVE
        val b = bindings.binding("projectregistry", "default")!!
        assertEquals("pg1", b.dsnId); assertEquals(BindingState.ACTIVE, b.state)
    }

    @Test fun migrate_failure_rollsBackToSource_sourceRetained() = EmbeddedPostgres.start().use { pg ->
        val source = fileRegistry()
        val toggle = ToggleableDataSource(pg.postgresDatabase)
        val target = PgProjectRegistry(toggle, "default", seedIfEmpty = false) // schema migrated while healthy
        val bindings = BindingRegistry(null)

        toggle.broken = true // the copy will now fail
        assertFailsWith<SQLException> {
            ProjectRegistryMigrator(bindings).migrate(source, target, "projectregistry", "default", "pg1")
        }
        // ROLLBACK: the store is unbound → falls back to the File source, which is untouched (A retained).
        assertNull(bindings.binding("projectregistry", "default"), "failed migration → unbound (File fallback)")
        assertEquals(listOf("default", "beta", "gamma"), source.projects().map { it.id }, "source registry intact")
        assertEquals("beta", source.activeProjectId())
    }

    @Test fun migrate_checksumMismatch_countEqual_abortsAndRollsBack() {
        // NON-VACUOUS checksum tooth (Test/PO-Assistant Finding A): a target that copies a COUNT-EQUAL but
        // CONTENT-DIFFERENT snapshot. Row-count matches → ONLY the checksum catches it. This exercises the
        // mismatch → MigrationVerifyException → rollback branch that no real-PG test could reach (importAll is
        // faithful). Mutate the checksum out (count-only) → this goes RED (no exception). No embedded PG needed.
        val source = fileRegistry() // [default, beta, gamma], active = beta
        val corruptTarget = object : MigrationTarget {
            private var stored: List<Project> = emptyList()
            private var active = ""
            override fun importAll(projects: List<Project>, active: String) {
                this.stored = projects.map { it.copy(name = it.name + "-tampered") } // same COUNT, different content
                this.active = active
            }
            override fun projects(): List<Project> = stored
            override fun activeProjectId(): String = active
        }
        val bindings = BindingRegistry(null)
        assertFailsWith<MigrationVerifyException> {
            ProjectRegistryMigrator(bindings).migrate(source, corruptTarget, "projectregistry", "default", "pg1")
        }
        // rollback: the store is unbound (File fallback) and the source is byte-for-byte intact (A retained).
        assertNull(bindings.binding("projectregistry", "default"), "checksum mismatch → unbound (rollback to File)")
        assertEquals(listOf("default", "beta", "gamma"), source.projects().map { it.id }, "source A retained + intact")
        assertEquals("beta", source.activeProjectId())
    }

    @Test fun restartDecrypt_encryptedDsnSurvivesRestart_thenPoolConnectsToRealPg() = EmbeddedPostgres.start().use { pg ->
        // The stable master key (CYPPIE_MASTER_KEY) — same across a "restart".
        val masterKey = SecretCipherFactory.newBoxKeyset()
        fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(masterKey))
        val dsnFile = Files.createTempFile("dsn-restart", ".json").toFile()

        // Register a DSN pointing at the embedded PG; the password is encrypted at rest.
        DsnRegistry(dsnFile, cipher()).put(
            DsnDescriptor("pg1", "embedded", "localhost", pg.port, "postgres", "postgres", sslMode = "disable", createdBy = "op", createdAt = 1L),
            "the-db-password",
        )

        // RESTART: a fresh DsnRegistry from the SAME file with a fresh cipher built from the SAME master key.
        val reopened = DsnRegistry(dsnFile, cipher())
        val resolved = reopened.resolve("pg1")!!
        assertEquals("the-db-password", resolved.password, "encrypted DSN password survived the restart and decrypted")

        // And the ConnectionProvider connects to the REAL embedded PG with the resolved creds → the store works.
        val bindings = BindingRegistry(null).apply { bind("projectregistry", "default", "pg1") }
        ConnectionProvider(reopened, bindings, maxPoolSize = 2).use { cp ->
            val ds = cp.forStore("projectregistry", "default")!!
            val reg = PgProjectRegistry(ds, "default")
            assertEquals(listOf("default"), reg.projects().map { it.id }, "PgProjectRegistry operates over the pool built from the restart-decrypted DSN")
        }
    }
}
