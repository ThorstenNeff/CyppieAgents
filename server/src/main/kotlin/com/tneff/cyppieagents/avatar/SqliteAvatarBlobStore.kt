package com.tneff.cyppieagents.avatar

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [AvatarBlobStore] (one row per `(projectId, agentId)`, the re-encoded PNG
 * in a real `BLOB` column — unlike the JSON stores). [safeSegment] path-safety is kept as defence-in-depth even
 * though SQL binds can't traverse a directory. First-boot import walks the old `<root>/<projectId>/<agentId>.png`
 * layout ONCE (empty-table guard) so operator avatar UPLOADS (the dev2 bottts bytes, [[default-agents-no-reset]])
 * survive the switch. Single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteAvatarBlobStore(dbPath: Path, legacyDir: Path? = null) : AvatarBlobStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("avatar.blob.sqlite")
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
        if (legacyDir != null && Files.isDirectory(legacyDir) && isEmpty()) importLegacy(legacyDir)
    }

    private fun isEmpty(): Boolean =
        conn.prepareStatement("SELECT 1 FROM avatar_blob LIMIT 1").use { it.executeQuery().use { rs -> !rs.next() } }

    private fun importLegacy(legacyDir: Path) {
        runCatching {
            var n = 0
            Files.newDirectoryStream(legacyDir).use { projDirs ->
                for (projDir in projDirs) {
                    if (!Files.isDirectory(projDir)) continue
                    val pid = projDir.fileName.toString()
                    Files.newDirectoryStream(projDir, "*.png").use { pngs ->
                        for (png in pngs) {
                            val agentId = png.fileName.toString().removeSuffix(".png")
                            if (safeSegment(pid) != null && safeSegment(agentId) != null) {
                                upsert(pid, agentId, Files.readAllBytes(png)); n++
                            }
                        }
                    }
                }
            }
            log.info("imported {} legacy avatar blobs from {}", n, legacyDir)
        }.onFailure { log.warn("avatar legacy import failed ({}) — starting empty", it.message) }
    }

    override fun write(projectId: String, agentId: String, png: ByteArray): Boolean = synchronized(lock) {
        if (safeSegment(projectId) == null || safeSegment(agentId) == null) return@synchronized false
        upsert(projectId, agentId, png)
        true
    }

    override fun read(projectId: String, agentId: String): ByteArray? = synchronized(lock) {
        if (safeSegment(projectId) == null || safeSegment(agentId) == null) return@synchronized null
        conn.prepareStatement("SELECT png FROM avatar_blob WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    }

    override fun delete(projectId: String, agentId: String): Boolean = synchronized(lock) {
        if (safeSegment(projectId) == null || safeSegment(agentId) == null) return@synchronized false
        conn.prepareStatement("DELETE FROM avatar_blob WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate() > 0
        }
    }

    override fun deleteByProject(projectId: String): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM avatar_blob WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); ps.executeUpdate()
        }
    }

    private fun upsert(projectId: String, agentId: String, png: ByteArray) {
        conn.prepareStatement(
            "INSERT INTO avatar_blob(project_id, agent_id, png) VALUES(?, ?, ?) " +
                "ON CONFLICT(project_id, agent_id) DO UPDATE SET png=excluded.png",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.setBytes(3, png)
            ps.executeUpdate()
        }
    }

    /** Accept only a single safe path segment; reject anything that could escape a directory (parity w/ File impl). */
    private fun safeSegment(s: String): String? =
        if (s.isNotEmpty() && s != "." && s != ".." && s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && "/" !in s && "\\" !in s) s
        else null

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS avatar_blob (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              png BLOB NOT NULL,
              PRIMARY KEY (project_id, agent_id)
            )
        """.trimIndent()
    }
}
