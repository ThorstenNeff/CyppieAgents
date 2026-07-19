package com.tneff.cyppieagents.migration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-730 (BE-4 a11y) — the READ_ONLY stateDescription must announce a **localized** reason label, never the raw
 * enum name. Before this fix the code built `stateDescription = "$readonly ($reasonWord)"` with
 * `reasonWord = store.readOnlyReason?.name`, so a screenreader read "Nur lesend (LEGACY_UNEVALUATED)". This guard
 * pins the fix at the SOURCE level (a `runComposeUiTest` mount hangs headless in this env): the raw `.name` leak
 * must be gone and the localized reason keys must be referenced. Reintroducing `readOnlyReason?.name` reddens it.
 */
class Cyp730ReadOnlyReasonA11yGuardTest {

    private val source = codeOf("src/commonMain/kotlin/com/tneff/cyppieagents/migration/MigrationSection.kt")

    @Test
    fun readOnly_stateDescription_usesLocalizedLabel_notRawEnumName() {
        assertFalse(
            source.contains("readOnlyReason?.name"),
            "CYP-730: the raw enum name must not feed the a11y stateDescription (a screenreader would read " +
                "'LEGACY_UNEVALUATED') — map to a localized label instead.",
        )
        assertTrue(
            source.contains("migration_readonly_reason_migrating") &&
                source.contains("migration_readonly_reason_legacy") &&
                source.contains("migration_readonly_reason_unknown"),
            "CYP-730: the READ_ONLY row must resolve its reason via the localized reason labels (BE-4).",
        )
    }

    /** Source with comments stripped, so a `.name` mention inside a KDoc/comment can't mask or fake the check. */
    private fun codeOf(rel: String): String =
        locateSource(rel).readLines().filterNot { raw ->
            val t = raw.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")

    private fun locateSource(rel: String): File {
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isFile) return it }
            File(cur, "app/shared/$rel").let { if (it.isFile) return it }
            cur = cur.parentFile
        }
        error("could not locate $rel")
    }
}
