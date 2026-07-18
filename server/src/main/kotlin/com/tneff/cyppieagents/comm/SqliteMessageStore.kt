package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Message
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D4) — the **embedded-SQLite** [MessageStore]: the local hub's messages survive a restart
 * (closing the D4 gap — production hardcoded [InMemoryMessageStore], so messages were lost on every boot).
 * One local `Sqlite*` impl behind the same store seam as the (dark, cloud) Pg vertical; direct-wired in
 * `BootOrchestrator`, mirroring [com.tneff.cyppieagents.events.SqliteEventSink]/`SqliteAgentEventStore`/
 * `SqliteRoleStore` (single held connection + WAL, `synchronized` — the interface is non-`suspend`).
 *
 * The whole [Message] is stored as `CommJson` in `message_json`; `channel_id` and `ts` are duplicated into
 * indexed columns so the channel/inbox queries don't decode every row, and the AUTOINCREMENT `seq` preserves
 * append order (the [InMemoryMessageStore] contract: filtered results in insertion order).
 */
class SqliteMessageStore(dbPath: Path) : MessageStore, AutoCloseable {
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
            INDEXES.forEach { st.executeUpdate(it) }
        }
    }

    override fun append(message: Message): Message = synchronized(lock) {
        conn.prepareStatement("INSERT INTO messages(channel_id, ts, message_json) VALUES(?, ?, ?)").use { ps ->
            ps.setString(1, message.channelId)
            ps.setLong(2, message.ts)
            // message_json is stored with seq=0 (the constructed value); the authoritative seq is the
            // AUTOINCREMENT column, injected on read (byChannel) — so message_json never drifts from it.
            ps.setString(3, CommJson.encodeToString(Message.serializer(), message))
            ps.executeUpdate()
        }
        // CYP-705: the AUTOINCREMENT `seq` IS the rowid → last_insert_rowid() returns the just-assigned value.
        val seq = conn.prepareStatement("SELECT last_insert_rowid()").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        message.copy(seq = seq)
    }

    override fun byChannel(channelId: String, since: Long?): List<Message> = synchronized(lock) {
        val sql = buildString {
            append("SELECT seq, message_json FROM messages WHERE channel_id = ?")
            if (since != null) append(" AND ts > ?")
            append(" ORDER BY seq")
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, channelId)
            if (since != null) ps.setLong(2, since)
            ps.readMessages()
        }
    }

    override fun acrossChannels(channelIds: Collection<String>, since: Long?): List<Message> = synchronized(lock) {
        if (channelIds.isEmpty()) return@synchronized emptyList()
        val placeholders = channelIds.joinToString(",") { "?" }
        val sql = buildString {
            append("SELECT seq, message_json FROM messages WHERE channel_id IN (").append(placeholders).append(")")
            if (since != null) append(" AND ts > ?")
            append(" ORDER BY seq")
        }
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            for (id in channelIds) ps.setString(i++, id)
            if (since != null) ps.setLong(i, since)
            ps.readMessages()
        }
    }

    private fun java.sql.PreparedStatement.readMessages(): List<Message> {
        executeQuery().use { rs ->
            val out = ArrayList<Message>()
            // CYP-705: inject the authoritative `seq` column into the decoded Message (message_json carries seq=0).
            while (rs.next()) out.add(CommJson.decodeFromString(Message.serializer(), rs.getString(2)).copy(seq = rs.getLong(1)))
            return out
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS messages (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              channel_id TEXT NOT NULL,
              ts INTEGER NOT NULL,
              message_json TEXT NOT NULL
            )
        """.trimIndent()
        val INDEXES = listOf("CREATE INDEX IF NOT EXISTS idx_messages_channel_ts ON messages(channel_id, ts)")
    }
}
