package com.tneff.cyppieagents.migration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-730 §2.2b — the READ_ONLY warning classification (the visual distinctness that keeps the surface honest).
 * [ReadOnlyReason.MIGRATION_WINDOW] is an active migration, NOT a fault → it must NOT render as a warning (no
 * amber, no ▲), else the genuinely-worrying legacy/unknown rows stop standing out. Every other reason (legacy-frozen,
 * unknown provenance) IS a warning. A mutation flipping MIGRATION_WINDOW to a warning reddens here.
 */
class Cyp730ReadOnlyClassifyTest {

    @Test
    fun migrationWindow_isNotAWarning_othersAre() {
        assertFalse(
            readOnlyIsWarn(ReadOnlyReason.MIGRATION_WINDOW),
            "an in-flight migration is not a warning row (no amber) — else the real warnings stop standing out",
        )
        assertTrue(readOnlyIsWarn(ReadOnlyReason.LEGACY_UNEVALUATED), "a legacy-frozen READ_ONLY is a warning")
        assertTrue(readOnlyIsWarn(null), "an unknown-provenance READ_ONLY is a warning (fail-loud, never silently fine)")
    }
}
