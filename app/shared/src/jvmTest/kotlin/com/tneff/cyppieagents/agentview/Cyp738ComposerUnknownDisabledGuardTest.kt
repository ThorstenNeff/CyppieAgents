package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-738 (§4a render guardrail) — the MessageComposer must render UNKNOWN as its OWN disabled-with-hint branch,
 * distinct from the editable (UNGATED/WRITABLE) path AND from READ_ONLY, so a wired runtime-unknown is never
 * silently editable (the leak the PL flagged). A `runComposeUiTest` mount hangs headless in this env, so this pins
 * the branches at the source level: the composer early-returns on `writability == …UNKNOWN` (its own hint) and on
 * `writability == …READ_ONLY` (a distinct hint). Removing/merging the UNKNOWN branch reddens this tooth.
 */
class Cyp738ComposerUnknownDisabledGuardTest {

    private val source = codeOf("src/commonMain/kotlin/com/tneff/cyppieagents/agentview/AgentWindow.kt")

    @Test
    fun messageComposer_hasDistinct_unknownDisabledBranch() {
        assertTrue(
            source.contains("writability == AgentComposerWritability.UNKNOWN"),
            "CYP-738: MessageComposer must branch on UNKNOWN explicitly (its own disabled-with-hint surface) — a " +
                "wired runtime-unknown must never fall onto the editable path.",
        )
        assertTrue(
            source.contains("agent_composer_unknown_hint"),
            "CYP-738: the UNKNOWN branch must render its dedicated hint (distinct from the read-only hint).",
        )
    }

    @Test
    fun messageComposer_readOnly_isDistinctFromUnknown() {
        assertTrue(
            source.contains("writability == AgentComposerWritability.READ_ONLY") &&
                source.contains("agent_composer_readonly_hint"),
            "CYP-738: READ_ONLY is its own proactive-hint branch, distinct from UNKNOWN.",
        )
    }

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
