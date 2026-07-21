package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-239 — axis (a): the persona-restart badge appears **only after a persona (CLAUDE.md) change**, never after a
 * name/colour save (which is immediate, §7). The distinction lives at the shell wiring: `AgentShell` marks the
 * per-agent VM gated on the settings VM's **`needsRestart`** (= `claudeMdWritten`, set ONLY by a CLAUDE.md overwrite),
 * NOT on `saved` (the name/colour path).
 *
 * **Why a source scan:** the SET crosses two ViewModels (settings dialog → per-agent window VM) through the shell, and
 * a behavioural test would need a full shell mount + a stubbed CLAUDE.md HTTP port to drive `claudeMdWritten`. The
 * regression that matters — the badge firing on a name/colour save — is a one-token wiring slip (`needsRestart` →
 * `saved`), so it is pinned where the shell owns it. The CLEAR half (on RUNNING) is behaviourally toothed by
 * [Cyp239PersonaRestartClearTest].
 *
 * Mutation (the PO's axis-(a) mutation): gate the mark on `saved` instead of `needsRestart` ⇒ a name/colour save would
 * show the badge ⇒ this REDs.
 */
class Cyp239PersonaBadgeWiringGuardTest {

    /** The mark must be gated on `.needsRestart` (persona signal) on the same statement that calls the mark. */
    private val markGatedOnNeedsRestart =
        Regex("""\.needsRestart\s*\)[^\n]*markPersonaPendingRestart""")

    @Test
    fun agentShell_marksPersonaPending_gatedOnNeedsRestart_notNameOrColourSave() {
        val src = codeOf("AgentShell.kt")

        assertTrue(
            src.contains("markPersonaPendingRestart"),
            "AgentShell must mark the per-agent persona-pending badge — the wiring is missing entirely (CYP-239).",
        )
        assertTrue(
            markGatedOnNeedsRestart.containsMatchIn(src),
            "AgentShell must gate the persona-pending mark on the settings VM's `needsRestart` (= a CLAUDE.md " +
                "overwrite), NEVER on a name/colour `saved` (§7 immediate). If this is red the mark was re-gated on " +
                "a non-persona signal → the badge would show after a name/colour save (the CYP-239 axis-(a) lie).",
        )
    }

    /** Read a commonMain source file's code lines with `//`-comment and KDoc/block-comment lines stripped. */
    private fun codeOf(relPath: String): String =
        locateCommonMain().resolve(relPath).readLines().mapNotNull { raw ->
            val code = raw.substringBefore("//").trim()
            if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) null else code
        }.joinToString("\n")

    /** Walk up to the module's commonMain kotlin root (mirrors OutlineTextColorGuardTest's locate). */
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
