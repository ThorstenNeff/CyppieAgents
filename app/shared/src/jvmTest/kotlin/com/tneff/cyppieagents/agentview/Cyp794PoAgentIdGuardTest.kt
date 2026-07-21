package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.PO_AGENT_ID
import com.tneff.cyppieagents.model.isPoSlot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-794 — the op-po transcript-fold gate reads the [PO_AGENT_ID] constant, not a scattered `"po"` literal. Pins
 * the constant's VALUE (it must match the hub-and-spoke `po` convention the server derives channel ids from) and
 * guards that the magic string stays removed at the AgentWindow fold call-site (the anti-regression for CYP-794).
 * Mirrors the source-scan idiom of [Cyp738ShellSeamArmedGuardTest].
 */
class Cyp794PoAgentIdGuardTest {

    @Test
    fun poAgentId_isPo_andIsPoSlotTracksIt() {
        assertEquals("po", PO_AGENT_ID, "the PO agent id must match the server hub-and-spoke `po` convention")
        assertTrue(isPoSlot(PO_AGENT_ID), "isPoSlot must track the constant, not a private literal")
        assertFalse(isPoSlot("backend"))
    }

    @Test
    fun foldGate_usesConstant_notPoLiteral() {
        val src = codeOf("agentview/AgentWindow.kt")
        // The op-po fold gate is present and bound to the constant...
        assertTrue(
            Regex("""foldToolRuns\s*=\s*agentId\s*==\s*PO_AGENT_ID""").containsMatchIn(src),
            "AgentWindow's op-po fold gate must read `foldToolRuns = agentId == PO_AGENT_ID` (CYP-794).",
        )
        // ...and no `agentId == "po"` magic-string was re-introduced anywhere in the file.
        assertFalse(
            Regex("""agentId\s*==\s*"po"""").containsMatchIn(src),
            "the `agentId == \"po\"` magic string must stay removed — use PO_AGENT_ID.",
        )
    }

    /** Read a commonMain source file with `//`-comment and KDoc/block-comment lines stripped. */
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
