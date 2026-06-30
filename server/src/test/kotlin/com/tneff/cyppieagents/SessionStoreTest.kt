package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.JsonFileSessionStore
import com.tneff.cyppieagents.connector.InMemorySessionStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-167 — durable session-store invariants. The whole feature hinges on the binding surviving a
 * restart (R1) and the key being projectId-scoped (M10); a corrupt file must never brick the boot (M8).
 */
class SessionStoreTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("session-store").toFile()
        return File(dir, "session-store.json")
    }

    /**
     * R1 (High) — Reload-Survival: persist with one store, drop the in-mem index, build a FRESH
     * [JsonFileSessionStore] from the SAME file → the lookup MUST still hit. This is the test a boot-nonce
     * / unstable-key regression fails while passing every in-process check: without it, resume silently
     * never fires (every boot starts fresh + orphan entries pile up).
     *
     * Mutation: make the `init { … loadAll(…) }` restore a no-op → [find] returns null → this reds.
     */
    @Test
    fun r1_bindingSurvivesAFreshStoreFromTheSameFile() {
        val file = tempFile()
        JsonFileSessionStore(file).upsert("default", "backend", "sess-123", now = 1000L)

        // A brand-new store instance (simulating the next boot) reading the same file.
        val reloaded = JsonFileSessionStore(file)
        val found = reloaded.find("default", "backend")

        assertEquals("sess-123", found?.sessionId, "the binding must survive a restart (fresh store, same file)")
        assertEquals("default", found?.projectId)
        assertEquals("backend", found?.agentId)
    }

    /**
     * M8 — a torn/corrupt file must NOT crash the boot: back it up and start empty (the resume just
     * won't fire). Mutation: drop the try/catch around `loadAll` → constructing the store throws → reds.
     */
    @Test
    fun m8_corruptFileBacksUpAndStartsEmpty() {
        val file = tempFile()
        file.writeText("{ this is not valid json ][")

        val store = JsonFileSessionStore(file) // must NOT throw
        assertNull(store.find("default", "backend"), "a corrupt store starts empty")

        val backups = file.parentFile.listFiles { f -> f.name.startsWith("session-store.json.corrupt-") }
        assertTrue(backups != null && backups.isNotEmpty(), "the corrupt file is backed up for forensics")
    }

    /**
     * M10 — the key is `(projectId, agentId)`: a different project must NOT resume another's session, and
     * clearing one project's entry must leave the other's intact. Mutation: key by agentId only → the two
     * projects collide → reds.
     */
    @Test
    fun m10_keyIsScopedByProjectId() {
        val store = InMemorySessionStore()
        store.upsert("projA", "backend", "sess-A", now = 1L)
        store.upsert("projB", "backend", "sess-B", now = 2L)

        assertEquals("sess-A", store.find("projA", "backend")?.sessionId)
        assertEquals("sess-B", store.find("projB", "backend")?.sessionId)

        store.clear("projA", "backend")
        assertNull(store.find("projA", "backend"), "clearing projA's entry removes only it")
        assertEquals("sess-B", store.find("projB", "backend")?.sessionId, "projB's entry is untouched")
    }

    /** find returns null for an absent key (first-start invariant: no entry ⇒ no --resume). */
    @Test
    fun find_absentKeyIsNull() {
        assertNull(InMemorySessionStore().find("default", "backend"))
    }

    /** createdAt is preserved while the SAME id is rewritten, but resets for a NEW id (new conversation). */
    @Test
    fun upsert_preservesCreatedAtForSameIdResetsForNew() {
        val store = InMemorySessionStore()
        store.upsert("default", "backend", "sess-1", now = 100L)
        store.upsert("default", "backend", "sess-1", now = 200L) // same id → lastActivity refresh
        store.find("default", "backend")!!.let {
            assertEquals(100L, it.createdAt, "same id keeps createdAt")
            assertEquals(200L, it.lastActivity, "lastActivity advances")
        }
        store.upsert("default", "backend", "sess-2", now = 300L) // new id → createdAt resets
        assertEquals(300L, store.find("default", "backend")!!.createdAt, "a new id resets createdAt")
    }
}
