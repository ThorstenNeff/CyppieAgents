package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.AgentShell
import com.tneff.cyppieagents.ShellConfig
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.AgentLifecycleApi
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.auth.UserTier
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.crossproject.StubCrossProjectRepository
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.project.StubProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-363 — **the chrome floor is re-derived from the composition, never maintained by hand.**
 *
 * [CONTENT_WINDOW_MIN_HEIGHT] said 301 dp: title bar 64 + header 164 + composer 73. CYP-333 then inserted a
 * fourth chrome row (`ModeToggleRow`) and the constant did not follow. At 301 dp the real shell rendered a window
 * that was **100 % chrome**: `transcript = 0 dp`, `input = 0 dp`. The invariant CYP-338 closed had reopened —
 * not because someone changed a number, but because nobody had to.
 *
 * **This test contains no chrome number.** It measures the composition twice and compares the two measurements:
 *
 *  * *natural*: `AgentWindow` in a host so tall that every row gets its intrinsic height;
 *  * *at the floor*: the same window in exactly the height the constants promise.
 *
 * A row that appears, disappears or changes height moves the natural sum, and the equality in
 * [theFloorIsExactlyTheTallestShippedChrome] goes red until the constant follows. That is the delivery — the
 * number is only its current value.
 *
 * **Why the natural measurement cannot be taken at the floor.** `Column` gives its *unweighted* children what is
 * left after the weighted one, so a height read at the floor is a **remainder, not a height**: `ModeToggleRow`
 * measures 84 dp naturally and 73 dp at the old floor, and the composer row measures 73 dp naturally and **5 dp**
 * there — with a 0 dp input inside it. Deriving the constant from a floor reading would have written the very
 * defect into the constant meant to prevent it. Every number below is read from the generous rendering.
 *
 * **Why heights and not visibility** — measured, and *not* what the ticket predicted. The ticket's MUT-5 claimed
 * that swapping the height comparison for `assertIsDisplayed()` keeps this test green at the old floor. It does
 * not: at 301 dp the input collapses to **0 dp** and `assertIsDisplayed` goes red too. But the weakened assertion
 * is green for every floor from **280 dp** upward, where the input renders **16 dp** — a text field at a quarter
 * of its natural 57 dp, clipped, unusable, and "displayed". The shipped defect happened to be past that edge;
 * the next one need not be. `assertIsDisplayed` is true of a 1 dp sliver and cannot express "the operator can
 * type", so the floor assertions compare the input against its own natural height.
 */
@OptIn(ExperimentalTestApi::class)
class ContentWindowChromeFloorGuardTest {

    /**
     * The transcript's promised minimum at [TILED_CONTENT_WINDOW_MIN_HEIGHT] — three text lines (CYP-338,
     * `min-window-height-spec.md` §2.2). Not chrome: a *deliberate* size, the one number this ticket chooses
     * rather than measures. Everything else is read off the composition.
     */
    private val transcriptFloorDp = 90f

    /** The class's own minimum width — the header wraps here, which is what makes the chrome as tall as it gets. */
    private val width = TILED_CONTENT_WINDOW_MIN_WIDTH

    // ---------------------------------------------------------------------------------------------------------
    // Every chrome state the composition can render. The floor is cut to the TALLEST of them.
    //
    //  · `canControl`        — a non-operator gets the read-only `workspace_operator_only` disclosure.
    //  · `terminalAvailable` — a wired terminal (`terminalContent != null`) enables the Shell segment and, today,
    //                          suppresses every note. NOT shipped yet (`WORKTREE_SHELL_LIVE_ENABLED = false`);
    //                          enumerated because CYP-333's live-flip makes it the shipped state and adds a
    //                          `labelSmall` line to it. A floor that only knows today's states would go stale on
    //                          that merge exactly as it went stale on CYP-333's first half. This is the state the
    //                          guard exists for.
    //  · `terminalGatedNote` — the honest "available once the worktree-shell backend lands" note. Only rendered
    //                          for an operator with no terminal wired (`terminalGatedNote && !terminalAvailable`).
    //  · `lifecycleError`    — `LifecycleErrorRow`, prepended when a lifecycle action fails.
    //
    // CYP-363 follow-up: `lifecycleError` was first enumerated WITHOUT a number, on the argument that it is
    // transient and sizing every window for it costs an error most operators never see. Measured, that argument
    // does not survive: at the old floor the row squeezed the composer from 57 dp to 37 dp — and the composer
    // still reported `assertIsDisplayed`. The floor's own name is `theFloorIsExactlyTheTallestShippedChrome`;
    // an exception for the state in which an agent FAILED TO START is an exception for the moment the operator
    // most needs to type. `301` was a floor that carried most states. Coordinator ruled: carry this one too.
    // The 20 dp it costs are 20 dp of minimum WINDOW height, not of transcript — at the floor the transcript is
    // 0 dp either way, and the tiled minimum still renders its full three lines.
    // ---------------------------------------------------------------------------------------------------------

    private data class ChromeState(
        val canControl: Boolean,
        val terminalAvailable: Boolean,
        val terminalGatedNote: Boolean,
        val lifecycleError: Boolean,
    ) {
        override fun toString() =
            "canControl=$canControl, terminalAvailable=$terminalAvailable, " +
                "terminalGatedNote=$terminalGatedNote, lifecycleError=$lifecycleError"
    }

    /**
     * The full cross-product, minus the one combination the composition **cannot** reach. Enumerated, not
     * sampled: a state left out is a state the floor lies about.
     *
     * `lifecycleError && !canControl` is impossible, and not merely untested — `AgentViewModel`'s lifecycle
     * actions open with `if (!canControl) return` (fail-closed, CYP-317). A non-operator has no action that can
     * fail, so the row can never appear for one. Rendering it does not yield a shorter window; it yields a test
     * that waits five seconds for a row that will never come. The exclusion is a **property of the production
     * code**, read there, not an accommodation of a timeout.
     */
    private val shippedStates: List<ChromeState> = listOf(false, true).flatMap { control ->
        listOf(false, true).flatMap { available ->
            listOf(false, true).flatMap { gated ->
                listOf(false, true).map { error -> ChromeState(control, available, gated, error) }
            }
        }
    }.filterNot { it.lifecycleError && !it.canControl }

    // --- G1 ---------------------------------------------------------------------------------------------------

    /**
     * The floor **is** the tallest shipped chrome: the title bar the window frame draws, plus every fixed row
     * `AgentWindow` stacks above and below its weighted content rectangle. Bidirectional — a row added without
     * raising the constant and a constant raised without a row both go red.
     */
    @Test
    fun theFloorIsExactlyTheTallestShippedChrome() {
        val titleBar = measureTitleBarOnTheRealShell()
        val tallest = shippedStates.maxOf { naturalChromeSum(it) }
        assertEquals(
            titleBar + tallest,
            CONTENT_WINDOW_MIN_HEIGHT,
            "CYP-363: CONTENT_WINDOW_MIN_HEIGHT must equal the measured chrome of the tallest shipped state.\n" +
                "  title bar (real shell)     = $titleBar\n" +
                shippedStates.joinToString("\n") { "  chrome[$it] = ${naturalChromeSum(it)}" } +
                "\n  => floor should be ${titleBar + tallest}, constant says $CONTENT_WINDOW_MIN_HEIGHT.\n" +
                "A chrome row came or went. Update the constant to the measured sum — do not soften this test.",
        )
    }

    /**
     * At the floor **every** shipped state keeps its composer whole, and the **tallest** state — the one the floor
     * is cut to — has nothing but chrome. Both halves are load-bearing: the first is the CYP-338 invariant stated
     * in heights rather than in `assertIsDisplayed`; the second says the floor is a floor and not merely a
     * large-enough number.
     *
     * A shorter state legitimately keeps the difference as transcript (a member's toggle row is 16 dp shorter than
     * an operator's gated one). Demanding `transcript == 0` of *every* state would be demanding that all four
     * states be equally tall — my first version of this test did exactly that and went red on a correct layout.
     */
    @Test
    fun atTheFloor_everyStateKeepsItsComposer_andTheTallestIsChromeOnly() {
        val titleBar = measureTitleBarOnTheRealShell()
        val tallest = shippedStates.maxOf { naturalChromeSum(it) }
        shippedStates.forEach { state ->
            val natural = measureAgentWindow(state, availableHeight = GENEROUS)
            val atFloor = measureAgentWindow(state, availableHeight = CONTENT_WINDOW_MIN_HEIGHT - titleBar)
            assertEquals(
                natural.inputHeight,
                atFloor.inputHeight,
                "CYP-338/363 [$state]: at the floor the input row must keep its full natural height " +
                    "(${natural.inputHeight} dp). It measured ${atFloor.inputHeight} dp — the composer is being " +
                    "squeezed, and a squeezed composer still passes assertIsDisplayed().",
            )
            assertEquals(
                tallest - natural.chromeSum,
                atFloor.transcriptHeight,
                "CYP-363 [$state]: at the floor a window may keep exactly the height the TALLEST state spends on " +
                    "chrome and this one does not — no more. The tallest state itself must reach 0 dp of " +
                    "transcript; anything above means CONTENT_WINDOW_MIN_HEIGHT sits above the real chrome sum.",
            )
        }
    }

    // --- G2 ---------------------------------------------------------------------------------------------------

    /**
     * At [TILED_CONTENT_WINDOW_MIN_HEIGHT] the transcript is exactly [transcriptFloorDp] — **exactly**, not at
     * least. A `>=` would let the next chrome row pass as long as *something* was left over, which is precisely
     * the give of the `weight(1f)` content rectangle that let CYP-363 ship: the window kept rendering, just with
     * nothing in it.
     */
    @Test
    fun atTheTiledMinimum_theTranscriptIsExactlyItsPromisedThreeLines() {
        val titleBar = measureTitleBarOnTheRealShell()
        assertEquals(
            transcriptFloorDp,
            TILED_CONTENT_WINDOW_MIN_HEIGHT - CONTENT_WINDOW_MIN_HEIGHT,
            "CYP-338: the tiled minimum is the floor plus $transcriptFloorDp dp of transcript, nothing else.",
        )
        val tallest = shippedStates.maxOf { naturalChromeSum(it) }
        shippedStates.forEach { state ->
            val natural = measureAgentWindow(state, availableHeight = GENEROUS)
            val measured = measureAgentWindow(state, availableHeight = TILED_CONTENT_WINDOW_MIN_HEIGHT - titleBar)
            assertEquals(
                transcriptFloorDp + (tallest - natural.chromeSum),
                measured.transcriptHeight,
                "CYP-338 [$state]: at TILED_CONTENT_WINDOW_MIN_HEIGHT the transcript must render exactly " +
                    "$transcriptFloorDp dp (three text lines) in the tallest state, plus whatever chrome this " +
                    "state does not spend. It measured ${measured.transcriptHeight} dp.",
            )
            assertEquals(
                natural.inputHeight,
                measured.inputHeight,
                "CYP-338 [$state]: the composer keeps its natural height above the floor too.",
            )
        }
    }

    // --- measurement ------------------------------------------------------------------------------------------

    private data class Measured(val transcriptHeight: Float, val inputHeight: Float, val chromeSum: Float)

    /**
     * The title bar belongs to the window frame, not to `AgentWindow`, so it is read off the **real shell** —
     * the composition an operator sees — rather than reconstructed from a `WindowFrame` call the test writes.
     * That is the failure mode the CYP-363 spec itself fell into: a simplified composition measures the test's
     * idea of the window, not the window.
     */
    private fun measureTitleBarOnTheRealShell(): Float {
        var titleBar = Float.NaN
        // 700 dp of host width: below ~600 the shell switches to the phone pager and no agent window is composed.
        runComposeUiTest {
            setContent { MaterialTheme { Box(Modifier.size(700.dp, 3_000.dp)) { RealShell() } } }
            awaitTag(AgentViewTags.input(AGENT_ID))
            titleBar = height(bounds(WindowTestTags.titleBar(AGENT_ID)))
        }
        return titleBar
    }

    /** `AgentWindow` alone, in a host of exactly [availableHeight] — the height the window frame leaves it. */
    private fun measureAgentWindow(state: ChromeState, availableHeight: Float): Measured {
        lateinit var measured: Measured
        runComposeUiTest {
            val vm = AgentViewModel(
                session = StubAgentSession(),
                agentId = AGENT_ID,
                lifecycle = if (state.lifecycleError) FailingLifecycleApi else null,
                canControl = state.canControl,
            )
            setContent {
                MaterialTheme {
                    Box(Modifier.size(width.dp, availableHeight.dp).testTag(HOST)) {
                        AgentWindow(
                            agentId = AGENT_ID,
                            viewModel = vm,
                            // A wired terminal is a SLOT, not a session: an empty box is enough to flip
                            // `terminalAvailable`. The default mode stays ORCHESTRATION, so the composer — the
                            // row this floor exists to protect — is still the one being measured.
                            terminalContent = if (state.terminalAvailable) ({ _, m -> Box(m) }) else null,
                            terminalGatedNote = state.terminalGatedNote,
                        )
                    }
                }
            }
            awaitTag(AgentViewTags.header(AGENT_ID))
            if (state.lifecycleError) {
                // The row is driven by a REAL failing action, not by a flag a test sets: the guard measures the
                // composition an operator gets, not one it invents.
                vm.start()
                awaitTag(AgentViewTags.lifecycleError(AGENT_ID))
            }
            val content = bounds(AgentViewTags.content(AGENT_ID))
            val input = onAllNodesWithTag(AgentViewTags.input(AGENT_ID), useUnmergedTree = true).fetchSemanticsNodes()
            // The host's height is MEASURED, not assumed: `Modifier.size` is a request, and the test root clips it.
            // Asking for 2000 dp and subtracting the transcript from 2000 would have silently inflated every chrome
            // sum by the clipped remainder — the same "a number I wrote down, not one the composition gave me"
            // mistake this whole ticket is about. Measured: a 2000 dp request renders ~500 dp.
            val hostHeight = height(bounds(HOST))
            measured = Measured(
                transcriptHeight = height(content),
                // The composer disappears entirely (not merely shrinks) if the column runs out — 0 dp, not absent.
                inputHeight = if (input.isEmpty()) 0f else height(bounds(AgentViewTags.input(AGENT_ID))),
                // Everything the column spends outside its weighted content rectangle.
                chromeSum = hostHeight - height(content),
            )
        }
        return measured
    }

    /** The natural (intrinsic) chrome of [state]: measured where nothing is a remainder. */
    private fun naturalChromeSum(state: ChromeState): Float = measureAgentWindow(state, GENEROUS).chromeSum

    private fun ComposeUiTest.awaitTag(tag: String) = waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect =
        onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun height(r: DpRect): Float = (r.bottom - r.top).value

    // --- fixtures ---------------------------------------------------------------------------------------------

    private object FailingLifecycleApi : AgentLifecycleApi {
        override suspend fun start(agentId: String): Unit = error("spawn_failed")
        override suspend fun stop(agentId: String): Unit = error("spawn_failed")
        override suspend fun restart(agentId: String): Unit = error("spawn_failed")
    }

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Composable
    private fun RealShell() = AgentShell(
        config = ShellConfig.dev().copy(operatorToken = "op-token"),
        tier = UserTier.OPERATOR,
        sessionFactory = { StubAgentSession() },
        commApi = FakeCommApi(),
        commLiveSource = StubCommLiveSource(),
        eventsApi = StubEventsApi(),
        eventsLiveSource = StubEventsSource(),
        agentManagementRepository = StubAgentManagementRepository(
            listOf(Agent(AGENT_ID, "Product Owner", Role.PO, AGENT_ID, AgentRunState.RUNNING)),
        ),
        projectRepository = StubProjectRepository(),
        crossProjectRepository = StubCrossProjectRepository(),
    )

    private companion object {
        const val AGENT_ID = "po"
        const val HOST = "cyp363.host"

        /** Any height with slack for the `weight(1f)` transcript; the host is measured, never assumed (see below). */
        const val GENEROUS = 2_000f
    }
}
