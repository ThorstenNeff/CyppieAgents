package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-738 (live-wire) — the committed-source guard that `AgentShell` ARMS the writable-agents seam with the live
 * [HttpAgentWritableApi], rather than the pre-CYP-779 DORMANT `= agentWritableApi` (which left `agentWritable` null
 * → the composer on its unconditional-editable UNGATED path).
 *
 * **Why a source scan and not the E2E alone:** [Cyp738AgentWritableHttpE2eTest] builds a real [HttpAgentWritableApi]
 * DIRECTLY, so it proves the HTTP → VM → composer path grips — but it stays green even if the shell reverts to the
 * dormant wiring, because it never asks the shell what it defaults to. A revert to dormant would silently return the
 * composer to always-editable (the §4a leak the PL guardrail forbids) with no behavioural test failing. So the arming
 * is pinned where the shell owns it. Mirrors the CYP-629 §6.3c wiring tripwire (source scan of `AgentShell`).
 *
 * Mutation: revert to `val resolvedAgentWritable = agentWritableApi` (drop the `?: …HttpAgentWritableApi` fallback) ⇒
 * RED.
 */
class Cyp738ShellSeamArmedGuardTest {

    /** `resolvedAgentWritable = agentWritableApi ?: <default>` — the ARMED form (fallback present, not dormant). */
    private val armedResolve = Regex("""resolvedAgentWritable\s*=\s*agentWritableApi\s*\?:""")

    /** The live default is the HTTP impl (not a stub / not null). */
    private val liveDefault = Regex("""HttpAgentWritableApi\s*\(""")

    @Test
    fun agentShell_armsTheWritableAgentsSeam_withTheLiveHttpImpl() {
        val src = codeOf("AgentShell.kt")

        assertTrue(
            armedResolve.containsMatchIn(src),
            "AgentShell must ARM the writable-agents seam: `resolvedAgentWritable = agentWritableApi ?: <live default>` " +
                "(CYP-738 live-wire). If this is red the seam reverted to the DORMANT `= agentWritableApi` → the agent " +
                "composer silently returns to unconditional-editable (UNGATED), the §4a leak.",
        )
        assertTrue(
            liveDefault.containsMatchIn(src),
            "AgentShell's writable-agents default must be the live HttpAgentWritableApi (GET /api/agents/writable), " +
                "not a stub or null — the seam is armed for prod, CYP-779 having landed.",
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
