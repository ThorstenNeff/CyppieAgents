package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-100: [WindowManagerState.syncWindows] reconciles a changed window set **without disturbing the
 * windows that stay** — an added agent gets a free slot, a removed one is dropped, and a same-membership
 * call only refreshes titles. Pure (no Compose). Each guard is mutation-provable:
 * - preserve: replace syncWindows with a full re-tile → the moved window's position test goes RED.
 * - free slot: drop the overlap check in placeNewWindow → the non-overlap test goes RED.
 * - drop gone / order: skip removal or the windowOrder update → those tests go RED.
 */
class WindowSyncTest {

    private fun overlaps(a: WindowState, b: WindowState): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    /** A host with explicit SMALL windows so there is genuine free space for a new one to land in. */
    private fun smallState(vararg windows: WindowState): WindowManagerState {
        val state = WindowManagerState(windows.toList())
        state.updateHostSize(1200f, 800f)
        return state
    }

    private fun win(id: String, x: Float, y: Float) = WindowState(id, id.uppercase(), x, y, 200f, 150f)

    @Test
    fun resetTo_laysOutTheWholeSet() {
        val state = WindowManagerState(emptyList())
        state.updateHostSize(1200f, 800f)
        state.resetTo(listOf("a" to "A", "b" to "B"))
        assertEquals(setOf("a", "b"), state.windows.map { it.id }.toSet())
        assertEquals(listOf("a", "b"), state.windowOrder)
    }

    @Test
    fun syncWindows_addNew_preservesExistingPositions_andPlacesNewFree() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        // Drag 'a' to a custom spot the user chose.
        state.moveBy("a", 37f, 41f)
        val aBefore = state.windows.first { it.id == "a" }
        val bBefore = state.windows.first { it.id == "b" }

        state.syncWindows(listOf("a" to "A", "b" to "B", "c" to "C"))

        // The kept windows keep their EXACT geometry — no reshuffle on add.
        val aAfter = state.windows.first { it.id == "a" }
        val bAfter = state.windows.first { it.id == "b" }
        assertEquals(aBefore.x, aAfter.x); assertEquals(aBefore.y, aAfter.y)
        assertEquals(bBefore.x, bAfter.x); assertEquals(bBefore.y, bAfter.y)
        // The new window is present and overlaps neither kept window (a free slot).
        val c = state.windows.first { it.id == "c" }
        assertTrue(
            state.windows.filter { it.id != "c" }.none { overlaps(it, c) },
            "new window must be placed in a free, non-overlapping slot",
        )
        assertEquals(listOf("a", "b", "c"), state.windowOrder)
    }

    /**
     * CYP-338 — the regression that let the defect ship. A content window that appears **after** the first
     * layout (the agent list arrives asynchronously) goes through [placeNewWindow], which floored every new
     * window at the plain [MIN_WINDOW_HEIGHT]. At 120 dp an agent window's 176 dp chrome cannot lay out the
     * composer at all, and the transcript collapses to 0 dp.
     *
     * Asserts the **invariant**, not the constant: `>= TILED_CONTENT_WINDOW_MIN_HEIGHT` would be satisfied by
     * a mutated `TILED_CONTENT_WINDOW_MIN_HEIGHT = 120` and prove only that a constant equals itself. The
     * spec's mutation probe (TILED → 120) turns this RED because 120 < [CONTENT_WINDOW_MIN_HEIGHT].
     * The path matters: `syncWindows`, never `resetTo` — a `resetTo` assertion would be green and worthless.
     */
    @Test
    fun syncWindows_addContentWindowAfterFirstLayout_neverBelowComposerInvariant() {
        val state = WindowManagerState(emptyList())
        state.updateHostSize(1600f, 1000f)
        state.resetTo(listOf("comm" to "Kommunikation"), contentWindowIds = setOf("comm"))

        // The agent arrives late (async fetch) → syncWindows → placeNewWindow, NOT tile.
        state.syncWindows(
            listOf("comm" to "Kommunikation", "backend" to "Backend"),
            contentWindowIds = setOf("comm", "backend"),
        )

        val agent = state.windows.first { it.id == "backend" }
        assertTrue(
            agent.height >= CONTENT_WINDOW_MIN_HEIGHT,
            "a late-arriving content window must keep its composer: was ${agent.height} dp, " +
                "invariant is >= $CONTENT_WINDOW_MIN_HEIGHT",
        )
    }

    /** CYP-338 guard: the taller floor is scoped to [contentWindowIds] — badge windows keep the 120 dp floor. */
    @Test
    fun syncWindows_addNonContentWindow_keepsPlainMinHeight() {
        val state = WindowManagerState(emptyList())
        state.updateHostSize(1600f, 1000f)
        state.resetTo(listOf("comm" to "Kommunikation"), contentWindowIds = setOf("comm"))

        state.syncWindows(
            listOf("comm" to "Kommunikation", "badge" to "Badge"),
            contentWindowIds = setOf("comm"),
        )

        assertEquals(MIN_WINDOW_HEIGHT, state.windows.first { it.id == "badge" }.height)
    }

    /** CYP-338 must not undo CYP-100: a late agent resizes nobody — the kept windows' SIZE is untouched too. */
    @Test
    fun syncWindows_addContentWindow_doesNotResizeExistingWindows() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        val before = state.windows.associate { it.id to (it.width to it.height) }

        state.syncWindows(
            listOf("a" to "A", "b" to "B", "backend" to "Backend"),
            contentWindowIds = setOf("backend"),
        )

        for ((id, size) in before) {
            val after = state.windows.first { it.id == id }
            assertEquals(size.first, after.width, "existing window $id must keep its width")
            assertEquals(size.second, after.height, "existing window $id must keep its height")
        }
    }

    /** CYP-338 spec §7.2 — the user must not be able to drag the composer back out of a content window. */
    @Test
    fun resizeBy_contentWindow_cannotShrinkBelowComposerInvariant() {
        val state = WindowManagerState(emptyList())
        state.updateHostSize(1600f, 1000f)
        state.resetTo(listOf("backend" to "Backend", "acl" to "ACL"), contentWindowIds = setOf("backend"))

        state.resizeBy("backend", -10_000f, -10_000f)
        state.resizeBy("acl", -10_000f, -10_000f)

        assertTrue(
            state.windows.first { it.id == "backend" }.height >= CONTENT_WINDOW_MIN_HEIGHT,
            "a content window keeps its composer no matter how hard it is dragged smaller",
        )
        // ...and the rule does not drag the right cases along: a reading window may still reach 120 dp.
        assertEquals(MIN_WINDOW_HEIGHT, state.windows.first { it.id == "acl" }.height)
    }

    /** CYP-338 spec §7.3 — the invariant holds class-wide, across every path, not just at creation. */
    @Test
    fun everyPath_keepsContentWindowsAboveComposerInvariant() {
        val content = setOf("backend", "comm")
        val state = WindowManagerState(emptyList())

        // Fallback path: no host measured yet.
        state.resetTo(listOf("backend" to "B", "comm" to "C", "acl" to "A"), contentWindowIds = content)
        assertAllContentAboveInvariant(state, content, "fallback (host unmeasured)")

        // tile path: a crowded, short host squeezes the cells.
        state.updateHostSize(700f, 520f)
        state.resetTo(listOf("backend" to "B", "comm" to "C", "acl" to "A", "log" to "L"), contentWindowIds = content)
        assertAllContentAboveInvariant(state, content, "tile (crowded host)")

        // placeNewWindow path.
        state.syncWindows(
            listOf("backend" to "B", "comm" to "C", "acl" to "A", "log" to "L", "frontend" to "F"),
            contentWindowIds = content + "frontend",
        )
        assertAllContentAboveInvariant(state, content + "frontend", "placeNewWindow")

        // expandCentered + clamp paths.
        state.toggleExpand("backend")
        assertAllContentAboveInvariant(state, content + "frontend", "expandCentered")
        state.updateHostSize(640f, 500f)
        assertAllContentAboveInvariant(state, content + "frontend", "clampSizeToBounds after host resize")
    }

    private fun assertAllContentAboveInvariant(state: WindowManagerState, content: Set<String>, path: String) {
        for (w in state.windows.filter { it.id in content }) {
            assertTrue(
                w.height >= CONTENT_WINDOW_MIN_HEIGHT,
                "$path: content window '${w.id}' fell to ${w.height} dp, below the $CONTENT_WINDOW_MIN_HEIGHT dp composer invariant",
            )
        }
    }

    @Test
    fun syncWindows_removeGoneWindow() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        state.syncWindows(listOf("a" to "A"))
        assertNull(state.windows.firstOrNull { it.id == "b" })
        assertEquals(listOf("a"), state.windowOrder)
    }

    @Test
    fun syncWindows_sameMembership_keepsPositions_refreshesTitle() {
        val state = smallState(win("a", 16f, 72f), win("b", 320f, 72f))
        state.moveBy("a", 50f, 60f)
        val aBefore = state.windows.first { it.id == "a" }

        state.syncWindows(listOf("a" to "A-renamed", "b" to "B"))

        val aAfter = state.windows.first { it.id == "a" }
        // Membership unchanged → never re-tiles: positions are byte-identical, only the title refreshes.
        assertEquals(aBefore.x, aAfter.x); assertEquals(aBefore.y, aAfter.y)
        assertEquals("A-renamed", aAfter.title)
    }
}
