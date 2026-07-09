package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.CompactConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-326 — the persisted compact-config store: fail-closed default (allowed=false, 500K) for an unset project;
 * a set survives a restart (a fresh instance over the same file); per-project keyed.
 */
class CompactConfigStoreTest {

    private fun tmp() = File(Files.createTempDirectory("compactcfg").toFile(), ".cyppie/compact-config.json")

    @Test
    fun unsetProject_defaultsFailClosed() {
        assertEquals(CompactConfig(allowed = false, thresholdTokens = 500_000), FileCompactConfigStore(tmp()).get("default"))
    }

    @Test
    fun set_survivesRestart_perProject() {
        val f = tmp()
        FileCompactConfigStore(f).apply {
            set("default", CompactConfig(allowed = true, thresholdTokens = 400_000))
            set("other", CompactConfig(allowed = false, thresholdTokens = 900_000))
        }
        val reloaded = FileCompactConfigStore(f) // simulated restart
        assertEquals(CompactConfig(allowed = true, thresholdTokens = 400_000), reloaded.get("default"))
        assertEquals(CompactConfig(allowed = false, thresholdTokens = 900_000), reloaded.get("other"))
    }

    @Test
    fun nullFile_isInMemoryOffSwitch() {
        val s = FileCompactConfigStore(null)
        s.set("default", CompactConfig(allowed = true, thresholdTokens = 500_000))
        assertEquals(CompactConfig(allowed = true, thresholdTokens = 500_000), s.get("default"))
    }
}
