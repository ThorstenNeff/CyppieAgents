package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.routing.NotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [ProjectRegistry]. The registry is a small in-memory model (project
 * order + active pointer) guarded by the pure [ProjectGuard] — this impl mirrors [FileProjectRegistry] EXACTLY
 * (same reads/mutations/guards/seed) and only swaps the JSON-file persistence for a single-row SQLite snapshot.
 * First-boot import of the old `projects.json` (empty-DB guard) preserves a multi-project deploy's registry.
 * Single held connection + WAL, `synchronized` (non-`suspend` interface). NOT a `MigrationTarget` (local store).
 */
class SqliteProjectRegistry(
    dbPath: Path,
    seedProjectId: String,
    seedProjectName: String = seedProjectId,
    legacyJson: Path? = null,
) : ProjectRegistry, AutoCloseable {
    private val log = LoggerFactory.getLogger("boot.projectregistry.sqlite")
    private val lock = Any()
    private val conn: Connection
    private val projects = LinkedHashMap<String, Project>() // insertion order == list order
    private var active: String

    /** Field-compatible with the (private) FileProjectRegistry snapshot, so the legacy `projects.json` decodes. */
    @Serializable
    private data class RegSnapshot(val activeProjectId: String, val projects: List<Project>)

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(CREATE_TABLE)
        }
        var loaded = readSnapshot()
        if (loaded == null && legacyJson != null && Files.exists(legacyJson)) {
            loaded = runCatching { CommJson.decodeFromString(RegSnapshot.serializer(), Files.readString(legacyJson)) }
                .onSuccess { log.info("imported legacy project registry from {}", legacyJson) }
                .onFailure { log.error("corrupt legacy project registry at {}; reseeding", legacyJson) }
                .getOrNull()
        }
        if (loaded != null && loaded.projects.isNotEmpty() && loaded.projects.any { it.id == loaded.activeProjectId }) {
            loaded.projects.forEach { projects[it.id] = it }
            active = loaded.activeProjectId
        } else {
            // Seed the one MVP project as active (fail-closed: a blank seed id → the S12 default).
            val id = seedProjectId.ifBlank { DEFAULT_PROJECT_ID }
            projects[id] = Project(id, seedProjectName.ifBlank { id })
            active = id
        }
        persist() // make the imported/seeded state durable (→ the empty-DB guard skips re-import next boot)
    }

    override fun view(): ProjectsView = synchronized(lock) { ProjectsView(active, projects.values.toList()) }
    override fun activeProjectId(): String = synchronized(lock) { active }
    override fun projects(): List<Project> = synchronized(lock) { projects.values.toList() }
    override fun exists(id: String): Boolean = synchronized(lock) { projects.containsKey(id) }

    override fun create(spec: CreateProjectRequest): Project = synchronized(lock) {
        ProjectGuard.validateCreate(projects.values.toList(), spec)?.let { throw projectGuardException(it) }
        val p = Project(spec.id.trim(), spec.name.trim())
        projects[p.id] = p
        persist()
        p
    }

    override fun rename(id: String, name: String): Project = synchronized(lock) {
        ProjectGuard.validateRename(projects.values.toList(), id, name)?.let { throw projectGuardException(it) }
        val updated = projects.getValue(id).copy(name = name.trim())
        projects[id] = updated
        persist()
        updated
    }

    override fun setActive(projectId: String): ProjectsView = synchronized(lock) {
        if (!projects.containsKey(projectId)) throw NotFoundException("project '$projectId' not found", code = "project_not_found")
        active = projectId
        persist()
        ProjectsView(active, projects.values.toList())
    }

    override fun requireDeletable(id: String) = synchronized(lock) {
        ProjectGuard.validateDelete(projects.values.toList(), id, active)?.let { throw projectGuardException(it) }
        Unit
    }

    override fun drop(id: String): Project = synchronized(lock) {
        ProjectGuard.validateDelete(projects.values.toList(), id, active)?.let { throw projectGuardException(it) }
        val removed = projects.remove(id) ?: throw NotFoundException("project '$id' not found", code = "project_not_found")
        persist()
        removed
    }

    private fun readSnapshot(): RegSnapshot? =
        conn.prepareStatement("SELECT snapshot_json FROM registry WHERE id=1").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) runCatching { CommJson.decodeFromString(RegSnapshot.serializer(), rs.getString(1)) }.getOrNull() else null
            }
        }

    /** Persist the whole snapshot into the single row (caller holds [lock], or init before serving). */
    private fun persist() {
        val json = CommJson.encodeToString(RegSnapshot.serializer(), RegSnapshot(active, projects.values.toList()))
        conn.prepareStatement("INSERT INTO registry(id, snapshot_json) VALUES(1, ?) ON CONFLICT(id) DO UPDATE SET snapshot_json=excluded.snapshot_json").use { ps ->
            ps.setString(1, json)
            ps.executeUpdate()
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS registry (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              snapshot_json TEXT NOT NULL
            )
        """.trimIndent()
    }
}
