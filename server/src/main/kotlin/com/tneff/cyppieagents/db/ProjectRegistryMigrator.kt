package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.model.Project
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The **migration-target seam** (CYP-220 Phase 3): exactly what [ProjectRegistryMigrator] needs of a target —
 * a raw bulk [importAll] plus the two verify reads. Implemented by [com.tneff.cyppieagents.boot.PgProjectRegistry];
 * an interface (not the concrete class) so a test can inject a **count-equal, content-different** target and
 * exercise the checksum-mismatch → abort → rollback branch (which a count-only verify would slip through).
 */
interface MigrationTarget {
    fun importAll(projects: List<Project>, active: String)
    fun projects(): List<Project>
    fun activeProjectId(): String
}

/** The outcome of a store migration — honest no-orphan reporting (counts + checksum, no content). */
data class MigrationReceipt(
    val storeKey: String,
    val projectId: String,
    val sourceRows: Int,
    val targetRows: Int,
    val checksumMatch: Boolean,
    val reboundToDsnId: String,
    val ok: Boolean,
)

/** Thrown when the post-copy verification (row-count OR checksum) fails — the migration is aborted, source retained. */
class MigrationVerifyException(message: String) : Exception(message)

/**
 * CYP-220 Phase 3 — the File→PG migration mechanic at ONE store (ProjectRegistry), Design §4:
 * **read-only window → copy A→B → row-count AND checksum verify → atomic rebind, with A retained for rollback.**
 *
 * The window is a [BindingState.MIGRATING] on the store's binding (writes gated + reads served from the source
 * A — enforced at the store-access layer once stores are wired to the [ConnectionProvider]; not in this
 * seam-phase). The **atomic rebind** is the single `MIGRATING → ACTIVE` flip on success; **rollback** is an
 * `unbind` back to the File source, which is NEVER dropped here (a separate, explicit decommission drops A).
 */
class ProjectRegistryMigrator(private val bindings: BindingRegistry) {

    fun migrate(
        source: ProjectRegistry,
        target: MigrationTarget,
        storeKey: String,
        projectId: String,
        targetDsnId: String,
    ): MigrationReceipt {
        // Window open: bind to the target instance in MIGRATING (writes gated; reads still from source A).
        bindings.bind(storeKey, projectId, targetDsnId)
        bindings.setState(storeKey, projectId, BindingState.MIGRATING)
        try {
            // Copy A → B (raw bulk, all-or-nothing in one transaction inside importAll).
            val active = source.activeProjectId()
            val projects = source.projects()
            target.importAll(projects, active)

            // Verify: row-count AND content checksum (count alone can miss corruption).
            val tProjects = target.projects()
            val countMatch = projects.size == tProjects.size
            val checksumMatch = checksum(active, projects) == checksum(target.activeProjectId(), tProjects)
            if (!countMatch || !checksumMatch) {
                throw MigrationVerifyException("verify failed: countMatch=$countMatch checksumMatch=$checksumMatch (source=${projects.size}, target=${tProjects.size})")
            }

            // Atomic rebind: the single flip that makes B live. From here the store resolves to the PG instance.
            bindings.setState(storeKey, projectId, BindingState.ACTIVE)
            return MigrationReceipt(storeKey, projectId, projects.size, tProjects.size, checksumMatch, targetDsnId, ok = true)
        } catch (e: Throwable) {
            // Rollback: unbind → the store falls back to the (retained, never-dropped) File source A. Fail-closed.
            bindings.unbind(storeKey, projectId)
            throw e
        }
    }

    /**
     * Deterministic content checksum of the ordered snapshot — length-prefixed (injective, no delimiter
     * collision): [len(active)][active] then per project [len(id)][id][len(name)][name]. Compared source vs
     * target (not count-only, which can miss corruption).
     */
    private fun checksum(active: String, projects: List<Project>): String {
        val out = ByteArrayOutputStream()
        fun part(s: String) {
            val b = s.encodeToByteArray()
            out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            out.write(b)
        }
        part(active)
        projects.forEach { part(it.id); part(it.name) }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
