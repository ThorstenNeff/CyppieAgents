package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowReducerTest {

    private fun sample(): List<WindowState> = listOf(
        WindowState("a", "A", 0f, 0f, 200f, 150f),
        WindowState("b", "B", 10f, 10f, 200f, 150f),
        WindowState("c", "C", 20f, 20f, 200f, 150f),
    )

    @Test
    fun bringToFront_middleWindow_movesItToEnd() {
        val result = WindowReducer.bringToFront(sample(), "b")
        assertEquals(listOf("a", "c", "b"), result.map { it.id })
    }

    @Test
    fun bringToFront_alreadyFrontWindow_keepsOrder() {
        val result = WindowReducer.bringToFront(sample(), "c")
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun bringToFront_unknownId_returnsUnchanged() {
        val original = sample()
        val result = WindowReducer.bringToFront(original, "does-not-exist")
        assertEquals(original.map { it.id }, result.map { it.id })
    }

    @Test
    fun moveBy_targetWindow_offsetsOnlyThatWindow() {
        val result = WindowReducer.moveBy(sample(), "b", 5f, -3f)
        val b = result.first { it.id == "b" }
        assertEquals(15f, b.x)
        assertEquals(7f, b.y)
        // Untouched windows keep their original position.
        assertEquals(0f, result.first { it.id == "a" }.x)
        assertEquals(20f, result.first { it.id == "c" }.x)
    }

    @Test
    fun moveBy_unknownId_returnsUnchanged() {
        val original = sample()
        val result = WindowReducer.moveBy(original, "does-not-exist", 10f, 10f)
        assertEquals(original, result)
    }

    @Test
    fun clampToBounds_draggedPastRightAndBottom_keepsMinVisibleInside() {
        val window = WindowState("a", "A", 5000f, 5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        // keepVisible default 48 → maxX = 1000-48, maxY = 800-48.
        assertEquals(952f, result.x)
        assertEquals(752f, result.y)
    }

    @Test
    fun clampToBounds_draggedPastLeftAndTop_keepsWindowReachable() {
        val window = WindowState("a", "A", -5000f, -5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        // Left edge may go off-screen but keepVisible of the right side stays in (48 - width).
        assertEquals(48f - 200f, result.x)
        // Title bar is kept at the very top so it stays grabbable.
        assertEquals(0f, result.y)
    }

    @Test
    fun clampToBounds_withinBounds_returnsUnchanged() {
        val window = WindowState("a", "A", 100f, 80f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 1000f, hostHeight = 800f)
        assertEquals(100f, result.x)
        assertEquals(80f, result.y)
    }

    @Test
    fun clampToBounds_unmeasuredHost_returnsUnchanged() {
        val window = WindowState("a", "A", 5000f, 5000f, 200f, 150f)
        val result = WindowReducer.clampToBounds(window, hostWidth = 0f, hostHeight = 0f)
        assertEquals(5000f, result.x)
        assertEquals(5000f, result.y)
    }

    @Test
    fun resizeBy_positiveDelta_growsWindow() {
        val result = WindowReducer.resizeBy(sample(), "a", 50f, 40f)
        val a = result.first { it.id == "a" }
        assertEquals(250f, a.width)
        assertEquals(190f, a.height)
    }

    @Test
    fun resizeBy_shrinkBelowMinimum_clampsToMinimum() {
        val result = WindowReducer.resizeBy(sample(), "a", -1000f, -1000f)
        val a = result.first { it.id == "a" }
        assertEquals(MIN_WINDOW_WIDTH, a.width)
        assertEquals(MIN_WINDOW_HEIGHT, a.height)
    }

    @Test
    fun resizeBy_growthPastMaximum_clampsToMaximum() {
        val result = WindowReducer.resizeBy(sample(), "a", 1000f, 1000f, maxWidth = 300f, maxHeight = 250f)
        val a = result.first { it.id == "a" }
        assertEquals(300f, a.width)
        assertEquals(250f, a.height)
    }

    @Test
    fun resizeBy_maximumBelowMinimum_neverGoesBelowMinimum() {
        // Degenerate host smaller than the minimum window: width/height stay at the minimum.
        val result = WindowReducer.resizeBy(sample(), "a", 1000f, 1000f, maxWidth = 10f, maxHeight = 10f)
        val a = result.first { it.id == "a" }
        assertEquals(MIN_WINDOW_WIDTH, a.width)
        assertEquals(MIN_WINDOW_HEIGHT, a.height)
    }

    @Test
    fun resizeDeltaForLayout_ltr_keepsSign() {
        assertEquals(12f, WindowReducer.resizeDeltaForLayout(12f, isRtl = false))
    }

    @Test
    fun resizeDeltaForLayout_rtl_invertsSign() {
        assertEquals(-12f, WindowReducer.resizeDeltaForLayout(12f, isRtl = true))
    }

    @Test
    fun clampSizeToBounds_windowLargerThanHost_shrinksToHost() {
        val window = WindowState("a", "A", 0f, 0f, 2000f, 2000f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 800f, hostHeight = 600f)
        assertEquals(800f, result.width)
        assertEquals(600f, result.height)
    }

    @Test
    fun clampSizeToBounds_windowWithinHost_returnsUnchanged() {
        val window = WindowState("a", "A", 0f, 0f, 300f, 200f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 800f, hostHeight = 600f)
        assertEquals(300f, result.width)
        assertEquals(200f, result.height)
    }

    @Test
    fun clampSizeToBounds_unmeasuredHost_returnsUnchanged() {
        val window = WindowState("a", "A", 0f, 0f, 2000f, 2000f)
        val result = WindowReducer.clampSizeToBounds(window, hostWidth = 0f, hostHeight = 0f)
        assertEquals(2000f, result.width)
        assertEquals(2000f, result.height)
    }

    @Test
    fun resizeHandle_constants_meetWcagMinimumTargetSize() {
        assertTrue(RESIZE_HANDLE_SIZE >= 24f) // WCAG 2.5.8
        assertTrue(RESIZE_HIT_SLOP >= RESIZE_HANDLE_SIZE)
    }

    @Test
    fun tile_threeItems_producesDistinctInBoundsPositions() {
        val items = listOf("po" to "PO", "fe" to "FE", "be" to "BE")
        val result = WindowReducer.tile(items, hostWidth = 1200f, hostHeight = 800f)

        assertEquals(3, result.size)
        // Tiled, not stacked: every window starts at a distinct position.
        assertEquals(3, result.map { it.x to it.y }.toSet().size)
        assertTrue(result.all { it.width >= MIN_WINDOW_WIDTH && it.height >= MIN_WINDOW_HEIGHT })
        assertTrue(result.all { it.x >= 0f && it.y >= 0f })
    }

    @Test
    fun tile_unmeasuredHost_stillKeepsMinimumSize() {
        val items = listOf("po" to "PO", "fe" to "FE")
        val result = WindowReducer.tile(items, hostWidth = 0f, hostHeight = 0f)

        assertEquals(2, result.size)
        assertTrue(result.all { it.width >= MIN_WINDOW_WIDTH && it.height >= MIN_WINDOW_HEIGHT })
    }

    @Test
    fun tile_emptyList_returnsEmpty() {
        assertEquals(emptyList(), WindowReducer.tile(emptyList(), 800f, 600f))
    }
}
