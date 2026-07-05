package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S4 — the **Postgres** [ChannelShareStore] impl. Behaviour-identical to
 * [FileChannelShareStore]: the same "empty grantees → revoke" rule (a share to nobody, or only to the owner's
 * own project, is a no-op hole), the same per-`channelId` granularity (a share never widens to the owner's
 * other channels), and the same fail-closed [sharedInboundChannelIds] (a blank active project reaches nothing).
 * **Non-secret** (a cross-project authorization gate, no credentials), so no
 * [com.tneff.cyppieagents.crypto.SecretCipher] — the whole [ChannelShareRecord] is stored as its canonical
 * [CommJson] form (one text column), preserving the `sharedWith`/`consents` sets exactly.
 */
class PgChannelShareStore(
    private val dataSource: DataSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    migrate: Boolean = true,
) : ChannelShareStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/channelshare") }

    override fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord {
        // A share to nobody (or only to the owner's own project) is a no-op hole — treat empty as revoke.
        val grantees = sharedWith.filter { it.isNotBlank() && it != ownerProjectId }.toSet()
        if (grantees.isEmpty()) {
            deleteRecord(channelId)
            return ChannelShareRecord(channelId, ownerProjectId, emptySet(), setOf(ownerProjectId), clock())
        }
        val rec = ChannelShareRecord(channelId, ownerProjectId, grantees, consents = setOf(ownerProjectId), sharedAt = clock())
        upsert(rec)
        return rec
    }

    override fun revoke(channelId: String): Boolean = deleteRecord(channelId)

    override fun record(channelId: String): ChannelShareRecord? = tx { c ->
        c.prepareStatement("SELECT record_json FROM channel_share WHERE channel_id = ?").use { st ->
            st.setString(1, channelId)
            st.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
        }
    }

    override fun sharedInboundChannelIds(activeProjectId: String): Set<String> {
        if (activeProjectId.isBlank()) return emptySet() // fail-closed: blank active reaches nothing
        return allRecords().filter { activeProjectId in it.sharedWith }.map { it.channelId }.toSet()
    }

    // ---- MigrationTarget (canonical rows: [channel_id, owner_project_id, record_json]) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT channel_id, owner_project_id, record_json FROM channel_share ORDER BY channel_id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1), rs.getString(2), rs.getString(3)))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) = tx { c ->
        c.prepareStatement("DELETE FROM channel_share").use { it.executeUpdate() }
        for (row in rows) {
            val f = MigrationRowCodec.decode(row) // [channelId, ownerProjectId, recordJson]
            c.prepareStatement("INSERT INTO channel_share (channel_id, owner_project_id, record_json) VALUES (?, ?, ?)")
                .use { it.setString(1, f[0]); it.setString(2, f[1]); it.setString(3, f[2]); it.executeUpdate() }
        }
        Unit
    }

    private fun allRecords(): List<ChannelShareRecord> = tx { c ->
        c.prepareStatement("SELECT record_json FROM channel_share").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(decode(rs.getString(1))) } }
        }
    }

    private fun upsert(rec: ChannelShareRecord) = tx { c ->
        c.prepareStatement(
            "INSERT INTO channel_share (channel_id, owner_project_id, record_json) VALUES (?, ?, ?) " +
                "ON CONFLICT (channel_id) DO UPDATE SET owner_project_id = EXCLUDED.owner_project_id, record_json = EXCLUDED.record_json",
        ).use { it.setString(1, rec.channelId); it.setString(2, rec.ownerProjectId); it.setString(3, CommJson.encodeToString(rec)); it.executeUpdate() }
        Unit
    }

    private fun deleteRecord(channelId: String): Boolean = tx { c ->
        c.prepareStatement("DELETE FROM channel_share WHERE channel_id = ?")
            .use { it.setString(1, channelId); it.executeUpdate() } > 0
    }

    private fun decode(json: String): ChannelShareRecord = CommJson.decodeFromString(json)

    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        val prev = c.autoCommit
        c.autoCommit = false
        try {
            val r = block(c); c.commit(); r
        } catch (e: Throwable) {
            runCatching { c.rollback() }; throw e
        } finally {
            runCatching { c.autoCommit = prev }
        }
    }
}
