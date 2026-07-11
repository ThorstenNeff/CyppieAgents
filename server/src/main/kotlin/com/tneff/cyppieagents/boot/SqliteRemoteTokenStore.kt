package com.tneff.cyppieagents.boot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [RemoteTokenStore], **secret-at-rest**. The runtime-minted remote-agent
 * bearer tokens (`agentId → token`) live in a local SQLite DB whose files are restricted to owner-only
 * (`0600`) — parity with [FileRemoteTokenStore]'s 0600 hardening (the token is HOME-local, never on the wire,
 * never logged). Encryption-at-rest via the CYP-220 Tink `SecretCipher` is a later slice (S-B / SecretStore);
 * for now a local Sqlite file at 0600 is behavior-parity with the File impl. Single held connection + WAL,
 * `synchronized` (non-`suspend` interface).
 */
class SqliteRemoteTokenStore(private val dbPath: Path) : RemoteTokenStore, AutoCloseable {
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
        restrictSecretFiles() // the DB + WAL/SHM sidecars carry the token → owner-only
    }

    override fun all(): Map<String, String> = synchronized(lock) {
        conn.prepareStatement("SELECT agent_id, token FROM remote_token").use { ps ->
            ps.executeQuery().use { rs ->
                val out = LinkedHashMap<String, String>()
                while (rs.next()) out[rs.getString(1)] = rs.getString(2)
                out
            }
        }
    }

    override fun put(agentId: String, token: String) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO remote_token(agent_id, token) VALUES(?, ?) " +
                "ON CONFLICT(agent_id) DO UPDATE SET token=excluded.token",
        ).use { ps ->
            ps.setString(1, agentId); ps.setString(2, token); ps.executeUpdate()
        }
        restrictSecretFiles()
        Unit
    }

    override fun remove(agentId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM remote_token WHERE agent_id=?").use { ps ->
            ps.setString(1, agentId); ps.executeUpdate()
        }
        Unit
    }

    /** Restrict the DB + WAL/SHM files to `rw-------` (owner-only), best-effort on POSIX FS (no-op elsewhere). */
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
            CREATE TABLE IF NOT EXISTS remote_token (
              agent_id TEXT PRIMARY KEY,
              token TEXT NOT NULL
            )
        """.trimIndent()
    }
}
