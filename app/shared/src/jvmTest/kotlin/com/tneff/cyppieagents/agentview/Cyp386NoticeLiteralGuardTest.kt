package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-386 — the committed-source guard that [StreamJsonMapper] holds NO hardcoded German user-facing notice
 * literal: the meaning of the "turn error" notice comes from a resource (`agent_turn_error_notice`), injected as
 * [StreamJsonMapper.turnErrorLabel] exactly like the CYP-383 `readyNoticeText`.
 *
 * **Why a source scan, not a render/behaviour test alone (the [[illusory-literal-compliance]] point the PO made):**
 * a behaviour test on the mapped `Notice.text` can only see what the label resolves TO, not whether it came from a
 * literal — with an EN build resolving `agent_turn_error_notice = "Turn error"`, a stray German literal elsewhere in
 * the mapper would still ship. The lie lives at the emission site MY code owns (the mapper), so enforce it there:
 * a returning hardcoded `"Turn-Fehler"` reds this instantly. Behaviour side is pinned by
 * [StreamJsonMapperTest.errorResult_emitsNotice] (the text starts with the INJECTED label).
 *
 * Same mechanism as [com.tneff.cyppieagents.ui.OutlineTextColorGuardTest] (comment-stripped source scan + walk-up
 * locate). Comment lines are stripped so the KDoc naming the German word (as an example) is not counted as a use.
 *
 * Mutation: reinstate `AgentEvent.Notice(idOf(e.uuid), "Turn-Fehler" + …)` in the mapper ⇒ RED.
 */
class Cyp386NoticeLiteralGuardTest {

    /** Any double-quoted string literal containing "Turn-Fehler" — the exact German literal CYP-386 removed. */
    private val germanTurnErrorLiteral = Regex(""""[^"]*Turn-Fehler[^"]*"""")

    @Test
    fun streamJsonMapper_holdsNoHardcodedGermanTurnErrorLiteral() {
        val lines = codeLinesOf("agentview/StreamJsonMapper.kt")

        val offenders = lines.filter { germanTurnErrorLiteral.containsMatchIn(it) }
        assertFalse(
            offenders.isNotEmpty(),
            "StreamJsonMapper must hold NO user-facing 'Turn-Fehler' literal — the label is injected " +
                "(turnErrorLabel ← agent_turn_error_notice), so the EN build never shows German (CYP-386). Offending " +
                "source line(s):\n" + offenders.joinToString("\n") { "  $it" },
        )
        // Non-vacuity: the scan really reached the mapper (else an empty file would pass silently) — the injected
        // label must be USED in the notice construction.
        assertTrue(
            lines.any { it.contains("turnErrorLabel") },
            "the mapper must USE the injected turnErrorLabel — if this is absent the scan located the wrong file",
        )
    }

    /** Read a commonMain source file's code lines with `//`-comment and KDoc/block-comment lines stripped. */
    private fun codeLinesOf(relPath: String): List<String> =
        locateCommonMain().resolve(relPath).readLines().mapNotNull { raw ->
            val code = raw.substringBefore("//").trim()
            if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) null else code
        }

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
