package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** The migration lifecycle phases recorded in the audit (Design §5). */
enum class MigrationPhase { WINDOW_OPEN, COPY, VERIFY, REBIND, ROLLBACK, DECOMMISSION }

enum class MigrationResult { OK, FAILED }

/**
 * One append-only migration-audit record (Design §5). **NO secret values** — actor is an operator identity id,
 * the DSN references are ids (never passwords/DSNs), and the payload is counts + a non-secret error message
 * (a DSN password never touches a migration path; the jdbcUrl is credential-free). Evidence, so it lives on OUR
 * infra and survives a user-DB loss.
 */
@Serializable
data class MigrationAuditEntry(
    val actor: String,
    val ts: Long,
    val storeKey: String,
    val projectId: String,
    val fromDsnId: String?,
    val toDsnId: String,
    val phase: MigrationPhase,
    val result: MigrationResult,
    val sourceRows: Int = 0,
    val targetRows: Int = 0,
    val checksumMatch: Boolean = false,
    val error: String? = null,
)

/** Append-only migration audit (Design §5) — bootstrap store on OUR infra. */
interface MigrationAudit {
    fun record(entry: MigrationAuditEntry)
    fun entries(): List<MigrationAuditEntry>

    companion object {
        /** A no-op sink (default / tests that don't assert on the audit). */
        val NONE: MigrationAudit = object : MigrationAudit {
            override fun record(entry: MigrationAuditEntry) {}
            override fun entries(): List<MigrationAuditEntry> = emptyList()
        }
    }
}

/** In-memory audit (tests). */
class InMemoryMigrationAudit : MigrationAudit {
    private val log = java.util.Collections.synchronizedList(mutableListOf<MigrationAuditEntry>())
    override fun record(entry: MigrationAuditEntry) { log.add(entry) }
    override fun entries(): List<MigrationAuditEntry> = synchronized(log) { log.toList() }
}

/**
 * Durable append-only audit — one JSON entry per line under the gitRoot (out-of-repo, 0600). Append-only (never
 * rewrites) so the trail can't be silently mutated. `null` file → in-memory-off (a no-op file sink).
 */
class FileMigrationAudit(private val file: File?) : MigrationAudit {
    private val log = LoggerFactory.getLogger("db.migrationaudit")
    private val lock = Any()

    override fun record(entry: MigrationAuditEntry) = synchronized(lock) {
        val f = file ?: return
        f.parentFile?.let { it.mkdirs(); restrict(it, dir = true) }
        val fresh = !f.exists()
        f.appendText(CommJson.encodeToString(entry) + "\n")
        if (fresh) restrict(f, dir = false)
    }

    override fun entries(): List<MigrationAuditEntry> = synchronized(lock) {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        f.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { CommJson.decodeFromString<MigrationAuditEntry>(it) }
                .onFailure { e -> log.warn("skipping corrupt migration-audit line: {}", e.message) }
                .getOrNull()
        }
    }

    private fun restrict(f: File, dir: Boolean) {
        val perms = if (dir) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(f.toPath(), perms) }
    }
}
