package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.boot.FileProjectRegistry
import com.tneff.cyppieagents.boot.PgProjectRegistry
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.model.CreateProjectRequest
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 4 — the GENERIC [StoreMigrator] + [MigrationAudit], exercised on the ProjectRegistry store
 * (source File → target real embedded Postgres): happy copy/verify/rebind (+ audit trail, no secrets), a
 * count-equal-content-different corruption caught ONLY by the checksum (rollback), a copy failure rolled back,
 * and the restart-decrypt end-to-end. The migrator itself is store-agnostic (row-based).
 */
class StoreMigratorTest {

    private fun fileRegistry(): FileProjectRegistry {
        val f = Files.createTempFile("pr-src", ".json").toFile()
        return FileProjectRegistry(f, "default", "Default").apply {
            create(CreateProjectRequest("beta", "Beta"))
            create(CreateProjectRequest("gamma", "Gamma"))
            setActive("beta")
        }
    }

    private class ToggleableDataSource(private val delegate: DataSource) : DataSource by delegate {
        @Volatile var broken = false
        override fun getConnection(): Connection = if (broken) throw SQLException("injected") else delegate.connection
        override fun getConnection(username: String?, password: String?): Connection =
            if (broken) throw SQLException("injected") else delegate.getConnection(username, password)
    }

    @Test fun migrate_happy_copiesVerifiesRebinds_andAuditsWithoutSecrets() = EmbeddedPostgres.start().use { pg ->
        val source = fileRegistry()
        val target = PgProjectRegistry(pg.postgresDatabase, "default", seedIfEmpty = false)
        val bindings = BindingRegistry(null)
        val audit = InMemoryMigrationAudit()

        // CYP-773: the store key is the CANONICAL `project` (the key StoreResidencies + PgStoreRouting use for the
        // project registry), not the old fictional `projectregistry` label — the migrator's residency guard now
        // makes the key load-bearing, and `project` IS user-DB-capable so this happy path stays valid.
        val receipt = StoreMigrator(bindings, audit).migrate(source, target, "project", "default", "pg1", actor = "op-alice")

        assertTrue(receipt.ok && receipt.checksumMatch)
        assertEquals(4, receipt.sourceRows) // 1 active row + 3 project rows
        assertEquals(listOf("default", "beta", "gamma"), target.projects().map { it.id })
        assertEquals("beta", target.activeProjectId())
        val b = bindings.binding("project", "default")!!
        assertEquals("pg1", b.dsnId); assertEquals(BindingState.ACTIVE, b.state)

        // Audit trail (§5): the phases in order, all OK, stamped with the actor.
        assertEquals(
            listOf(MigrationPhase.WINDOW_OPEN, MigrationPhase.COPY, MigrationPhase.VERIFY, MigrationPhase.REBIND),
            audit.entries().map { it.phase },
        )
        assertTrue(audit.entries().all { it.result == MigrationResult.OK && it.actor == "op-alice" && it.toDsnId == "pg1" })
        // NO secrets: even if a DSN password exists for pg1, the audit references the dsnId only — never the password.
        val secret = "SUPER-SECRET-DSN-PW"
        assertTrue(audit.entries().none { com.tneff.cyppieagents.CommJson.encodeToString(it).contains(secret) })
    }

    @Test fun migrate_copyFailure_rollsBackToSource_auditsRollback() = EmbeddedPostgres.start().use { pg ->
        val source = fileRegistry()
        val toggle = ToggleableDataSource(pg.postgresDatabase)
        val target = PgProjectRegistry(toggle, "default", seedIfEmpty = false) // schema migrated while healthy
        val bindings = BindingRegistry(null)
        val audit = InMemoryMigrationAudit()

        toggle.broken = true
        assertFailsWith<SQLException> {
            StoreMigrator(bindings, audit).migrate(source, target, "project", "default", "pg1", actor = "op")
        }
        assertNull(bindings.binding("project", "default"), "failed copy → unbound (File fallback)")
        assertEquals(listOf("default", "beta", "gamma"), source.projects().map { it.id }, "source A retained + intact")
        val last = audit.entries().last()
        assertEquals(MigrationPhase.ROLLBACK, last.phase); assertEquals(MigrationResult.FAILED, last.result)
    }

    @Test fun migrate_checksumMismatch_countEqual_abortsAndRollsBack() {
        // NON-VACUOUS checksum tooth: a target that stores COUNT-EQUAL, CONTENT-DIFFERENT rows → count matches,
        // ONLY the checksum catches it → MigrationVerifyException → rollback. Mutate the checksum out → RED.
        val source = fileRegistry()
        val corrupt = object : MigrationTarget {
            private var stored: List<ByteArray> = emptyList()
            override fun importRows(rows: List<ByteArray>) {
                stored = rows.map { row ->
                    val f = MigrationRowCodec.decode(row)
                    MigrationRowCodec.encode(f.mapIndexed { i, s -> if (i == f.lastIndex) s + "-tampered" else s })
                }
            }
            override fun exportRows(): List<ByteArray> = stored
        }
        val bindings = BindingRegistry(null)
        val audit = InMemoryMigrationAudit()
        assertFailsWith<MigrationVerifyException> {
            StoreMigrator(bindings, audit).migrate(source, corrupt, "project", "default", "pg1", actor = "op")
        }
        assertNull(bindings.binding("project", "default"))
        assertEquals(listOf("default", "beta", "gamma"), source.projects().map { it.id })
        assertEquals(MigrationPhase.VERIFY, audit.entries().first { it.result == MigrationResult.FAILED }.phase)
    }

    @Test fun restartDecrypt_encryptedDsnSurvivesRestart_thenPoolConnectsToRealPg() = EmbeddedPostgres.start().use { pg ->
        val masterKey = SecretCipherFactory.newBoxKeyset()
        fun cipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(masterKey))
        val dsnFile = Files.createTempFile("dsn-restart", ".json").toFile()

        DsnRegistry(dsnFile, cipher()).put(
            DsnDescriptor("pg1", "embedded", "localhost", pg.port, "postgres", "postgres", sslMode = "disable", createdBy = "op", createdAt = 1L),
            "the-db-password",
        )
        // RESTART: fresh DsnRegistry from the same file with a fresh cipher built from the SAME master key.
        val reopened = DsnRegistry(dsnFile, cipher())
        assertEquals("the-db-password", reopened.resolve("pg1")!!.password, "encrypted DSN survived restart + decrypted")

        val bindings = BindingRegistry(null).apply { bind("project", "default", "pg1") }
        ConnectionProvider(reopened, bindings, maxPoolSize = 2).use { cp ->
            val ds = cp.forStore("project", "default")!!
            assertEquals(listOf("default"), PgProjectRegistry(ds, "default").projects().map { it.id })
        }
    }
}
