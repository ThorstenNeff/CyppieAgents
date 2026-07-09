package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-325 (defect 2) — the durable per-agent token-usage store + its round-trip through [AgentTokenUsageTracker].
 * Only known numbers persist (null = unknown → removed, never stored: the null≠0 honesty). Rehydrated on
 * construction so a restart's snapshot-on-connect keeps the value.
 */
class FileTokenUsageStoreTest {

    private fun tmpFile() = File(Files.createTempDirectory("tokenusage").toFile(), ".cyppie/token-usage.json")

    @Test
    fun put_allFor_persistsAndReloadsAcrossInstances() {
        val f = tmpFile()
        FileTokenUsageStore(f).apply { put("default", "backend", 15500); put("default", "frontend", 900); put("other", "x", 5) }
        // a NEW instance over the same file = a simulated restart
        val reloaded = FileTokenUsageStore(f)
        assertEquals(mapOf("backend" to 15500, "frontend" to 900), reloaded.allFor("default"))
        assertEquals(mapOf("x" to 5), reloaded.allFor("other"))
    }

    @Test
    fun nullValue_isNeverPersisted_removesInstead() {
        val f = tmpFile()
        FileTokenUsageStore(f).apply { put("default", "backend", 15500); put("default", "backend", null) }
        assertEquals(emptyMap(), FileTokenUsageStore(f).allFor("default"), "null (unknown) → removed, never stored as a value")
    }

    @Test
    fun removeAgent_and_removeProject_purge() {
        val f = tmpFile()
        val s = FileTokenUsageStore(f).apply { put("default", "backend", 100); put("default", "frontend", 200); put("p2", "a", 3) }
        s.removeAgent("default", "backend")
        assertEquals(mapOf("frontend" to 200), s.allFor("default"))
        assertEquals(2, FileTokenUsageStore(f).allFor("default").size + FileTokenUsageStore(f).allFor("p2").size)
        s.removeProject("default")
        assertEquals(emptyMap(), FileTokenUsageStore(f).allFor("default"))
        assertEquals(mapOf("a" to 3), FileTokenUsageStore(f).allFor("p2"), "cascade is project-scoped")
    }

    @Test
    fun removeProject_blank_isFailClosed_noUnscopedClear() {
        val f = tmpFile()
        val s = FileTokenUsageStore(f).apply { put("default", "backend", 100) }
        assertEquals(0, s.removeProject(""))
        assertEquals(mapOf("backend" to 100), s.allFor("default"))
    }

    @Test
    fun nullFile_isInMemoryOffSwitch_noPersistence() {
        val s = FileTokenUsageStore(null)
        s.put("default", "backend", 100) // no throw, no file
        assertEquals(mapOf("backend" to 100), s.allFor("default"))
    }

    // ---- tracker round-trip (the real persistence path) ----

    @Test
    fun tracker_persistsOnResult_and_rehydratesOnConstruction() {
        val f = tmpFile()
        val store = TokenUsageStore(f)
        AgentTokenUsageTracker("default", store).onResult("backend", 15500)
        // a fresh tracker over the SAME store = a restart: the value is back in its snapshot.
        val restarted = AgentTokenUsageTracker("default", store)
        assertEquals(listOf(AgentTokenUsageEvent("backend", 15500)), restarted.snapshot())
    }

    @Test
    fun tracker_reset_toNull_dropsPersistedValue() {
        val f = tmpFile()
        val store = TokenUsageStore(f)
        val t = AgentTokenUsageTracker("default", store)
        t.onResult("backend", 15500)
        t.reset("backend") // → null: unknown → removed from the store
        assertTrue(AgentTokenUsageTracker("default", store).snapshot().isEmpty(), "a reset agent has no persisted value")
    }

    @Test
    fun tracker_forget_dropsPersistedValue() {
        val f = tmpFile()
        val store = TokenUsageStore(f)
        AgentTokenUsageTracker("default", store).apply { onResult("backend", 100); forget("backend") }
        assertFalse(AgentTokenUsageTracker("default", store).snapshot().any { it.agentId == "backend" })
    }

    @Test
    fun tracker_isScopedByProjectId() {
        val f = tmpFile()
        val store = TokenUsageStore(f)
        AgentTokenUsageTracker("projA", store).onResult("backend", 100)
        AgentTokenUsageTracker("projB", store).onResult("backend", 200)
        assertEquals(listOf(AgentTokenUsageEvent("backend", 100)), AgentTokenUsageTracker("projA", store).snapshot())
        assertEquals(listOf(AgentTokenUsageEvent("backend", 200)), AgentTokenUsageTracker("projB", store).snapshot())
    }
}
