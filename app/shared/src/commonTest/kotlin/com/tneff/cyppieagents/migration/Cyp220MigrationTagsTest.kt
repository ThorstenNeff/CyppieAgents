package com.tneff.cyppieagents.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-220 (tags §0) — the `storeKey` schema-collision fix. Real storeKeys are snake_case, but a testTag segment
 * may only be `[A-Za-z0-9-]+` (no `_`). [MigrationTags.seg] maps snake_case → lossless camelCase for the TAG ONLY.
 * This pins that mapping AND that no store tag ever ships a raw underscore (which would break the QA contract).
 */
class Cyp220MigrationTagsTest {

    private val realStoreKeys = listOf(
        "agent_events", "agent_override", "channel_share", "delivery", "event_log",
        "project_config", "remote_token", "report", "session",
    )

    @Test
    fun seg_mapsSnakeCaseToLosslessCamelCase() {
        assertEquals("agentEvents", MigrationTags.seg("agent_events"))
        assertEquals("remoteToken", MigrationTags.seg("remote_token"))
        assertEquals("channelShare", MigrationTags.seg("channel_share"))
        assertEquals("report", MigrationTags.seg("report")) // no underscore ⇒ unchanged
        assertEquals("session", MigrationTags.seg("session"))
    }

    @Test
    fun storeTags_neverContainUnderscore_schemaConforming() {
        for (k in realStoreKeys) {
            val tags = listOf(
                MigrationTags.store(k), MigrationTags.storeState(k), MigrationTags.storeMigrate(k),
                MigrationTags.unavailable(k), MigrationTags.unavailableReason(k),
            )
            for (t in tags) {
                assertFalse(t.contains('_'), "tag '$t' must not contain '_' (schema §0 — build it through seg())")
                assertTrue(t.startsWith("migration."), "tag '$t' is in the migration area")
            }
        }
    }
}
