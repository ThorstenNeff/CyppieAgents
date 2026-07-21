package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-386 — the committed-source guard that **no German notice literal lives at a PROD notice-emission site**: the
 * user-facing text of a system notice comes from a resource (injected label), not a hardcoded literal, so the EN
 * build never shows German. The two prod emission sites:
 *  - [StreamJsonMapper]  — the "Turn-Fehler" notice (injected [StreamJsonMapper.turnErrorLabel] ← `agent_turn_error_notice`).
 *  - [AgentViewModel]    — the "Verbindung zum Agenten verloren" notice (injected `connLostLabel` ← `agent_conn_lost_notice`).
 *
 * **Why a source scan, not a render/behaviour test (the [[illusory-literal-compliance]] point):** a behaviour test
 * on `Notice.text` only sees what the label resolves TO, not whether it came from a literal — an EN build resolving
 * the key would still ship if a stray German literal lingered. The lie lives at the emission site the code owns, so
 * enforce it there. Mirrors [com.tneff.cyppieagents.ui.OutlineTextColorGuardTest] (comment-stripped scan + walk-up).
 *
 * **The demo is EXPLICITLY excluded, not silently unscanned (PO condition).** [StubAgentSession]'s "Session
 * gestartet" is a `:app:webAppDemo` fixture whose whole scenario is German demo content — not prod-facing, so it is
 * NAMED in [excludedDemoSites] with its reason, and [excludedDemoSites_stillExist] asserts the file still exists so
 * the exclusion cannot rot into a silent, complete-looking-but-blind guard.
 *
 * Mutation: reinstate `"Turn-Fehler"` in the mapper OR `"Verbindung zum Agenten verloren"` in the VM ⇒ RED.
 */
class Cyp386NoticeLiteralGuardTest {

    /** The PROD notice-emission sites — no German notice literal may appear here (each localized to an injected label). */
    private val prodEmissionSites = mapOf(
        "agentview/StreamJsonMapper.kt" to "turnErrorLabel",
        "agentview/AgentViewModel.kt" to "connLostLabel",
    )

    /** The German notice literals CYP-386/CYP-383 localized — none may reappear at a prod emission site. */
    private val germanNoticeLiterals = listOf("Turn-Fehler", "Verbindung zum Agenten verloren", "Session gestartet")

    /**
     * EXPLICITLY excluded, with reason (PO-ratified 2026-07-21) — a DEMO fixture, not a prod emission site, so its
     * German notice literal is not a prod i18n bug. Named so the exclusion is LABELED (not a silent scan gap).
     */
    private val excludedDemoSites = mapOf(
        "agentview/StubAgentSession.kt" to
            "webAppDemo fixture (mainDemo/EventLogDemoApp, 'NOT the prod web App') + tests; its whole scripted " +
                "scenario is German demo content, so its 'Session gestartet' notice is not a prod i18n bug. Localizing " +
                "one notice in an all-German demo would only mix languages for ~0 gain.",
    )

    @Test
    fun noGermanNoticeLiteral_atAnyProdEmissionSite() {
        prodEmissionSites.forEach { (relPath, injectedLabel) ->
            val lines = codeLinesOf(relPath)
            germanNoticeLiterals.forEach { german ->
                val offenders = lines.filter { it.contains("\"") && it.contains(german) }
                assertTrue(
                    offenders.isEmpty(),
                    "$relPath must hold NO user-facing German notice literal (\"$german\") — the label is injected " +
                        "so the EN build never shows German (CYP-386). Offending source line(s):\n" +
                        offenders.joinToString("\n") { "  $it" },
                )
            }
            // Non-vacuity: the scan really reached this emission site AND it uses its injected label (else an empty
            // file / wrong path would pass silently).
            assertTrue(
                lines.any { it.contains(injectedLabel) },
                "$relPath must USE its injected label ($injectedLabel) — if this is absent the scan located the wrong file",
            )
        }
    }

    @Test
    fun excludedDemoSites_stillExist_soTheExclusionCannotRot() {
        // A named exclusion for a file that no longer exists is a silent hole: the guard reads as "complete" while
        // scanning nothing at that path, and a future prod literal at a same-named file would slip past. Delete the
        // exclusion entry when you delete the file.
        val missing = excludedDemoSites.keys.filter { !locateCommonMain().resolve(it).isFile }
        assertTrue(
            missing.isEmpty(),
            "These excludedDemoSites no longer exist — the exclusion is now a silent, complete-looking-but-blind gap. " +
                "Remove the stale entry (or fix the path):\n" + missing.joinToString("\n") { "  $it" },
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
