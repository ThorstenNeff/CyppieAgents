package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.EventsConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-43 (ST9): the `events` config block. The load-bearing AC is **backward compatibility** — a
 * `platform.config.json` written before CYP-43 (no `events` key) must still load, with every knob
 * defaulted (`contextWindowTokens` = 200k etc.) — plus override parsing + round-trip.
 */
class EventsConfigTest {

    @Test
    fun configWithoutEventsBlock_loadsWithDefaults() {
        val json = """{"repo":{"url":"file:///r.git"},"agents":[{"id":"po","name":"PO","role":"PO"}]}"""
        val cfg = CommJson.decodeFromString<PlatformConfig>(json)
        assertEquals(EventsConfig(), cfg.events, "a pre-CYP-43 config must load with default events knobs")
        assertEquals(200_000L, cfg.events.contextWindowTokens)
        assertEquals(10, cfg.events.bandPct)
        assertEquals(75, cfg.events.compactPct)
        assertEquals(4096, cfg.events.queueCapacity)
        assertEquals(".cyppie/events.db", cfg.events.sinkPath)
        assertEquals(".cyppie/hooks.spool", cfg.events.spoolPath)
    }

    @Test
    fun eventsBlock_overridesParse_unsetFieldsDefault() {
        val json = """
            {"repo":{"url":"x"},"agents":[{"id":"po","name":"PO","role":"PO"}],
             "events":{"bandPct":20,"contextWindowTokens":1000000,"sinkPath":"/tmp/e.db","queueCapacity":128}}
        """.trimIndent()
        val cfg = CommJson.decodeFromString<PlatformConfig>(json)
        assertEquals(20, cfg.events.bandPct)
        assertEquals(1_000_000L, cfg.events.contextWindowTokens)
        assertEquals("/tmp/e.db", cfg.events.sinkPath)
        assertEquals(128, cfg.events.queueCapacity)
        assertEquals(75, cfg.events.compactPct) // unset → default
        assertEquals(64, cfg.events.batchSize) // unset → default
    }

    @Test
    fun eventsConfig_roundTrips() {
        val ev = EventsConfig(
            bandPct = 25,
            compactPct = 80,
            contextWindowTokens = 1_048_576,
            severityDefaults = mapOf("tool.result" to "warn"),
            retention = "off",
        )
        val rt = CommJson.decodeFromString<EventsConfig>(CommJson.encodeToString(ev))
        assertEquals(ev, rt)
    }

    @Test
    fun loadFromFile_appliesEventsKnobs() {
        val file = Files.createTempFile("platform-config", ".json").toFile()
        try {
            file.writeText(
                """
                {"repo":{"url":"file:///r.git"},
                 "events":{"queueCapacity":256,"batchSize":16,"spoolPath":".cyppie/hooks.jsonl"},
                 "agents":[{"id":"po","name":"PO","role":"PO"},{"id":"be","name":"BE","role":"WORKER"}]}
                """.trimIndent(),
            )
            val cfg = PlatformConfig.load(file)
            assertEquals(256, cfg.events.queueCapacity)
            assertEquals(16, cfg.events.batchSize)
            assertEquals(".cyppie/hooks.jsonl", cfg.events.spoolPath)
            assertEquals(200_000L, cfg.events.contextWindowTokens) // unset → default
        } finally {
            file.delete()
        }
    }
}
