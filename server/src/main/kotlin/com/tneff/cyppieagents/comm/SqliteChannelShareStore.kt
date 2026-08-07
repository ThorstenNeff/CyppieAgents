package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [ChannelShareStore] (one row per `channelId`, whole [ChannelShareRecord]
 * as `record_json`). Reuses [computeShareRecord]/[emptyShareRecord] (empty-grantees → revoke) so File/Sqlite/Pg
 * cannot drift. First-boot import of the old `channel-shares.json` (empty-table guard) preserves live shares.
 * Single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteChannelShareStore(
    dbPath: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    legacyJson: Path? = null,
) : ChannelShareStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("comm.channelshare.sqlite")
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
    }

    private fun isEmpty(): Boolean =
        conn.prepareStatement("SELECT 1 FROM channel_share LIMIT 1").use { it.executeQuery().use { rs -> !rs.next() } }

    private fun importLegacy(legacyJson: Path) {
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, ChannelShareRecord>>(Files.readString(legacyJson))
            for ((_, rec) in decoded) upsert(rec)
            log.info("imported {} legacy channel-share rows from {}", decoded.size, legacyJson)
        }.onFailure { log.warn("channel-share legacy import failed ({}) — starting empty", it.message) }
    }

    override fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord = synchronized(lock) {
        val now = clock()
        val rec = computeShareRecord(channelId, ownerProjectId, sharedWith, now)
        if (rec == null) {
            conn.prepareStatement("DELETE FROM channel_share WHERE channel_id=?").use { it.setString(1, channelId); it.executeUpdate() }
            emptyShareRecord(channelId, ownerProjectId, now)
        } else {
            upsert(rec)
            rec
        }
    }

    override fun revoke(channelId: String): Boolean = synchronized(lock) {
        conn.prepareStatement("DELETE FROM channel_share WHERE channel_id=?").use { ps ->
            ps.setString(1, channelId); ps.executeUpdate() > 0
        }
    }

    override fun record(channelId: String): ChannelShareRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT record_json FROM channel_share WHERE channel_id=?").use { ps ->
            ps.setString(1, channelId)
            ps.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
        }
    }

    override fun sharedInboundChannelIds(activeProjectId: String): Set<String> = synchronized(lock) {
        if (activeProjectId.isBlank()) return@synchronized emptySet() // fail-closed: blank active reaches nothing
        conn.prepareStatement("SELECT record_json FROM channel_share").use { ps ->
            ps.executeQuery().use { rs ->
                val out = HashSet<String>()
                while (rs.next()) {
                    val rec = decode(rs.getString(1))
                    if (activeProjectId in rec.sharedWith) out.add(rec.channelId)
                }
                out
            }
        }
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized 0 // fail-closed: never an unscoped purge
        mutateBy { purgeProjectFromShare(it, projectId) }
    }

    override fun sweepOrphans(liveProjectIds: Set<String>, federationEnabled: Boolean): Int = synchronized(lock) {
        mutateBy { sweepOrphanFromShare(it, liveProjectIds, federationEnabled) }
    }

    /** Apply a per-record [decide] across the whole gate (caller holds [lock]); commit drops/narrows to the db. */
    private fun mutateBy(decide: (ChannelShareRecord) -> SharePurge): Int {
        val all = conn.prepareStatement("SELECT record_json FROM channel_share").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(decode(rs.getString(1))) } }
        }
        var touched = 0
        for (rec in all) {
            when (val r = decide(rec)) {
                SharePurge.Drop -> { deleteRow(rec.channelId); touched++ }
                is SharePurge.Narrow -> { upsert(r.record); touched++ }
                SharePurge.Untouched -> {}
            }
        }
        return touched
    }

    private fun deleteRow(channelId: String) {
        conn.prepareStatement("DELETE FROM channel_share WHERE channel_id=?").use { it.setString(1, channelId); it.executeUpdate() }
    }

    private fun decode(json: String) = CommJson.decodeFromString(ChannelShareRecord.serializer(), json)

    private fun upsert(rec: ChannelShareRecord) {
        conn.prepareStatement(
            "INSERT INTO channel_share(channel_id, record_json) VALUES(?, ?) " +
                "ON CONFLICT(channel_id) DO UPDATE SET record_json=excluded.record_json",
        ).use { ps ->
            ps.setString(1, rec.channelId)
            ps.setString(2, CommJson.encodeToString(ChannelShareRecord.serializer(), rec))
            ps.executeUpdate()
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS channel_share (
              channel_id TEXT PRIMARY KEY,
              record_json TEXT NOT NULL
            )
        """.trimIndent()
    }
}
