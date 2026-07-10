package com.tneff.cyppieagents.window

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-363 — **the chrome-floor guard's state space is derived from `AgentWindow`'s parameter list, not from a
 * list a test author remembered to write.**
 *
 * `ContentWindowChromeFloorGuardTest` enumerates `ChromeState` = `{canControl, terminalAvailable, mode,
 * lifecycleError}` and measures the window at each point. But those are the inputs *I thought of*. `AgentWindow`
 * also takes `terminalGatedNote`, which the measurement let default to `false` — an input that moves chrome and
 * that the state space did not vary. A parameter added next year that shifts the **header** height (not the
 * toggle row) would default the same way, and it would escape the floor guard (which measures only enumerated
 * states) *and* the real-shell tripwire (which pins one production configuration).
 *
 * This closes it by reading the parameters `AgentWindow` actually declares and requiring **every one** to be
 * classified. A new parameter compiles fine and then fails this test until someone puts it in a bucket:
 *
 *  - [chromeInputsVariedByTheFloorGuard] — it changes chrome height, and the floor guard drives it (directly or
 *    through the `AgentViewModel`). Adding one here is a promise that `ChromeState` covers it.
 *  - [chromeInputProductionGatedAndTripwired] — it changes chrome height but production cannot currently reach
 *    the change; its return is caught by `theRealShellsUpperChrome…`, which measures the shipped shell.
 *  - [doesNotAffectChromeHeight] — it cannot change how tall the window's chrome is, with the reason why.
 *
 * The three buckets are disjoint and exhaustive over the real parameter list. Same discipline as
 * `OutlineTextColorGuardTest`: read the source, name every case, fail on anything unaccounted-for — plus the
 * stale-entry check that keeps the classification from rotting into a rubber stamp.
 *
 * **Limit, named.** This reads parameter *names*; it cannot know that a bucket's *claim* is true — that
 * `capabilities` really only changes width, say. That claim is what the floor guard and the tripwire test by
 * measurement. This guard tests the one thing they cannot: that no input is missing from their reckoning.
 */
class AgentWindowChromeInputCoverageTest {

    /**
     * Chrome-affecting inputs the floor guard varies. Each maps to how — a `ChromeState` field or a `ViewModel`
     * seed. `agentId`/`viewModel` are the composable's spine, not toggles, but the ViewModel is where three of
     * the four `ChromeState` dimensions enter, so it is named here rather than dismissed as inert.
     */
    private val chromeInputsVariedByTheFloorGuard: Map<String, String> = mapOf(
        "viewModel" to "carries canControl (toggle-row hint), contentMode (composer present + shell note), and " +
            "lifecycleError (the error row) — three of the four ChromeState dimensions enter through it",
        "terminalContent" to "its null-ness IS ChromeState.terminalAvailable: enables the Shell segment and " +
            "fills the content rectangle in TERMINAL mode",
    )

    /**
     * Chrome-affecting, but production cannot reach the change today, so the floor is not sized for it. Its
     * return to reachability is what `theRealShellsUpperChrome_isAHeightThisGuardActuallyMeasures` exists to
     * catch — it measures the real shell's upper chrome, so a gated row that comes back has nowhere to hide.
     */
    private val chromeInputProductionGatedAndTripwired: Map<String, String> = mapOf(
        "terminalGatedNote" to "renders the gated-shell note only when `terminalGatedNote && !terminalAvailable`; " +
            "the shipped shell passes `!WORKTREE_SHELL_LIVE_ENABLED` = false AND a non-null terminalContent, so " +
            "both conjuncts are false. Reachability is guarded by theRealShellsUpperChrome…, not assumed here",
    )

    /** Inputs that cannot change how tall the chrome is. The reason is the load-bearing part, not the entry. */
    private val doesNotAffectChromeHeight: Map<String, String> = mapOf(
        "agentId" to "test-tag / string identity; no view",
        "modifier" to "the window's OUTER layout (size/position from the window manager), never its inner chrome",
        "capabilities" to "the fidelity badge sits in the weighted identity cluster (CYP-369); it changes the " +
            "cluster's WIDTH, and the header is a single 56 dp line at every width — line count is fixed",
        "capabilitiesLoading" to "suppresses the same badge; width only, same reasoning as `capabilities`",
        "provider" to "the provider chip is in the same weighted cluster; width only, never a new line",
        "onCapabilityBadgeClick" to "a click callback; renders nothing",
    )

    private data class Param(val name: String, val line: Int)

    @Test
    fun everyAgentWindowParameter_isClassifiedForItsChromeEffect() {
        val declared = agentWindowParameters()
        assertTrue(declared.isNotEmpty(), "parsed no parameters from AgentWindow — the source scan is broken")

        val classified = chromeInputsVariedByTheFloorGuard.keys +
            chromeInputProductionGatedAndTripwired.keys + doesNotAffectChromeHeight.keys
        val unaccounted = declared.filter { it.name !in classified }
        assertTrue(
            unaccounted.isEmpty(),
            buildString {
                appendLine("CYP-363: AgentWindow declares parameters the chrome-floor guard's state space does not")
                appendLine("account for. Each must go in ONE bucket in AgentWindowChromeInputCoverageTest:")
                appendLine("  · chromeInputsVariedByTheFloorGuard      — it moves chrome AND ChromeState varies it")
                appendLine("  · chromeInputProductionGatedAndTripwired — it moves chrome but production can't reach it")
                appendLine("  · doesNotAffectChromeHeight              — it cannot change the chrome's height, and why")
                appendLine("A defaulted input is exactly how `terminalGatedNote` slipped the state space.")
                appendLine()
                unaccounted.forEach { appendLine("  AgentWindow.kt:${it.line}  ${it.name}") }
            },
        )
    }

    @Test
    fun noBucketPreApprovesAParameterThatNoLongerExists() {
        val declared = agentWindowParameters().map { it.name }.toSet()
        val buckets = mapOf(
            "chromeInputsVariedByTheFloorGuard" to chromeInputsVariedByTheFloorGuard.keys,
            "chromeInputProductionGatedAndTripwired" to chromeInputProductionGatedAndTripwired.keys,
            "doesNotAffectChromeHeight" to doesNotAffectChromeHeight.keys,
        )
        val stale = buckets.flatMap { (bucket, names) -> (names - declared).map { "$bucket: $it" } }
        assertTrue(
            stale.isEmpty(),
            "These classified names are no longer parameters of AgentWindow — a classification for a removed " +
                "parameter pre-approves a future one with the same name, and it means the scan drifted from the " +
                "source. Delete them:\n" + stale.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun theBucketsAreDisjoint_soNoParameterIsCountedTwice() {
        val a = chromeInputsVariedByTheFloorGuard.keys
        val b = chromeInputProductionGatedAndTripwired.keys
        val c = doesNotAffectChromeHeight.keys
        val overlap = (a intersect b) + (a intersect c) + (b intersect c)
        assertTrue(overlap.isEmpty(), "a parameter is in more than one bucket: $overlap")
    }

    /**
     * The parameters of `fun AgentWindow(...)`, read from the composable's source. A parameter is a line of the
     * form `name: Type…` between the signature's parentheses; KDoc/comment lines (`*`, `/`, `//`) are skipped.
     */
    private fun agentWindowParameters(): List<Param> {
        val src = locateAgentWindowSource().readLines()
        val open = src.indexOfFirst { it.contains("fun AgentWindow(") }
        require(open >= 0) { "could not find `fun AgentWindow(` in the source" }
        val params = mutableListOf<Param>()
        var depth = 0
        var started = false
        for (i in open until src.size) {
            val raw = src[i]
            depth += raw.count { it == '(' } - raw.count { it == ')' }
            started = started || raw.contains("fun AgentWindow(")
            // The signature ends when the paren depth returns to zero after it opened.
            if (started && depth <= 0 && raw.contains(")")) {
                // Still scan this closing line for a trailing param, then stop.
                topLevelParam(raw, i)?.let(params::add)
                break
            }
            if (i == open) continue // the `fun AgentWindow(` line itself carries no parameter
            topLevelParam(raw, i)?.let(params::add)
        }
        return params
    }

    /** A parameter declaration at the top level of the signature, or null for KDoc/comment/blank lines. */
    private fun topLevelParam(raw: String, index: Int): Param? {
        val code = raw.substringBefore("//").trim()
        if (code.isEmpty() || code.startsWith("*") || code.startsWith("/")) return null
        val name = Regex("""^([A-Za-z_]\w*)\s*:""").find(code)?.groupValues?.get(1) ?: return null
        return Param(name, index + 1)
    }

    private fun locateAgentWindowSource(): File {
        val rel = "src/commonMain/kotlin/com/tneff/cyppieagents/agentview/AgentWindow.kt"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isFile) return it }
            File(cur, "app/shared/$rel").let { if (it.isFile) return it }
            cur = cur.parentFile
        }
        error("could not locate $rel")
    }
}
