package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
