package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.BadRequestException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** One project's operator-set overrides (S15 / CYP-96). All nullable → unset falls back to boot config. */
@Serializable
data class ProjectConfigEntry(
    val repoUrl: String? = null,
    val repoBranch: String? = null,
    /** The API key AT REST. NEVER logged, NEVER serialized to a client — only [Secrets.mask] escapes it. */
    val apiKey: String? = null,
)

/**
 * Operator-settable, per-`projectId` config overrides for repo + API key (S15 / CYP-96; Doc 05 D3).
 * Backs the `/api/config` endpoints and the spawn-/boot-time resolution.
 *
 * **Security (Reviewer merge-gate):** the API key lives at rest only here, in an out-of-repo [file]
 * under the gitRoot working dir (next to `events.db`), written **0600**, **gitignored**, and **never
 * logged**. The key value escapes this class in exactly two ways: [resolvedApiKey] (into the spawn
 * ENV, the same exposure as today's env key) and [Secrets.mask] (`***<last4>`, into [apiKeyView]).
 * There is no path that returns it in full to a client. Resolution is keyed by `projectId` and falls
 * back to the boot config — a project only ever sees its own entry (no cross-project read).
 *
 * MVP = 1 project. The map is keyed by `projectId` so N projects are a data change, not a code change.
 */
class ProjectConfigStore(
    /** Persistence target; `null` → in-memory only (tests / dry boots). */
    private val file: File?,
    private val fallbackRepo: RepoConfig,
    private val secrets: Secrets,
) {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("boot.projectconfig")
    private val entries: MutableMap<String, ProjectConfigEntry> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists() && f.length() > 0) {
            try {
                entries.putAll(CommJson.decodeFromString<Map<String, ProjectConfigEntry>>(f.readText()))
            } catch (e: Exception) {
                // A torn/corrupt store must not brick boot. Start empty. NB: we do NOT back up or log
                // the contents — the file holds a secret, so no key value is ever written elsewhere.
                log.error("corrupt project-config store at {}; starting empty (contents not logged)", f)
            }
        }
    }

    // ---- resolution (spawn / boot read paths) ----

    /** The repo for [projectId]: operator override → boot fallback. Read at clone/worktree time. */
    fun resolvedRepo(projectId: String): RepoConfig = synchronized(lock) {
        val e = entries[projectId]
        if (!e?.repoUrl.isNullOrBlank()) RepoConfig(e!!.repoUrl!!, e.repoBranch?.ifBlank { null } ?: "main") else fallbackRepo
    }

    /** The API key for [projectId]: operator override → env/[Secrets] fallback. Spawn-time ENV only. */
    fun resolvedApiKey(projectId: String): String? = synchronized(lock) {
        entries[projectId]?.apiKey?.takeIf { it.isNotBlank() } ?: secrets.apiKeyFor(projectId)
    }

    // ---- views (GET; the key is only ever masked here) ----

    fun repoView(projectId: String): RepoConfigView = synchronized(lock) {
        val repo = resolvedRepoLocked(projectId)
        if (repo.url.isBlank()) RepoConfigView(configured = false)
        else RepoConfigView(configured = true, url = repo.url, branch = repo.branch)
    }

    fun apiKeyView(projectId: String): ApiKeyView = synchronized(lock) {
        val key = entries[projectId]?.apiKey?.takeIf { it.isNotBlank() } ?: secrets.apiKeyFor(projectId)
        if (key.isNullOrBlank()) ApiKeyView(set = false, masked = null)
        else ApiKeyView(set = true, masked = Secrets.mask(key))
    }

    // ---- mutations (operator PUT) ----

    fun setRepo(projectId: String, url: String, branch: String): RepoConfigView {
        val u = url.trim()
        if (!isPlausibleRepoUrl(u)) throw BadRequestException("invalid repository url", code = "invalid_repo_url")
        val b = branch.trim().ifBlank { "main" }
        synchronized(lock) {
            entries[projectId] = (entries[projectId] ?: ProjectConfigEntry()).copy(repoUrl = u, repoBranch = b)
            persist()
        }
        return RepoConfigView(configured = true, url = u, branch = b)
    }

    fun setApiKey(projectId: String, key: String): ApiKeyView {
        val k = key.trim()
        if (k.isBlank()) throw BadRequestException("invalid api key", code = "invalid_api_key")
        synchronized(lock) {
            entries[projectId] = (entries[projectId] ?: ProjectConfigEntry()).copy(apiKey = k)
            persist()
        }
        return ApiKeyView(set = true, masked = Secrets.mask(k))
    }

    // ---- cascade teardown (S13 / CYP-91 — the config partition of project delete) ----

    /**
     * Drop [projectId]'s config override (repo + API key at rest) — the config partition of the
     * project cascade-delete. Returns `true` if an entry existed. **Strictly scoped to the exact
     * [projectId]** (the map is keyed by it), so deleting project A never removes project B's override
     * (no-cross-project). A blank key removes nothing (fail-closed). Idempotent: absent → no-op.
     */
    fun remove(projectId: String): Boolean = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized false // fail-closed: never an unscoped clear
        val existed = entries.remove(projectId) != null
        if (existed) persist()
        existed
    }

    private fun resolvedRepoLocked(projectId: String): RepoConfig {
        val e = entries[projectId]
        return if (!e?.repoUrl.isNullOrBlank()) RepoConfig(e!!.repoUrl!!, e.repoBranch?.ifBlank { null } ?: "main") else fallbackRepo
    }

    /** Source-of-truth validation (design §6.4): a scheme URL or an scp-like `user@host:path`. */
    private fun isPlausibleRepoUrl(url: String): Boolean =
        url.isNotBlank() && (url.contains("://") || SCP_LIKE.matches(url))

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { parent ->
            parent.mkdirs()
            // 0700 dir — not world-traversable / group-rwx (mkdirs default 0775 would let a local
            // non-owner list the dir). Reviewer merge-gate hardening (CYP-96).
            restrictDirToOwner(parent)
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        // 0600-FIRST: create the empty tmp and lock it down BEFORE the plaintext key is written, so the
        // secret never lands in a world-readable file even briefly (writeText keeps an existing file's perms).
        tmp.delete()
        tmp.createNewFile()
        restrictToOwner(tmp)
        tmp.writeText(CommJson.encodeToString(entries.toMap()))
        restrictToOwner(tmp) // re-assert (no-op if unchanged); defensive
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(f)
    }

    /** Owner-only rw (0600) — the file holds a secret. POSIX where available; best-effort otherwise. */
    private fun restrictToOwner(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }.onFailure {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    /** Owner-only rwx (0700) for the secret's directory — not world/group traversable. */
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

    private companion object {
        val SCP_LIKE = Regex("^[^@\\s]+@[^:\\s]+:.+$")
    }
}
