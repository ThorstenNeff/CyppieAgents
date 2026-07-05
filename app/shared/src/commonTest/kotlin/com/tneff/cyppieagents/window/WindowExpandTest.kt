package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-241 — titlebar double-click Expand+Center / Restore toggle. Pure state/reducer coverage of the §9 invariants
 * (geometry runs OUTSIDE Compose against [WindowReducer]/[WindowManagerState], like the rest of the window logic);
 * the gesture + `stateDescription` a11y binding are covered in the render test (`WindowManagerA11yTest`).
 */
class WindowExpandTest {

    // 1200×800 host. "comm" is a content window (wider prefs/floor), "acl" a default window.
    private fun hostedState() = WindowManagerState(
        listOf(
            WindowState("comm", "Comm", 100f, 100f, 300f, 200f),
            WindowState("acl", "ACL", 500f, 100f, 300f, 200f),
        ),
        contentWindowIds = setOf("comm"),
    ).apply { updateHostSize(1200f, 800f) }

    private fun win(state: WindowManagerState, id: String) = state.windows.first { it.id == id }

    // --- geometry (§1, §9.1/§9.2): preferred, capped, floored, centered in the usable area ---

    @Test
    fun expandCentered_contentWindow_isPreferredSize_centeredBelowBand() {
        val target = WindowReducer.expandCentered(
            WindowState("comm", "Comm", 100f, 100f, 300f, 200f), 1200f, 800f, isContent = true,
        )
        assertEquals(640f, target.width)   // EXPAND_PREFERRED_CONTENT_W (≤ usableW 1152)
        assertEquals(560f, target.height)  // EXPAND_PREFERRED_CONTENT_H (≤ usableH 696)
        assertEquals((1200f - 640f) / 2f, target.x) // centered horizontally = 280
        assertEquals(HOST_AFFORDANCE_BAND + (800f - HOST_AFFORDANCE_BAND - 560f) / 2f, target.y) // = 148, below band
    }

    @Test
    fun expandCentered_defaultWindow_usesSmallerPrefs() {
        val target = WindowReducer.expandCentered(
            WindowState("acl", "ACL", 0f, 0f, 300f, 200f), 1200f, 800f, isContent = false,
        )
        assertEquals(460f, target.width)  // EXPAND_PREFERRED_DEFAULT_W
        assertEquals(380f, target.height) // EXPAND_PREFERRED_DEFAULT_H
    }

    @Test
    fun expandCentered_neverFullscreen_marginOnEveryEdge() {
        val h = 1200f
        val v = 800f
        val target = WindowReducer.expandCentered(WindowState("comm", "C", 0f, 0f, 300f, 200f), h, v, isContent = true)
        assertTrue(target.width <= h - 2f * EXPAND_MARGIN, "never fills width (margin both sides)")
        assertTrue(target.height <= v - HOST_AFFORDANCE_BAND - 2f * EXPAND_MARGIN, "never fills height (band + margin)")
        assertTrue(target.x >= EXPAND_MARGIN - 0.001f && target.y >= HOST_AFFORDANCE_BAND, "inset from edges + below band")
    }

    @Test
    fun expandCentered_tinyViewport_capsToUsable_flooredToTypeMin() {
        // usableW = 400 - 48 = 352 < prefW(640) → width capped to 352 (still ≥ content floor 320).
        val target = WindowReducer.expandCentered(WindowState("comm", "C", 0f, 0f, 300f, 200f), 400f, 400f, isContent = true)
        assertTrue(target.width <= 352f + 0.001f, "capped to usable width")
        assertTrue(target.width >= TILED_CONTENT_WINDOW_MIN_WIDTH - 0.001f, "floored to the content type-min")
    }

    @Test
    fun expandCentered_hostNotMeasured_returnsUnchanged() {
        val w = WindowState("comm", "C", 12f, 34f, 300f, 200f)
        assertEquals(w, WindowReducer.expandCentered(w, 0f, 0f, isContent = true))
    }

    // --- toggle + anchor (§2, §5, §9.3/§9.6) ---

    @Test
    fun firstDoubleClick_expands_setsAnchor_andFocuses() {
        val state = hostedState()
        state.toggleExpand("comm")
        val comm = win(state, "comm")
        assertEquals(640f, comm.width); assertEquals(560f, comm.height)
        assertEquals(280f, comm.x); assertEquals(148f, comm.y)
        assertTrue(state.isExpanded("comm"))
        assertEquals("comm", state.focusedId) // §5: expand brings to front
    }

    @Test
    fun secondDoubleClick_untouched_restoresOriginal_dropsAnchor() {
        val state = hostedState()
        val original = win(state, "comm")
        state.toggleExpand("comm")
        state.toggleExpand("comm")
        assertEquals(original, win(state, "comm")) // geometry back to exact original
        assertFalse(state.isExpanded("comm"))
        assertEquals("comm", state.focusedId) // restore also focuses (§5)
    }

    @Test
    fun move_afterExpand_clearsAnchor_soSecondDoubleClickIsFreshExpand_notDead() {
        val state = hostedState()
        state.toggleExpand("comm")          // expand
        state.moveBy("comm", 20f, 10f)      // manual move → anchor invalidated (§2)
        assertFalse(state.isExpanded("comm"))
        state.toggleExpand("comm")          // NOT a dead click → fresh Expand+Center
        assertTrue(state.isExpanded("comm"))
        assertEquals(280f, win(state, "comm").x) // re-centered to the expand target
        assertEquals(148f, win(state, "comm").y)
    }

    @Test
    fun resize_afterExpand_clearsAnchor() {
        val state = hostedState()
        state.toggleExpand("acl")
        assertTrue(state.isExpanded("acl"))
        state.resizeBy("acl", 10f, 10f)
        assertFalse(state.isExpanded("acl")) // §2: a manual resize invalidates the anchor
    }

    @Test
    fun fit_clearsAllAnchors() {
        val state = hostedState()
        state.toggleExpand("comm")
        state.toggleExpand("acl")
        assertTrue(state.isExpanded("comm") && state.isExpanded("acl"))
        state.fit()
        assertFalse(state.isExpanded("comm")); assertFalse(state.isExpanded("acl")) // §4: re-tile drops every anchor
        assertTrue(state.expandAnchors.isEmpty())
    }

    @Test
    fun toggleExpand_hostNotMeasured_isNoOp() {
        val state = WindowManagerState(listOf(WindowState("comm", "C", 10f, 10f, 300f, 200f)), setOf("comm"))
        state.toggleExpand("comm")
        assertFalse(state.isExpanded("comm"))
        assertEquals(WindowState("comm", "C", 10f, 10f, 300f, 200f), win(state, "comm"))
    }
}
