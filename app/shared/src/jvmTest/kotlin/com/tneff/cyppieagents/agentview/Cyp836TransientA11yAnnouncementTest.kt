package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * CYP-836 — the transient-state a11y **announce-class** render tooth. Pins, per feed × state, the exact `liveRegion`
 * the parity spec assigns (§2/§4, grounded in `A11Y-ANNOUNCEMENTS.md` — attention, not severity). The mutation for
 * each axis (Polite↔Assertive, or a liveRegion added where the spec forbids one, or ERROR→Polite) reddens the axis —
 * so the announce class is machine-pinned, not prose.
 *
 * Web-parity note (built to the spec + PO ruling, flagged for reconciliation): the CONTEXT_LOST/handoff banner is
 * Assertive here (unsolicited-critical, doctrine §1); web renders it `role=status`/polite.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp836TransientA11yAnnouncementTest {

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)
    private val noLiveRegion = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private val fakeTerminal: @androidx.compose.runtime.Composable (String, Modifier) -> Unit = { id, m ->
        Box(m.testTag("faketerm.$id")) { Text("TERM") }
    }

    /** Controllable mode repo: [gate] to hold the in-flight window; [rejectCode] to drive the swap_failed reject. */
    private class GateRepo(
        private val gate: CompletableDeferred<Unit>? = null,
        private val rejectCode: String? = null,
    ) : ModeRepository {
        override suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm {
            gate?.await()
            rejectCode?.let { throw ModeChangeException(it) }
            return ModeConfirm(confirmed = target)
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    fun lifecycleStatus_announcesPolite() = runComposeUiTest {
        setContent { MaterialTheme { AgentWindow(agentId = "backend", viewModel = remember { AgentViewModel(emptySession(), "backend") }) } }
        waitForIdle()
        // Ambient status of a looked-at window → Polite (parity web role=status). Mutation Polite→Assertive/none → RED.
        onNodeWithTag(AgentViewTags.status("backend"), useUnmergedTree = true).assert(liveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun lifecycleError_swapFailed_announcesAssertive_ownNode() = runComposeUiTest {
        val repo = GateRepo(rejectCode = "BUSY_TIMEOUT")
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), "backend", canControl = true, modeRepository = repo) }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.lifecycleError.value != null }
        // ERROR / mode_swap_failed = result of a submitted action → Assertive, own node (§3c; parity web role=alert).
        // Mutation Assertive→Polite → RED.
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assert(liveRegion(LiveRegionMode.Assertive))
    }

    // ── terminal (mode) ──────────────────────────────────────────────────────────────────────────────────────────
    @Test
    fun terminalModeToggle_confirmedFlip_announcesAssertive() = runComposeUiTest {
        setContent { MaterialTheme { AgentWindow(agentId = "backend", viewModel = remember { AgentViewModel(emptySession(), "backend", canControl = true) }) } }
        waitForIdle()
        // The confirmed (non-optimistic) view flip is a submitted-action result → Assertive. Mutation →Polite/none → RED.
        onNodeWithTag(AgentViewTags.modeToggle("backend"), useUnmergedTree = true).assert(liveRegion(LiveRegionMode.Assertive))
    }

    @Test
    fun terminalSwitching_hasNoLiveRegion_noPulse() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val repo = GateRepo(gate = gate)
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), "backend", canControl = true, modeRepository = repo) }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.modeSwitching.value }
        // in-flight switch = aria-busy parity, NO pulse. Mutation: add a liveRegion to the switching node → RED.
        onNodeWithTag(AgentViewTags.modeSwitching("backend"), useUnmergedTree = true).assert(noLiveRegion)
    }

    @Test
    fun terminalContextLostBanner_announcesAssertive() = runComposeUiTest {
        val ctxLost = AgentTerminalControlEvent(agentId = "backend", state = TerminalControlState.CONTEXT_LOST, heldBy = null, since = 1_000L)
        setContent { MaterialTheme { AgentWindow(agentId = "backend", viewModel = remember { AgentViewModel(emptySession(), "backend") }, control = ctxLost) } }
        waitForIdle()
        // Unsolicited-critical terminal-control change → Assertive (the once-per-appearance banner announce). Mutation
        // Assertive→Polite/none → RED. (Web = role=status/polite; Compose louder by doctrine + PO ruling — flagged.)
        onNodeWithTag(AgentViewTags.contextLostBanner("backend"), useUnmergedTree = true).assert(liveRegion(LiveRegionMode.Assertive))
    }

    // ── busy / token (the absence teeth — §3d de-dup + §2 token stays silent) ─────────────────────────────────────
    @Test
    fun busy_and_token_host_hasNoLiveRegion_absenceTooth() {
        // busy = ONE announce carrier (§3d: the lifecycle status node is it) — the `*` marker stays visual-only.
        // token = deliberately SILENT (§2: high-frequency ambient = noise; a live-region firing every token tick is
        // the textbook noise case). Their host is WindowManager (the FloatingWindow title bar), which today carries
        // ZERO liveRegion. A source scan pins that ABSENCE deterministically (the render-tree merge on the FloatingWindow
        // title bar makes a per-node render assertion unreliable here): a `liveRegion` added to the busy `*` or the
        // context-token node reddens this — no "helpful" announce can sneak in later.
        val src = locateSource("src/commonMain/kotlin/com/tneff/cyppieagents/window/WindowManager.kt").readText()
        assertFalse(
            src.contains("liveRegion"),
            "CYP-836 §2/§3d: WindowManager (the busy-`*` + context-token host) must carry NO liveRegion — busy is " +
                "de-duped onto the lifecycle status carrier and token is deliberately silent (high-frequency ambient = " +
                "noise). A liveRegion here is a regression (an ambient tick announcing on every turn/token update).",
        )
    }

    private fun locateSource(rel: String): java.io.File {
        var cur: java.io.File? = java.io.File(".").absoluteFile
        while (cur != null) {
            java.io.File(cur, "app/shared/$rel").let { if (it.isFile) return it }
            java.io.File(cur, rel).let { if (it.isFile) return it }
            cur = cur.parentFile
        }
        error("could not locate $rel")
    }
}
