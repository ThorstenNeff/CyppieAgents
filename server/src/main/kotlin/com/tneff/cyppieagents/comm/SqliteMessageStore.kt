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
            migrate(st) // CYP-905: add id / edited_at columns to a pre-existing (older-schema) DB
            INDEXES.forEach { st.executeUpdate(it) }
        }
    }

    /**
     * CYP-905 — additive migration for an already-deployed DB whose `messages` table predates the `id` /
     * `edited_at` columns. `CREATE TABLE IF NOT EXISTS` above is a no-op on the old schema, so ALTER the missing
     * columns in and backfill `id` from `message_json` (one-time). A fresh DB already has both via [CREATE_TABLE],
     * so [existingColumns] finds them and this does nothing.
     */
    private fun migrate(st: java.sql.Statement) {
        val cols = existingColumns()
        if ("id" !in cols) {
            st.executeUpdate("ALTER TABLE messages ADD COLUMN id TEXT")
            backfillIds()
        }
        if ("edited_at" !in cols) {
            st.executeUpdate("ALTER TABLE messages ADD COLUMN edited_at INTEGER")
        }
    }

    private fun existingColumns(): Set<String> =
        conn.prepareStatement("PRAGMA table_info(messages)").use { ps ->
            ps.executeQuery().use { rs ->
                val out = HashSet<String>()
                while (rs.next()) out.add(rs.getString("name"))
                out
            }
        }

    /** CYP-905 — one-time backfill of the new `id` column from each pre-migration row's `message_json`. */
    private fun backfillIds() {
        val rows = conn.prepareStatement("SELECT seq, message_json FROM messages WHERE id IS NULL").use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<Pair<Long, String>>()
                while (rs.next()) out.add(rs.getLong(1) to CommJson.decodeFromString(Message.serializer(), rs.getString(2)).id)
                out
            }
        }
        conn.prepareStatement("UPDATE messages SET id = ? WHERE seq = ?").use { ps ->
            for ((seq, mid) in rows) { ps.setString(1, mid); ps.setLong(2, seq); ps.addBatch() }
            ps.executeBatch()
        }
    }

    override fun append(message: Message): Message = synchronized(lock) {
        conn.prepareStatement("INSERT INTO messages(channel_id, ts, message_json, id) VALUES(?, ?, ?, ?)").use { ps ->
            ps.setString(1, message.channelId)
            ps.setLong(2, message.ts)
            // message_json is stored with seq=0 (the constructed value); the authoritative seq is the
            // AUTOINCREMENT column, injected on read (byChannel) — so message_json never drifts from it.
            ps.setString(3, CommJson.encodeToString(Message.serializer(), message))
            ps.setString(4, message.id) // CYP-905: addressable id column for update / editedAtOf
            ps.executeUpdate()
        }
        // CYP-705: the AUTOINCREMENT `seq` IS the rowid → last_insert_rowid() returns the just-assigned value.
        val seq = conn.prepareStatement("SELECT last_insert_rowid()").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        message.copy(seq = seq)
    }

    override fun update(id: String, newBody: String, editedAt: Long): Message? = synchronized(lock) {
        // Locate the row by the addressable id column, rewrite the body inside message_json (kept seq=0 by the
        // append convention), and set the out-of-band edited_at marker. Returns null if the id is absent.
        val existing = conn.prepareStatement("SELECT seq, message_json FROM messages WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        } ?: return@synchronized null
        val (seq, json) = existing
        val updated = CommJson.decodeFromString(Message.serializer(), json).copy(body = newBody)
        conn.prepareStatement("UPDATE messages SET message_json = ?, edited_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, CommJson.encodeToString(Message.serializer(), updated))
            ps.setLong(2, editedAt)
            ps.setString(3, id)
            ps.executeUpdate()
        }
        updated.copy(seq = seq) // CYP-705: re-inject the authoritative seq (message_json carries seq=0)
    }

    override fun editedAtOf(ids: Collection<String>): Map<String, Long> = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        conn.prepareStatement("SELECT id, edited_at FROM messages WHERE id IN ($placeholders) AND edited_at IS NOT NULL").use { ps ->
            var i = 1
            for (id in ids) ps.setString(i++, id)
            ps.executeQuery().use { rs ->
                val out = HashMap<String, Long>()
                while (rs.next()) out[rs.getString(1)] = rs.getLong(2)
                out
            }
        }
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
              message_json TEXT NOT NULL,
              id TEXT,
              edited_at INTEGER
            )
        """.trimIndent()
        // CYP-905: idx_messages_id speeds the by-id update / editedAtOf lookups (created after migrate() so it
        // also covers a just-ALTERed old DB).
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_messages_channel_ts ON messages(channel_id, ts)",
            "CREATE INDEX IF NOT EXISTS idx_messages_id ON messages(id)",
        )
    }
}
