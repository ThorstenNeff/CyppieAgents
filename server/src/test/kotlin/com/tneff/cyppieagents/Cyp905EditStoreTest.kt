package com.tneff.cyppieagents

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.SqliteMessageStore
import com.tneff.cyppieagents.model.Message
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-905 — [com.tneff.cyppieagents.comm.MessageStore] edit-seam teeth: `update` replaces the body in place and
 * records the out-of-band `editedAt` marker; `editedAtOf` recovers it (batched). The durable prod store
 * ([SqliteMessageStore]) must survive a restart AND migrate an older-schema DB (no id / edited_at columns).
 */
class Cyp905EditStoreTest {
    private fun msg(id: String, body: String) =
        Message(id = id, channelId = "c1", from = "backend", body = body, ts = 100L)

    // In-memory: update replaces body (seq/from/ts preserved) + editedAtOf recovers the marker; unedited = absent.
    @Test fun inMemoryUpdateAndEditedAtOf() {
        val store = InMemoryMessageStore()
        val a = store.append(msg("m1", "one"))
        store.append(msg("m2", "two"))

        val updated = store.update("m1", "one-edited", editedAt = 555L)
        assertNotNull(updated)
        assertEquals("one-edited", updated.body)
        assertEquals(a.seq, updated.seq, "seq preserved on edit")
        assertEquals(mapOf("m1" to 555L), store.editedAtOf(listOf("m1", "m2")), "only m1 has a marker")
        assertNull(store.update("nope", "x", 1L), "absent id → null")
    }

    // SQLite: an edit survives a full close/reopen — the edited body AND the editedAt marker are on disk.
    // Mutant: update writes body but not edited_at (or a side-map instead of the column) → marker null after reopen → reds.
    @Test fun sqliteRestartSurvivesBodyAndMarker() {
        val dir = Files.createTempDirectory("cyp905-sqlite")
        val dbPath = dir.resolve("messages.db")
        SqliteMessageStore(dbPath).use { s ->
            s.append(msg("m1", "original"))
            s.update("m1", "edited-body", editedAt = 42_000L)
        }
        // reopen over the same file (the restart)
        SqliteMessageStore(dbPath).use { s ->
            val stored = s.byChannel("c1").first { it.id == "m1" }
            assertEquals("edited-body", stored.body, "edited body survived restart")
            assertEquals(mapOf("m1" to 42_000L), s.editedAtOf(listOf("m1")), "editedAt marker survived restart")
        }
        cleanup(dir)
    }

    // SQLite migration: an older-schema DB (no id / edited_at columns) is ALTERed + id backfilled on open, so an
    // edit by message id works against a pre-CYP-905 row.
    // Mutant: skip migrate()/backfill → update can't locate the pre-existing row (no id column) → null → reds.
    @Test fun sqliteMigratesOldSchemaAndBackfillsId() {
        val dir = Files.createTempDirectory("cyp905-migrate")
        val dbPath = dir.resolve("legacy.db")
        val json = CommJson.encodeToString(Message.serializer(), msg("legacy-1", "old row"))
        // hand-build the PRE-CYP-905 schema (no id / edited_at) and insert one row
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, channel_id TEXT NOT NULL, ts INTEGER NOT NULL, message_json TEXT NOT NULL)",
                )
            }
            c.prepareStatement("INSERT INTO messages(channel_id, ts, message_json) VALUES(?,?,?)").use { ps ->
                ps.setString(1, "c1"); ps.setLong(2, 100L); ps.setString(3, json); ps.executeUpdate()
            }
        }
        // open through SqliteMessageStore → migrate() ALTERs id/edited_at in + backfills id from message_json
        SqliteMessageStore(dbPath).use { s ->
            val updated = s.update("legacy-1", "old row edited", editedAt = 7L)
            assertNotNull(updated, "the backfilled id lets the edit locate the pre-existing row")
            assertEquals("old row edited", s.byChannel("c1").first { it.id == "legacy-1" }.body)
            assertEquals(mapOf("legacy-1" to 7L), s.editedAtOf(listOf("legacy-1")))
        }
        cleanup(dir)
    }

    private fun cleanup(dir: Path) {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }
}
