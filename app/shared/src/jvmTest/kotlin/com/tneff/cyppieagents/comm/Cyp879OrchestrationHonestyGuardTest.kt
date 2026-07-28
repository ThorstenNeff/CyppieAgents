package com.tneff.cyppieagents.comm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-879 (OS-A) — the render ≠ authority boundary holds: the orchestration model derives kind + thread from the
 * SERVER-STAMPED meta (`meta.kind` / `meta.inReplyTo`) ONLY. It must NEVER derive them from message CONTENT (`body`),
 * TIMING (`ts`), or text HEURISTICS (regex / case-folding / substring / prefix). A reflex that "figures out the kind
 * from the text" or "guesses a thread from timing" — the exact client-fabrication this honesty forbids — reddens here.
 */
class Cyp879OrchestrationHonestyGuardTest {

    /** Content/timing/heuristic tokens the server-meta-only model must never reference. */
    private val forbiddenDerivationTokens = listOf(
        "body", ".ts", "Regex", "lowercase", "uppercase", "startsWith", "endsWith", ".contains(", ".matches(",
    )

    @Test
    fun orchestrationModel_derivesFromServerMetaOnly_neverContentOrTimingHeuristics() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/comm/OrchestrationMessage.kt")
        for (token in forbiddenDerivationTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-879: the orchestration model must derive kind/thread from server meta ONLY (render ≠ authority) — " +
                    "found the content/timing/heuristic token '$token'. Kind comes from meta.kind, a thread from " +
                    "meta.inReplyTo to a present parent; never guessed from body/ts/text.",
            )
        }
    }

    private fun codeLinesOf(rel: String): List<String> =
        locateSource(rel).readLines().filterNot { raw ->
            val t = raw.trimStart()
            t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
        }

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
