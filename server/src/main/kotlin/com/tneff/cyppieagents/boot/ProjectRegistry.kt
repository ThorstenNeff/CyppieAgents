package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Persisted registry snapshot (server-internal): the N projects plus which one is active. */
@Serializable
private data class RegistrySnapshot(
    val activeProjectId: String,
    val projects: List<Project>,
)

/**
 * The single source for the [ProjectGuard] code → HTTP-exception mapping — shared by every [ProjectRegistry]
 * impl (File + PG) so the 4xx contract can't drift between backends (CLAUDE.md: single-source derived values).
 */
internal fun projectGuardException(code: String): Exception = when (code) {
    "invalid_project_id" -> BadRequestException("invalid project id", code = "invalid_project_id")
    "project_exists" -> ConflictException("a project with this id already exists", code = "project_exists")
    "project_not_found" -> NotFoundException("project not found", code = "project_not_found")
    "last_project" -> ConflictException("cannot delete the last project", code = "last_project")
    "active_project_protected" -> ConflictException("cannot delete the active project; switch away first", code = "active_project_protected")
    else -> BadRequestException(code, code = code)
}

/**
 * The multi-project registry (S13 / CYP-91 — "die Eins auf N aufmachen"): the persisted set of N
 * projects plus the active-project pointer, seeded from the boot `config.projectId` so a single-project
 * MVP install upgrades to N transparently.
 *
 * CYP-223 (CYP-220 Phase 1): **store-seam interface;** default impl [FileProjectRegistry]; a future PG
 * impl implements this; companion `invoke` = current factory choice, no behavior change.
 */
interface ProjectRegistry {

    // ---- reads ----

    fun view(): ProjectsView
    fun activeProjectId(): String
    fun projects(): List<Project>
    fun exists(id: String): Boolean

    // ---- mutations (operator-gated at the endpoint) ----

    /** Create a project. Throws the §guard 4xx on `invalid_project_id` / `project_exists`. */
    fun create(spec: CreateProjectRequest): Project

    /** Rename a project (id is immutable). Throws on `invalid_project_id` (blank) / `project_not_found`. */
    fun rename(id: String, name: String): Project

    /** Flip the active pointer (PROVISIONAL — pointer only; live re-instancing is deferred). 404 if unknown. */
    fun setActive(projectId: String): ProjectsView

    /**
     * Guard the delete WITHOUT mutating — the fail-closed precondition [ProjectDeleter] checks BEFORE
     * any resource teardown, so a rejected delete cascades nothing. Throws `project_not_found` /
     * `last_project` / `active_project_protected`.
     */
    fun requireDeletable(id: String)

    /**
     * Drop a project's metadata + persist — the registry commit at the END of the cascade (after the
     * resources are torn down). Re-runs the guard (deny-wins; the state can't have drifted under the
     * deleter's lock, but the re-check keeps this method safe to call on its own).
     */
    fun drop(id: String): Project

    companion object {
        /** Factory seam (CYP-223): the current impl choice is the file store. */
        operator fun invoke(
            file: File?,
            seedProjectId: String,
            seedProjectName: String = seedProjectId,
        ): ProjectRegistry = FileProjectRegistry(file, seedProjectId, seedProjectName)
    }
}

/**
 * All mutations run the pure [ProjectGuard] first and throw
 * the matching 4xx on a violation — **fail-closed: a rejection mutates nothing** (and, for delete,
 * cascades nothing; see [ProjectDeleter]). The endpoints layered on top are operator-gated.
 *
 * **Scope of THIS class (the non-controversial mechanics):** it owns the registry metadata + the
 * active pointer only. [setActive] flips the persisted pointer; the *live* per-project hub/session
 * re-instancing that a real active-switch implies is the deferred S13-design/architecture part, NOT
 * here. Insertion order is preserved so the list view is stable.
 *
 * Persistence mirrors [ProjectConfigStore]: an atomic 0600 write to an out-of-repo file under the
 * gitRoot working dir (`null` file → in-memory only, for tests / dry boots).
 */
class FileProjectRegistry(
    private val file: File?,
    seedProjectId: String,
    seedProjectName: String = seedProjectId,
) : ProjectRegistry {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("boot.projectregistry")
    private val projects = LinkedHashMap<String, Project>() // insertion order == list order
    private var active: String

    init {
        val f = file
        val loaded = if (f != null && f.exists() && f.length() > 0) {
            runCatching { CommJson.decodeFromString<RegistrySnapshot>(f.readText()) }
                .onFailure { log.error("corrupt project registry at {}; reseeding from boot config", f) }
                .getOrNull()
        } else {
            null
        }
        if (loaded != null && loaded.projects.isNotEmpty() && loaded.projects.any { it.id == loaded.activeProjectId }) {
            loaded.projects.forEach { projects[it.id] = it }
            active = loaded.activeProjectId
        } else {
            // Seed the one MVP project as active. A blank seed id would be unscoped — fall back to the
            // S12 default so the registry always has a valid active project (fail-closed invariant).
            val id = seedProjectId.ifBlank { com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID }
            projects[id] = Project(id, seedProjectName.ifBlank { id })
            active = id
        }
    }

    // ---- reads ----

    override fun view(): ProjectsView = synchronized(lock) { ProjectsView(active, projects.values.toList()) }
    override fun activeProjectId(): String = synchronized(lock) { active }
    override fun projects(): List<Project> = synchronized(lock) { projects.values.toList() }
    override fun exists(id: String): Boolean = synchronized(lock) { projects.containsKey(id) }

    // ---- mutations (operator-gated at the endpoint) ----

    override fun create(spec: CreateProjectRequest): Project = synchronized(lock) {
        ProjectGuard.validateCreate(projects.values.toList(), spec)?.let { throw codeToException(it) }
        val p = Project(spec.id.trim(), spec.name.trim())
        projects[p.id] = p
        persist()
        p
    }

    override fun rename(id: String, name: String): Project = synchronized(lock) {
        ProjectGuard.validateRename(projects.values.toList(), id, name)?.let { throw codeToException(it) }
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
        ProjectGuard.validateDelete(projects.values.toList(), id, active)?.let { throw codeToException(it) }
        Unit
    }

    override fun drop(id: String): Project = synchronized(lock) {
        ProjectGuard.validateDelete(projects.values.toList(), id, active)?.let { throw codeToException(it) }
        val removed = projects.remove(id) ?: throw NotFoundException("project '$id' not found", code = "project_not_found")
        persist()
        removed
    }

    private fun codeToException(code: String): Exception = projectGuardException(code)

    // ---- persistence (atomic 0600; mirrors ProjectConfigStore) ----

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { parent ->
            parent.mkdirs()
            restrictDirToOwner(parent)
        }
        val snapshot = RegistrySnapshot(active, projects.values.toList())
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        restrictToOwner(tmp)
        tmp.writeText(CommJson.encodeToString(snapshot))
        restrictToOwner(tmp)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(f)
    }

    private fun restrictToOwner(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }.onFailure {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    private fun restrictDirToOwner(d: File) {
        runCatching {
            Files.setPosixFilePermissions(
                d.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }.onFailure {
            d.setReadable(false, false); d.setReadable(true, true)
            d.setWritable(false, false); d.setWritable(true, true)
            d.setExecutable(false, false); d.setExecutable(true, true)
        }
    }
}
