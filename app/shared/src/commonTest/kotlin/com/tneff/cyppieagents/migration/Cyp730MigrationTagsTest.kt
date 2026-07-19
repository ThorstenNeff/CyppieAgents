package com.tneff.cyppieagents.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-730 §2.2b — the READ_ONLY provenance tag contract (Achse B). Each [ReadOnlyReason] (and the absent-reason
 * `null`) maps to a DISTINCT row tag, so the three READ_ONLY rows are individually addressable (migration window vs
 * legacy-frozen vs unknown). A mutation that collapses two reasons onto the same qualifier reddens here.
 */
class Cyp730MigrationTagsTest {

    @Test
    fun readOnlyReason_mapsToDistinctRowTag() {
        val k = "channel_share" // snake_case storeKey → camelCase tag segment via seg()
        val migrating = MigrationTags.storeReadonly(k, ReadOnlyReason.MIGRATION_WINDOW)
        val legacy = MigrationTags.storeReadonly(k, ReadOnlyReason.LEGACY_UNEVALUATED)
        val unknown = MigrationTags.storeReadonly(k, null)

        assertEquals("migration.store.channelShare.state.readonly.migrating", migrating)
        assertEquals("migration.store.channelShare.state.readonly.legacy", legacy)
        assertEquals("migration.store.channelShare.state.readonly.unknown", unknown)
        assertEquals(
            3, setOf(migrating, legacy, unknown).size,
            "each READ_ONLY reason must resolve to a DISTINCT row tag — the three states are individually addressable",
        )
    }

    @Test
    fun legacyActionLine_hangsUnderTheLegacyRow() {
        val tag = MigrationTags.storeLegacyAction("channel_share")
        assertEquals("migration.store.channelShare.state.readonly.legacy.action", tag)
        assertTrue(
            tag.startsWith(MigrationTags.storeReadonly("channel_share", ReadOnlyReason.LEGACY_UNEVALUATED)),
            "the legacy action line is nested under the legacy READ_ONLY row it explains",
        )
    }
}
