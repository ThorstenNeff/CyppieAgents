package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentAvatar
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-415 (S-F / D2) — [SqliteAgentOverrideStore]: the merge/avatar semantics, restart durability, and the
 * **first-boot preserve import** — the money tooth of this slice.
 *
 * **[deploy_operator_overrides_survive_file_to_sqlite_switch]** is the preserve tooth ([[default-agents-no-reset]]):
 * pre-populate an old `agent-overrides.json` with the dev2 `#B5419A` color + an avatar ref, boot the SqliteX
 * store pointed at it → the customizations are carried into the DB, byte-for-byte. Non-vacuity: drop the
 * `importLegacy` call (the mutation) → the imported override is gone → this test reds. The empty-table guard
 * makes it idempotent (a second boot must NOT re-import or clobber a later edit).
 */
class Cyp415SqliteAgentOverrideStoreTest {

    private val dir = Files.createTempDirectory("cyp415-ov")
    private val db = dir.resolve("agent-overrides.db")
    private val legacy = dir.resolve("agent-overrides.json")
    private var store: SqliteAgentOverrideStore? = null

    @AfterTest fun tearDown() {
        store?.close()
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun open(legacyJson: java.nio.file.Path? = null) =
        SqliteAgentOverrideStore(db, legacyJson).also { store = it }

    @Test
    fun deploy_operator_overrides_survive_file_to_sqlite_switch() {
        // A live deploy's agent-overrides.json: dev2 has an operator-set name, the #B5419A color, and an avatar.
        val legacyContent = mapOf(
            "default" to mapOf(
                "dev2" to AgentOverride(name = "Dev Two", color = "#B5419A", avatar = AgentAvatar.Preset("bottts", "dev2-seed")),
            ),
        )
        Files.writeString(legacy, CommJson.encodeToString(legacyContent))

        val imported = open(legacy).overrideOf("default", "dev2")
        assertEquals("#B5419A", imported?.color, "the dev2 color survived the File→SQLite switch (preserve)")
        assertEquals("Dev Two", imported?.name)
        assertEquals(AgentAvatar.Preset("bottts", "dev2-seed"), imported?.avatar, "the avatar ref survived too")

        // Idempotent: a second boot over the SAME (now non-empty) db does NOT re-import / clobber a later edit.
        store!!.close()
        val second = open(legacy)
        second.setAvatar("default", "dev2", null) // an operator clears the avatar after the migration
        store!!.close()
        val third = open(legacy)
        assertNull(third.overrideOf("default", "dev2")?.avatar, "the post-migration edit is not overwritten by a re-import")
        assertEquals("#B5419A", third.overrideOf("default", "dev2")?.color)
    }

    @Test
    fun put_preserves_blank_fields_and_setAvatar_clears() {
        val s = open()
        s.put("default", "dev2", name = "A", color = "#B5419A", persona = null, launch = null)
        // A blank/null edit PRESERVES the stored value (mergeStringFields).
        val merged = s.put("default", "dev2", name = "", color = null, persona = "p", launch = null)
        assertEquals("A", merged.name, "blank name preserved")
        assertEquals("#B5419A", merged.color, "null color preserved")
        assertEquals("p", merged.persona)
        // setAvatar(null) genuinely CLEARS.
        s.setAvatar("default", "dev2", AgentAvatar.Preset("bottts", "x"))
        assertEquals(null, s.setAvatar("default", "dev2", null).avatar)
    }

    @Test
    fun removeProject_is_scoped_and_removeAgent_reports() {
        val s = open()
        s.put("default", "a", "n", null, null, null)
        s.put("other", "b", "n", null, null, null)
        assertTrue(s.removeAgent("default", "a"))
        assertEquals(0, s.removeProject(""), "blank project → never an unscoped clear")
        assertEquals(1, s.removeProject("other"))
        assertEquals(emptyMap(), s.allFor("other"))
    }
}
