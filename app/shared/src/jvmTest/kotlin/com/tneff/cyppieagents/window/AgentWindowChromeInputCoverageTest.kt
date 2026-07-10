package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-363 — **the chrome-floor guard's state space is derived from `AgentWindow`'s parameter list, not from a
 * list a test author remembered to write.**
 *
 * `ContentWindowChromeFloorGuardTest` enumerates `ChromeState` = `{canControl, terminalAvailable, mode,
 * lifecycleError}` and measures the window at each point. But those are the inputs *I thought of*. `AgentWindow`
 * also takes `terminalGatedNote`, which the measurement let default to `false` — an input that moves chrome and
 * that the state space did not vary. A parameter added next year that shifts the **header** height (not the
 * toggle row) would default the same way and escape the floor guard (which measures only enumerated states) *and*
 * the real-shell tripwire (which pins one production configuration).
 *
 * This closes it by reading the parameters `AgentWindow` actually declares and requiring **every one** to be
 * classified into exactly one bucket:
 *
 *  - [chromeInputsVariedByTheFloorGuard] — it changes chrome height, and the floor guard drives it (directly or
 *    through the `AgentViewModel`). Being here is a promise that `ChromeState` covers it.
 *  - [chromeInputProductionGatedAndTripwired] — it changes chrome height but production cannot reach the change;
 *    its return is caught by `theRealShellsUpperChrome…`, which measures the shipped shell.
 *  - [chromeInertByMeasurement] — it does **not** change chrome height, and [theInertBucketIsMeasuredNotBelieved]
 *    proves it: the header is rendered with the parameter at a chrome-provoking value and its height is asserted
 *    unchanged. **This bucket is a measurement, not a claim.**
 *  - [chromeInertByConstruction] — it cannot be measured *through this composable's own tags*, because it is the
 *    identity those tags are keyed on. One entry only, and the reason is why measurement does not apply.
 *
 * **The point of the split (the reviewer's question).** An "inert" bucket that is only *asserted* is a promise
 * without a guard: drop a chrome-shifting parameter into it and the test nods. So the inert claim is **measured**
 * for every parameter that renders anything. The single exception, `agentId`, is named as such — not hidden in a
 * prose reason — because you cannot provoke it without changing the tag the measurement addresses the window by.
 *
 * **Limit, still named.** The source scan reads parameter *names*; it cannot know a bucket's claim is true for
 * the *varied* or *gated* buckets — that is what the floor guard and the tripwire measure. What this file adds is
 * that the *inert* claim is no longer taken on trust either. Three guards, three responsibilities; none believed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentWindowChromeInputCoverageTest {

    private val chromeInputsVariedByTheFloorGuard: Map<String, String> = mapOf(
        "viewModel" to "carries canControl (toggle-row hint), contentMode (composer present + shell note), and " +
            "lifecycleError (the error row) — three of the four ChromeState dimensions enter through it",
        "terminalContent" to "its null-ness IS ChromeState.terminalAvailable: enables the Shell segment and " +
            "fills the content rectangle in TERMINAL mode",
    )

    private val chromeInputProductionGatedAndTripwired: Map<String, String> = mapOf(
        "terminalGatedNote" to "renders the gated-shell note only when `terminalGatedNote && !terminalAvailable`; " +
            "the shipped shell passes `!WORKTREE_SHELL_LIVE_ENABLED` = false AND a non-null terminalContent, so " +
            "both conjuncts are false. Reachability is guarded by theRealShellsUpperChrome…, not assumed here",
    )

    /**
     * Inert, and **measured** so in [theInertBucketIsMeasuredNotBelieved]. The value is the chrome-provoking
     * value used to prove the header height does not move — a value that makes the parameter render *the most* it
     * can, so "unchanged height" is evidence and not luck. `null` means the default already is the provoking one.
     */
    private val chromeInertByMeasurement: Map<String, String> = mapOf(
        "modifier" to "outer layout supplied by the window manager; padding/offset move the window, not its header",
        "capabilities" to "a DEGRADED value renders the fidelity badge; it lands in the weighted identity cluster " +
            "(CYP-369) and changes the cluster's WIDTH, never the header's single-line height",
        "capabilitiesLoading" to "toggles the same badge's presence; width only",
        "provider" to "a present provider renders the '(Claude)' chip in the same weighted cluster; width only",
        "onCapabilityBadgeClick" to "a click callback; renders nothing",
    )

    /**
     * The one parameter that cannot be measured through this composable's tags: it IS those tags. Provoking it
     * (a different id) moves every `agent.<id>.*` node, so the measurement would be comparing two different
     * windows, not two states of one. Pure identity, reasoned rather than measured — and alone, so the exception
     * is visible instead of buried among genuinely-measured names.
     */
    private val chromeInertByConstruction: Map<String, String> = mapOf(
        "agentId" to "the string the window's own test tags are keyed on; changing it changes what is measured, " +
            "not how tall the window is. It renders no view of its own.",
    )

    private fun allBuckets() = listOf(
        "chromeInputsVariedByTheFloorGuard" to chromeInputsVariedByTheFloorGuard.keys,
        "chromeInputProductionGatedAndTripwired" to chromeInputProductionGatedAndTripwired.keys,
        "chromeInertByMeasurement" to chromeInertByMeasurement.keys,
        "chromeInertByConstruction" to chromeInertByConstruction.keys,
    )

    private data class Param(val name: String, val line: Int)

    @Test
    fun everyAgentWindowParameter_isClassifiedForItsChromeEffect() {
        val declared = agentWindowParameters()
        assertTrue(declared.isNotEmpty(), "parsed no parameters from AgentWindow — the source scan is broken")

        val classified = allBuckets().flatMap { it.second }.toSet()
        val unaccounted = declared.filter { it.name !in classified }
        assertTrue(
            unaccounted.isEmpty(),
            buildString {
                appendLine("CYP-363: AgentWindow declares parameters the chrome-floor guard's state space does not")
                appendLine("account for. Each must go in ONE bucket in AgentWindowChromeInputCoverageTest:")
                appendLine("  · chromeInputsVariedByTheFloorGuard      — it moves chrome AND ChromeState varies it")
                appendLine("  · chromeInputProductionGatedAndTripwired — it moves chrome but production can't reach it")
                appendLine("  · chromeInertByMeasurement               — it does not move chrome (and it is measured)")
                appendLine("  · chromeInertByConstruction              — identity the tags key on; cannot be measured")
                appendLine("A defaulted input is exactly how `terminalGatedNote` slipped the state space.")
                appendLine()
                unaccounted.forEach { appendLine("  AgentWindow.kt:${it.line}  ${it.name}") }
            },
        )
    }

    @Test
    fun noBucketPreApprovesAParameterThatNoLongerExists() {
        val declared = agentWindowParameters().map { it.name }.toSet()
        val stale = allBuckets().flatMap { (bucket, names) -> (names - declared).map { "$bucket: $it" } }
        assertTrue(
            stale.isEmpty(),
            "These classified names are no longer parameters of AgentWindow — a classification for a removed " +
                "parameter pre-approves a future one with the same name, and it means the scan drifted from the " +
                "source. Delete them:\n" + stale.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun theBucketsAreDisjoint_soNoParameterIsCountedTwice() {
        val seen = mutableSetOf<String>()
        val doubled = mutableListOf<String>()
        allBuckets().forEach { (_, names) -> names.forEach { if (!seen.add(it)) doubled += it } }
        assertTrue(doubled.isEmpty(), "a parameter is in more than one bucket: $doubled")
    }

    /**
     * The inert claim, measured. For each parameter in [chromeInertByMeasurement], render the header once at its
     * default and once at a chrome-provoking value; the header height must be identical. An entry that actually
     * shifts the header — the exact bug the coverage buckets exist to prevent someone hiding here — fails.
     */
    @Test
    fun theInertBucketIsMeasuredNotBelieved() {
        val baseline = headerHeight { agentId, m -> AgentWindow(agentId, vm(), modifier = m) }
        val degraded = Capabilities(
            CapabilityStatus.UNAVAILABLE, CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED,
            CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
        )
        // Each provoked render sets exactly one inert parameter to the value that makes it render the most.
        val provoked: Map<String, @Composable (String, Modifier) -> Unit> = mapOf(
            "modifier" to { agentId, m -> AgentWindow(agentId, vm(), modifier = m.padding(20.dp)) },
            "capabilities" to { agentId, m -> AgentWindow(agentId, vm(), modifier = m, capabilities = degraded) },
            "capabilitiesLoading" to { agentId, m -> AgentWindow(agentId, vm(), modifier = m, capabilitiesLoading = true) },
            "provider" to { agentId, m -> AgentWindow(agentId, vm(), modifier = m, provider = ProviderInfo.CLAUDE) },
            "onCapabilityBadgeClick" to { agentId, m -> AgentWindow(agentId, vm(), modifier = m, onCapabilityBadgeClick = { error("unused") }) },
        )
        assertEquals(
            chromeInertByMeasurement.keys, provoked.keys,
            "every chromeInertByMeasurement entry needs a provoking render here, and vice versa — otherwise a " +
                "name is 'measured' in prose only",
        )
        provoked.forEach { (name, content) ->
            assertEquals(
                baseline,
                headerHeight(content),
                "CYP-363 [$name]: setting this parameter to a chrome-provoking value changed the header height " +
                    "from $baseline dp. It is not inert — move it out of chromeInertByMeasurement into the bucket " +
                    "that varies it, and give ChromeState a dimension for it.",
            )
        }
    }

    // --- rendering helpers ------------------------------------------------------------------------------------

    private fun vm() = AgentViewModel(StubAgentSession(), AGENT_ID, canControl = true)

    /** Header height of `AgentWindow`, rendered at a fixed size wide enough that the header is its stable line. */
    private fun headerHeight(content: @Composable (String, Modifier) -> Unit): Float {
        var h = Float.NaN
        runComposeUiTest {
            setContent { MaterialTheme { Box(Modifier.size(400.dp, 900.dp)) { content(AGENT_ID, Modifier) } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentViewTags.header(AGENT_ID)).fetchSemanticsNodes().isNotEmpty()
            }
            val r = onNodeWithTag(AgentViewTags.header(AGENT_ID), useUnmergedTree = true).getUnclippedBoundsInRoot()
            h = (r.bottom - r.top).value
        }
        return h
    }

    // --- source scan ------------------------------------------------------------------------------------------

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
            if (started && depth <= 0 && raw.contains(")")) {
                topLevelParam(raw, i)?.let(params::add)
                break
            }
            if (i == open) continue
            topLevelParam(raw, i)?.let(params::add)
        }
        return params
    }

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

    private companion object {
        const val AGENT_ID = "po"
    }
}
