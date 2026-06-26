package com.tneff.cyppieagents.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-22: automated F2 (keyboard) / F3 (semantics) assertions for the window manager, complementing
 * the pure-logic unit tests (which cover the reducer) and UIUX's manual a11y re-pass.
 *
 * These drive the **real** [WindowHost]/[FloatingWindow] composables through Compose's UI-test infra
 * (CYP-11) — focus + key injection + semantics — i.e. exactly the composable-level wiring the unit
 * tests cannot reach. Keyboard outcomes are asserted on the observable [WindowManagerState] (the
 * source of truth the key events flow into).
 */
@OptIn(ExperimentalTestApi::class)
class WindowManagerA11yTest {

    private fun singleWindowState() = WindowManagerState(
        listOf(WindowState("po", "Product Owner", 100f, 100f, 300f, 200f)),
    )

    @Test
    fun semantics_window_exposesHeadingAndDescriptions() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }

        // Window is a screenreader heading (WCAG 1.3.1) ...
        onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // ... and window, title bar and resize grip carry content descriptions (WCAG 4.1.2).
        onNodeWithContentDescription("Agentenfenster", substring = true, useUnmergedTree = true)
            .assertExists()
        onNodeWithContentDescription("Titelleiste", substring = true, useUnmergedTree = true)
            .assertExists()
        onNodeWithContentDescription("Größe ändern", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun keyboard_arrowRight_movesFocusedWindow() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()

        assertTrue(state.windows.first { it.id == "po" }.x > 100f)
    }

    @Test
    fun keyboard_shiftArrowDown_resizesFocusedWindow() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.DirectionDown)
            keyUp(Key.ShiftLeft)
        }
        waitForIdle()

        assertTrue(state.windows.first { it.id == "po" }.height > 200f)
    }

    @Test
    fun keyboard_focus_bringsWindowToFront() = runComposeUiTest {
        val state = WindowManagerState(
            listOf(
                WindowState("po", "Product Owner", 0f, 0f, 300f, 200f),
                WindowState("frontend", "Frontend", 350f, 0f, 300f, 200f),
            ),
        )
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }

        assertEquals("frontend", state.focusedId)
        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        waitForIdle()
        assertEquals("po", state.focusedId)
    }
}
