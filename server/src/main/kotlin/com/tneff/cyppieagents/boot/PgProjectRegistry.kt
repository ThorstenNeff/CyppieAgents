package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.routing.NotFoundException
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 3 — the **Postgres** [ProjectRegistry] impl (the proof vertical). Behaviour-identical to
 * [FileProjectRegistry]: the same [ProjectGuard] runs first (fail-closed, mutate-nothing on a violation), the
 * same 4xx codes ([projectGuardException]), insertion order preserved (via `project.seq`), and the boot project
 * seeded when empty. The schema is Flyway-migrated at construction ([FlywayMigrator], Design §4.1). A JVM lock
 * makes each read-guard-write a single unit (mirrors File's `synchronized`); each unit runs in one transaction.
 */
class PgProjectRegistry(
    private val dataSource: DataSource,
    seedProjectId: String,
    seedProjectName: String = seedProjectId,
    migrate: Boolean = true,
    /** A **migration target** passes false: migrate the schema but do NOT seed — the migrator fills it via [importAll]. */
    seedIfEmpty: Boolean = true,
) : ProjectRegistry {
    private val lock = Any()

    init {
        if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/projectregistry")
        synchronized(lock) {
            tx { c ->
                if (count(c) == 0L) {
                    if (seedIfEmpty) {
                        val id = seedProjectId.ifBlank { DEFAULT_PROJECT_ID }
                        insertProject(c, Project(id, seedProjectName.ifBlank { id }))
                        setActivePointer(c, id)
                    }
                } else if (readActive(c) == null) {
                    // non-empty (e.g. migrated data) but no active pointer → pin the first project (defensive).
                    setActivePointer(c, allProjects(c).first().id)
                }
            }
        }
    }

    /**
     * CYP-220 Phase 3 — RAW bulk copy for a File→PG migration (no [ProjectGuard], no seed): replace the whole
     * table with [projects] (in order) + set the active pointer. One transaction — all-or-nothing. Used only by
     * [com.tneff.cyppieagents.db.ProjectRegistryMigrator] into a freshly-migrated, un-seeded target.
     */
    fun importAll(projects: List<Project>, active: String) = synchronized(lock) {
        tx { c ->
            c.prepareStatement("DELETE FROM project").use { it.executeUpdate() }
            c.prepareStatement("DELETE FROM project_active").use { it.executeUpdate() }
            projects.forEach { insertProject(c, it) } // seq auto-increments in insertion order
            setActivePointer(c, active)
        }
    }

    // ---- reads ----

    override fun view(): ProjectsView = synchronized(lock) { tx { c -> ProjectsView(requireActive(c), allProjects(c)) } }
    override fun activeProjectId(): String = synchronized(lock) { tx { c -> requireActive(c) } }
    override fun projects(): List<Project> = synchronized(lock) { tx { c -> allProjects(c) } }
    override fun exists(id: String): Boolean = synchronized(lock) {
        tx { c -> c.prepareStatement("SELECT 1 FROM project WHERE project_id = ?").use { it.setString(1, id); it.executeQuery().use { rs -> rs.next() } } }
    }

    // ---- mutations (guard first; fail-closed) ----

    override fun create(spec: CreateProjectRequest): Project = synchronized(lock) {
        tx { c ->
            ProjectGuard.validateCreate(allProjects(c), spec)?.let { throw projectGuardException(it) }
            val p = Project(spec.id.trim(), spec.name.trim())
            insertProject(c, p)
            p
        }
    }

    override fun rename(id: String, name: String): Project = synchronized(lock) {
        tx { c ->
            ProjectGuard.validateRename(allProjects(c), id, name)?.let { throw projectGuardException(it) }
            c.prepareStatement("UPDATE project SET name = ? WHERE project_id = ?").use { it.setString(1, name.trim()); it.setString(2, id); it.executeUpdate() }
            Project(id, name.trim())
        }
    }

    override fun setActive(projectId: String): ProjectsView = synchronized(lock) {
        tx { c ->
            if (!existsIn(c, projectId)) throw NotFoundException("project '$projectId' not found", code = "project_not_found")
            setActivePointer(c, projectId)
            ProjectsView(projectId, allProjects(c))
        }
    }

    override fun requireDeletable(id: String): Unit = synchronized(lock) {
        tx { c -> ProjectGuard.validateDelete(allProjects(c), id, requireActive(c))?.let { throw projectGuardException(it) } }
        Unit
    }

    override fun drop(id: String): Project = synchronized(lock) {
        tx { c ->
            ProjectGuard.validateDelete(allProjects(c), id, requireActive(c))?.let { throw projectGuardException(it) }
            val existing = allProjects(c).firstOrNull { it.id == id }
                ?: throw NotFoundException("project '$id' not found", code = "project_not_found")
            c.prepareStatement("DELETE FROM project WHERE project_id = ?").use { it.setString(1, id); it.executeUpdate() }
            existing
        }
    }

    // ---- SQL helpers ----

    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        val prev = c.autoCommit
        c.autoCommit = false
        try {
            val r = block(c)
            c.commit()
            r
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = prev }
        }
    }

    private fun allProjects(c: Connection): List<Project> =
        c.prepareStatement("SELECT project_id, name FROM project ORDER BY seq").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Project(rs.getString(1), rs.getString(2))) }
            }
        }

    private fun count(c: Connection): Long =
        c.prepareStatement("SELECT COUNT(*) FROM project").use { it.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }

    private fun existsIn(c: Connection, id: String): Boolean =
        c.prepareStatement("SELECT 1 FROM project WHERE project_id = ?").use { it.setString(1, id); it.executeQuery().use { rs -> rs.next() } }

    private fun insertProject(c: Connection, p: Project) =
        c.prepareStatement("INSERT INTO project (project_id, name) VALUES (?, ?)").use { it.setString(1, p.id); it.setString(2, p.name); it.executeUpdate() }

    private fun readActive(c: Connection): String? =
        c.prepareStatement("SELECT active_project_id FROM project_active LIMIT 1").use { it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun requireActive(c: Connection): String =
        readActive(c) ?: throw IllegalStateException("project registry has no active pointer")

    private fun setActivePointer(c: Connection, id: String) =
        c.prepareStatement(
            "INSERT INTO project_active (only_one, active_project_id) VALUES (TRUE, ?) " +
                "ON CONFLICT (only_one) DO UPDATE SET active_project_id = EXCLUDED.active_project_id",
        ).use { it.setString(1, id); it.executeUpdate() }
}
