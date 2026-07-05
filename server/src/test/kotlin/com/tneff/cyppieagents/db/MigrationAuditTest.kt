package com.tneff.cyppieagents.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CYP-220 Phase 4 — the migration audit (§5): append-only durability + NO secret values on the wire/at rest. */
class MigrationAuditTest {

    private fun entry(phase: MigrationPhase, result: MigrationResult) =
        MigrationAuditEntry("op-alice", 1_000L, "projectregistry", "default", fromDsnId = null, toDsnId = "pg1", phase = phase, result = result, sourceRows = 4, targetRows = 4, checksumMatch = true)

    @Test fun file_appendOnly_persistsAndReloadsAcrossInstances() {
        val f = Files.createTempFile("mig-audit", ".jsonl").toFile().also { it.delete() }
        val a = FileMigrationAudit(f)
        a.record(entry(MigrationPhase.WINDOW_OPEN, MigrationResult.OK))
        a.record(entry(MigrationPhase.VERIFY, MigrationResult.OK))

        // reload (a fresh instance / restart) sees the persisted trail, in order
        assertEquals(listOf(MigrationPhase.WINDOW_OPEN, MigrationPhase.VERIFY), FileMigrationAudit(f).entries().map { it.phase })

        // append-only: a later record ADDS (never rewrites) — the trail grows, older entries stay
        FileMigrationAudit(f).record(entry(MigrationPhase.REBIND, MigrationResult.OK))
        assertEquals(3, FileMigrationAudit(f).entries().size)
    }

    @Test fun noSecretValues_atRest() {
        val f = Files.createTempFile("mig-audit-sec", ".jsonl").toFile().also { it.delete() }
        FileMigrationAudit(f).record(entry(MigrationPhase.VERIFY, MigrationResult.OK))
        val onDisk = f.readText()
        // ids + counts only — a DSN password/secret never appears (the entry type has no secret field).
        assertFalse(onDisk.contains("password", ignoreCase = true), "audit must carry no secret values")
        assertTrue(onDisk.contains("pg1"), "dsnId (a non-secret reference) is fine")
        assertTrue(onDisk.contains("projectregistry"))
    }

    @Test fun inMemory_records_and_NONE_isNoOp() {
        val im = InMemoryMigrationAudit()
        im.record(entry(MigrationPhase.COPY, MigrationResult.OK))
        assertEquals(1, im.entries().size)
        MigrationAudit.NONE.record(entry(MigrationPhase.COPY, MigrationResult.OK))
        assertTrue(MigrationAudit.NONE.entries().isEmpty())
    }
}
