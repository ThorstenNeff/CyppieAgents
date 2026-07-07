package com.tneff.cyppieagents.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-303 (folded into CYP-304) — the §9-Inv.1 SOURCE GUARD. Now that the Night scheme makes `tertiary`
 * signal-GREEN, ANY semantic use of `colorScheme.tertiary*` in the app would paint a warning / offline /
 * pending state green ("ok") — an honesty break (green must NEVER signal status). Pkg a0 (CYP-300)
 * de-overloaded every such use (WARN→amber severity source, in-progress→neutral, EFFECT_DEFERRED→secondary),
 * leaving **zero** consumers. This test scans commonMain source and fails if any `colorScheme.<…>tertiary…`
 * reference reappears OUTSIDE `MaritimeTheme.kt` (which DEFINES the token) — so the invariant becomes
 * self-enforcing exactly when it became load-bearing.
 *
 * `tertiary` is a brand/accent-ONLY role now; a future *decorative* use would be a deliberate act that also
 * updates this guard. Mutation proof: add `MaterialTheme.colorScheme.tertiary` to any commonMain UI file → RED.
 */
class TertiarySourceGuardTest {

    // Matches colorScheme.tertiary / .tertiaryContainer / .onTertiary / .onTertiaryContainer.
    private val forbidden = Regex("""colorScheme\.\w*[Tt]ertiary""")

    @Test
    fun noSemanticTertiaryUseInCommonMain_outsideMaritimeTheme() {
        val root = locateCommonMain()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MaritimeTheme.kt" }
            .mapNotNull { file ->
                val hits = file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }
                if (hits.isEmpty()) null
                else file.name + ": " + hits.joinToString("; ") { "L${it.index + 1} ${it.value.trim()}" }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "§9-Inv.1: `tertiary` is now the signal-green NIGHT accent (brand only) — no SEMANTIC " +
                "`colorScheme.tertiary*` may appear in commonMain (a0 de-overloaded it; use the severity source " +
                "or a neutral role instead). Offenders:\n" + offenders.joinToString("\n"),
        )
    }

    /** Walk up to the module's commonMain kotlin root (mirrors CommI18nDisclosureTest's locate pattern). */
    private fun locateCommonMain(): File {
        val rel = "src/commonMain/kotlin/com/tneff/cyppieagents"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isDirectory) return it }
            File(cur, "app/shared/$rel").let { if (it.isDirectory) return it }
            cur = cur.parentFile
        }
        error("Could not locate $rel from ${File(".").absolutePath}")
    }
}
