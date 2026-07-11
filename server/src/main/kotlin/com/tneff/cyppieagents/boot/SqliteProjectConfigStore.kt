package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.BadRequestException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [ProjectConfigStore], **secret-at-rest** (the API key). One row per
 * `projectId` (whole [ProjectConfigEntry] as `entry_json`); the DB files are restricted to owner-only (`0600`)
 * — parity with [FileProjectConfigStore]'s hardening. Encryption-at-rest via the CYP-220 Tink `SecretCipher` is
 * a later slice (S-B); a local Sqlite file at 0600 is behavior-parity with the File impl. Reuses the shared
 * [isPlausibleRepoUrl]/[isPlausibleApiKey]/[Secrets.mask] so File/Sqlite/Pg cannot drift.
 *
 * **First-boot import (preserve-safe):** if [legacyJson] exists AND the table is empty, the old
 * `project-config.json` is imported ONCE — so a deploy's **GUI-set API key** is NOT dropped by the switch
 * (avoids a "no-spawn" regression). Deterministic + idempotent (empty-table guard).
 */
class SqliteProjectConfigStore(
    private val dbPath: Path,
    private val fallbackRepo: RepoConfig,
    private val secrets: Secrets,
    legacyJson: Path? = null,
) : ProjectConfigStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("boot.projectconfig.sqlite")
    private val lock = Any()
    private val conn: Connection

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
        if (legacyJson != null && Files.exists(legacyJson) && isEmpty()) importLegacy(legacyJson)
        restrictSecretFiles() // the entry_json carries the plaintext API key → owner-only
    }

    private fun isEmpty(): Boolean =
        conn.prepareStatement("SELECT 1 FROM project_config LIMIT 1").use { it.executeQuery().use { rs -> !rs.next() } }

    private fun importLegacy(legacyJson: Path) {
        runCatching {
            // NB: intentionally do NOT log the decoded contents — they hold the secret (mirrors the File impl).
            val decoded = CommJson.decodeFromString<Map<String, ProjectConfigEntry>>(Files.readString(legacyJson))
            for ((pid, entry) in decoded) upsert(pid, entry)
            log.info("imported {} legacy project-config rows from {} (contents not logged)", decoded.size, legacyJson)
        }.onFailure { log.warn("project-config legacy import failed — starting empty (contents not logged)") }
    }

    override fun resolvedRepo(projectId: String): RepoConfig = synchronized(lock) {
        val e = read(projectId)
        if (!e?.repoUrl.isNullOrBlank()) RepoConfig(e!!.repoUrl!!, e.repoBranch?.ifBlank { null } ?: "main") else fallbackRepo
    }

    override fun resolvedApiKey(projectId: String): String? = synchronized(lock) {
        read(projectId)?.apiKey?.takeIf { it.isNotBlank() } ?: secrets.apiKeyFor(projectId)
    }

    override fun repoView(projectId: String): RepoConfigView = synchronized(lock) {
        val repo = resolvedRepoLocked(projectId)
        if (repo.url.isBlank()) RepoConfigView(configured = false)
        else RepoConfigView(configured = true, url = repo.url, branch = repo.branch)
    }

    override fun apiKeyView(projectId: String): ApiKeyView = synchronized(lock) {
        val key = read(projectId)?.apiKey?.takeIf { it.isNotBlank() } ?: secrets.apiKeyFor(projectId)
        if (key.isNullOrBlank()) ApiKeyView(set = false, masked = null)
        else ApiKeyView(set = true, masked = Secrets.mask(key))
    }

    override fun setRepo(projectId: String, url: String, branch: String): RepoConfigView {
        val u = url.trim()
        if (!isPlausibleRepoUrl(u)) throw BadRequestException("invalid repository url", code = "invalid_repo_url")
        val b = branch.trim().ifBlank { "main" }
        synchronized(lock) { upsert(projectId, (read(projectId) ?: ProjectConfigEntry()).copy(repoUrl = u, repoBranch = b)) }
        return RepoConfigView(configured = true, url = u, branch = b)
    }

    override fun setApiKey(projectId: String, key: String): ApiKeyView {
        val k = key.trim()
        if (!isPlausibleApiKey(k)) throw BadRequestException("invalid api key", code = "invalid_api_key")
        synchronized(lock) { upsert(projectId, (read(projectId) ?: ProjectConfigEntry()).copy(apiKey = k)) }
        return ApiKeyView(set = true, masked = Secrets.mask(k))
    }

    override fun remove(projectId: String): Boolean = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized false // fail-closed: never an unscoped clear
        conn.prepareStatement("DELETE FROM project_config WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); ps.executeUpdate() > 0
        }
    }

    private fun resolvedRepoLocked(projectId: String): RepoConfig {
        val e = read(projectId)
        return if (!e?.repoUrl.isNullOrBlank()) RepoConfig(e!!.repoUrl!!, e.repoBranch?.ifBlank { null } ?: "main") else fallbackRepo
    }

    private fun read(projectId: String): ProjectConfigEntry? =
        conn.prepareStatement("SELECT entry_json FROM project_config WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> if (rs.next()) CommJson.decodeFromString(ProjectConfigEntry.serializer(), rs.getString(1)) else null }
        }

    private fun upsert(projectId: String, entry: ProjectConfigEntry) {
        conn.prepareStatement(
            "INSERT INTO project_config(project_id, entry_json) VALUES(?, ?) " +
                "ON CONFLICT(project_id) DO UPDATE SET entry_json=excluded.entry_json",
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, CommJson.encodeToString(ProjectConfigEntry.serializer(), entry))
            ps.executeUpdate()
        }
        restrictSecretFiles()
    }

    /** Restrict the DB + WAL/SHM files to `rw-------`, best-effort on POSIX FS (no-op elsewhere). */
    private fun restrictSecretFiles() {
        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        for (suffix in listOf("", "-wal", "-shm")) {
            val p = Path.of("$dbPath$suffix")
            if (Files.exists(p)) runCatching { Files.setPosixFilePermissions(p, owner) }
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS project_config (
              project_id TEXT PRIMARY KEY,
              entry_json TEXT NOT NULL
            )
        """.trimIndent()
    }
}
