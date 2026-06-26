package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowManagerStateTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("a", "A", 0f, 0f, 200f, 150f),
            WindowState("b", "B", 0f, 0f, 200f, 150f),
        ),
    )

    @Test
    fun focusedId_initialState_isLastWindow() {
        assertEquals("b", twoWindows().focusedId)
    }

    @Test
    fun focus_lowerWindow_updatesFocusedIdToThatWindow() {
        val state = twoWindows()
        state.focus("a")
        assertEquals("a", state.focusedId)
        assertEquals(listOf("b", "a"), state.windows.map { it.id })
    }

    @Test
    fun moveBy_window_updatesItsPosition() {
        val state = twoWindows()
        state.moveBy("a", 5f, 7f)
        val a = state.windows.first { it.id == "a" }
        assertEquals(5f, a.x)
        assertEquals(7f, a.y)
    }

    @Test
    fun resizeBy_window_updatesItsSize() {
        val state = twoWindows()
        state.resizeBy("a", 20f, 10f)
        val a = state.windows.first { it.id == "a" }
        assertEquals(220f, a.width)
        assertEquals(160f, a.height)
    }

    @Test
    fun moveBy_pastHostBounds_isClampedAfterUpdateHostSize() {
        val state = WindowManagerState(listOf(WindowState("a", "A", 0f, 0f, 200f, 150f)))
        state.updateHostSize(1000f, 800f)
        state.moveBy("a", 5000f, 5000f)
        val a = state.windows.first { it.id == "a" }
        assertEquals(952f, a.x) // 1000 - keepVisible(48)
        assertEquals(752f, a.y) // 800 - keepVisible(48)
    }

    @Test
    fun moveBy_withoutHostSize_isNotClamped() {
        val state = WindowManagerState(listOf(WindowState("a", "A", 0f, 0f, 200f, 150f)))
        state.moveBy("a", 5000f, 5000f)
        val a = state.windows.first { it.id == "a" }
        assertEquals(5000f, a.x)
        assertEquals(5000f, a.y)
    }

    @Test
    fun updateHostSize_shrunkBelowWindow_reclampsStrandedWindowIntoView() {
        val state = WindowManagerState(listOf(WindowState("a", "A", 900f, 700f, 200f, 150f)))
        state.updateHostSize(500f, 400f)
        val a = state.windows.first { it.id == "a" }
        assertEquals(452f, a.x) // 500 - 48
        assertEquals(352f, a.y) // 400 - 48
        assertTrue(a.x < 500f && a.y < 400f)
    }
}
