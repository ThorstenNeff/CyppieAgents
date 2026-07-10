package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-373 — **the width floor holds on every path that can change a width, not only on the ones that create it.**
 *
 * `WindowReducer` had `invariantMinHeight(isContent)` and **no width twin**. Placement asked `minWidthFor(id)` and
 * got 320 dp for a content window; the *mutating* paths — `resizeBy`, the host-shrink clamp, the Expand→Restore
 * clamp — passed `minHeight` and let `minWidth` default to `MIN_WINDOW_WIDTH = 160`. Measured on untouched
 * `develop`: `resizeBy("po", -10_000, 0)` on a content window returned **160 dp**.
 *
 * **The width was protected when a window was created and unprotected the moment anyone touched it.** That is
 * CYP-338's asymmetry mirrored onto the other axis, and it silently undoes CYP-369: the agent header needs 298 dp
 * of its 304 usable dp at 320 dp width. At 160 dp only 144 dp are usable, and Stop and Restart lose their width
 * again. **One drag of the resize grip and the operator cannot stop an agent.**
 *
 * **This test enumerates the paths rather than the symptom.** A test that only pinned `resizeBy` would have been
 * green on `develop` for the clamp path, and green again for whatever fourth mutating path is added next year.
 * Every function that can shrink a window appears below; if you add one, add it here.
 *
 * **Why both halves matter.** Asserting only "a content window never goes below 320" is satisfied by making the
 * floor 320 for *everything* — which would be a different, invisible defect: the ACL and Event-Log windows are
 * reading surfaces with nothing to type into, and they legitimately shrink to 160 dp. The mutation
 * `invariantMinWidth(_) = 320f` must go **red**, and it does, on
 * [nonContentWindows_keepThePlainFloor_soAWiderFloorIsNotJustAppliedEverywhere]. The pair separates *"the width is
 * protected"* from *"the width is the same everywhere"*.
 *
 * Class membership is production's, not this test's invention: `AgentShell` treats every window as content
 * **except** ACL / Event-Log (browse + tail) / Settings / Agent-Management / Product-Lead / Roster / Compact.
 * Agent **and Comm** windows carry a composer and are content — the Comm window is not an exception to this.
 */
class Cyp373InvariantMinWidthGuardTest {

    private val content = "po"
    private val reading = "acl"

    private fun state() = WindowManagerState(
        listOf(
            WindowState(content, "PO", 0f, 0f, 400f, 500f),
            WindowState(reading, "ACL", 600f, 0f, 300f, 300f),
        ),
        contentWindowIds = setOf(content),
    ).apply { updateHostSize(1200f, 900f) }

    private fun widthOf(state: WindowManagerState, id: String) = state.windows.first { it.id == id }.width

    /**
     * Every mutating path, named. `shrink` drives the window as far below the floor as the API allows; the floor
     * is what must stop it. Each lambda is one door into a width change.
     */
    private val shrinkingPaths: Map<String, (WindowManagerState, String) -> Unit> = mapOf(
        "resizeBy (the resize grip)" to { s, id -> s.resizeBy(id, -10_000f, 0f) },
        "updateHostSize (host shrink / rotation / split-screen)" to { s, _ -> s.updateHostSize(200f, 900f) },
        "toggleExpand → Restore (the clamp on the way back)" to { s, id ->
            s.resizeBy(id, -10_000f, 0f) // anchor cleared; window now at the floor
            s.toggleExpand(id) // expand
            s.toggleExpand(id) // restore → clampSizeToBounds
        },
        "fit (re-tile everything)" to { s, _ -> s.updateHostSize(400f, 900f); s.fit() },
    )

    @Test
    fun contentWindows_neverSinkBelowTheWidthFloor_onAnyPathThatCanShrinkThem() {
        shrinkingPaths.forEach { (path, shrink) ->
            val s = state()
            shrink(s, content)
            val w = widthOf(s, content)
            assertTrue(
                w >= TILED_CONTENT_WINDOW_MIN_WIDTH,
                "CYP-373 [$path]: a content window shrank to $w dp, below the " +
                    "$TILED_CONTENT_WINDOW_MIN_WIDTH dp floor. The header then has ${w - 16f} dp of usable width; " +
                    "CYP-369 needs 298. Stop and Restart lose their width and the operator cannot stop an agent. " +
                    "This path is not passing `WindowReducer.invariantMinWidth(isContent)`.",
            )
        }
    }

    /**
     * The other half of the pair. Without it, `invariantMinWidth(_) = 320f` — a blanket floor — passes everything
     * above while quietly taking 160 dp of screen from every reading surface. A guard that cannot fail in the
     * over-application direction is only half a guard.
     */
    @Test
    fun nonContentWindows_keepThePlainFloor_soAWiderFloorIsNotJustAppliedEverywhere() {
        val s = state()
        s.resizeBy(reading, -10_000f, 0f)
        assertEquals(
            MIN_WINDOW_WIDTH,
            widthOf(s, reading),
            "CYP-373: a reading surface (ACL/Event-Log/Settings/…) must still reach $MIN_WINDOW_WIDTH dp. It has no " +
                "composer to protect. If this fails, the width floor was applied to every class instead of the " +
                "content class — 'the width is protected' is not 'the width is the same everywhere'.",
        )
        assertTrue(
            MIN_WINDOW_WIDTH < TILED_CONTENT_WINDOW_MIN_WIDTH,
            "precondition: the two floors must differ, or the assertion above proves nothing",
        )
    }

    /** The single source, stated once. Both classes, both directions — the whole content of the decision. */
    @Test
    fun invariantMinWidth_isTheOnlyPlaceTheClassDecisionIsMade() {
        assertEquals(TILED_CONTENT_WINDOW_MIN_WIDTH, WindowReducer.invariantMinWidth(isContent = true))
        assertEquals(MIN_WINDOW_WIDTH, WindowReducer.invariantMinWidth(isContent = false))
    }

    /**
     * The host may be narrower than the floor. The width invariant wins and the window overflows, exactly as the
     * height twin already behaves (`coerceIn(min, maxOf(min, host))`). Pinned because the tempting "fix" — letting
     * the host win — reintroduces the defect on any small viewport, which is where it hurts most.
     */
    @Test
    fun aHostNarrowerThanTheFloor_doesNotWinAgainstTheInvariant() {
        val s = state()
        s.updateHostSize(200f, 900f)
        assertEquals(TILED_CONTENT_WINDOW_MIN_WIDTH, widthOf(s, content))
    }
}
