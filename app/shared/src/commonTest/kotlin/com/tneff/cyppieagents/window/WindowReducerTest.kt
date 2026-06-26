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
