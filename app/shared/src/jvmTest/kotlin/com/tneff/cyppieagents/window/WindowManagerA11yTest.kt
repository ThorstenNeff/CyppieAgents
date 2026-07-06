package com.tneff.cyppieagents.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    // --- CYP-241: titlebar double-click Expand+Center / Restore — the gesture + stateDescription binding,
    //     driven through the REAL WindowHost/FloatingWindow (geometry itself is covered purely in WindowExpandTest). ---

    @Test
    fun doubleTap_titleBar_togglesExpand_andFlipsStateDescription() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }
        // Locale-robust: read the actual stateDescription and assert it FLIPS with the state (the exact DE/EN copy
        // is pinned by the resource files + keys doc; the test locale here is EN, so no hardcoded string).
        fun stateDesc(): String? = onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

        assertFalse(state.isExpanded("po"))
        val normalDesc = stateDesc()

        // First double-tap on the titlebar → Expand: state flips, brings to front, stateDescription changes.
        onNodeWithTag(WindowTestTags.titleBar("po")).performTouchInput { doubleClick() }
        waitForIdle()
        assertTrue(state.isExpanded("po"))
        assertEquals("po", state.focusedId)
        assertNotEquals(normalDesc, stateDesc(), "stateDescription must flip to the expanded copy")

        // Second double-tap (untouched) → Restore: state + stateDescription return to normal.
        onNodeWithTag(WindowTestTags.titleBar("po")).performTouchInput { doubleClick() }
        waitForIdle()
        assertFalse(state.isExpanded("po"))
        assertEquals(normalDesc, stateDesc())
    }

    /** CYP-241 §9.4: the double-tap detector coexists with the drag one — a moving gesture on the titlebar is still
     *  a DRAG (window moves), not a double-tap (no expand). Guards against the tap detector swallowing drags. */
    @Test
    fun titleBarDrag_stillMovesWindow_andIsNotAnExpand() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }
        val startX = state.windows.first { it.id == "po" }.x
        onNodeWithTag(WindowTestTags.titleBar("po")).performTouchInput {
            down(center)
            moveTo(center + Offset(140f, 0f))
            up()
        }
        waitForIdle()
        assertTrue(state.windows.first { it.id == "po" }.x > startX, "titlebar drag still moves the window")
        assertFalse(state.isExpanded("po"), "a drag is not a double-tap → never an accidental expand")
    }

    // --- CYP-245 (Enter = toggle Expand+Center ↔ Restore) + CYP-248 (Escape = Restore-only): the keyboard path on
    //     the focused window root, driven through the REAL WindowHost/FloatingWindow. Same onToggleExpand as the
    //     CYP-241 double-tap → geometry + stateDescription parity is by construction; these pin the key wiring, the
    //     toggle / no-dead-key behaviour, Escape's restore-only + non-expanding no-op, and the a11y hint appendage. ---

    @Test
    fun enter_togglesExpand_andFlipsStateDescription() = runComposeUiTest {
        val state = singleWindowState()
        setContent { MaterialTheme { WindowHost(state = state, windowContent = { Text("stub ${it.id}") }) } }
        fun stateDesc(): String? = onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        assertFalse(state.isExpanded("po"))
        val normalDesc = stateDesc()

        // Enter → Expand (same path as the double-tap): state flips, brings to front, stateDescription changes.
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(state.isExpanded("po"))
        assertEquals("po", state.focusedId)
        assertNotEquals(normalDesc, stateDesc(), "stateDescription must flip to the expanded copy")

        // Enter again → Restore: state + stateDescription return to normal.
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertFalse(state.isExpanded("po"))
        assertEquals(normalDesc, stateDesc())
    }

    /** §9-③: after a manual move (anchor cleared) Enter is a FRESH expand, never a dead no-op. */
    @Test
    fun enter_afterManualMove_isFreshExpand_notDeadKey() = runComposeUiTest {
        val state = singleWindowState()
        setContent { MaterialTheme { WindowHost(state = state, windowContent = { Text("stub ${it.id}") }) } }
        onNodeWithTag(WindowTestTags.window("po")).requestFocus()

        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) } // expand
        waitForIdle()
        assertTrue(state.isExpanded("po"))

        // A manual arrow move clears the Restore anchor (CYP-241) → no longer "expanded".
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertFalse(state.isExpanded("po"))

        // Enter is NOT dead here → a fresh Expand, not a no-op.
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(state.isExpanded("po"))
    }

    /** §9-⑥/⑨: Escape restores an expanded window and returns the announcement to normal (== Enter-restore). */
    @Test
    fun escape_restoresExpandedWindow_toNormal() = runComposeUiTest {
        val state = singleWindowState()
        setContent { MaterialTheme { WindowHost(state = state, windowContent = { Text("stub ${it.id}") }) } }
        fun stateDesc(): String? = onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        val normalDesc = stateDesc()

        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) } // expand
        waitForIdle()
        assertTrue(state.isExpanded("po"))

        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(state.isExpanded("po"))
        assertEquals(normalDesc, stateDesc())
    }

    /** §9-⑥/⑦: on a non-expanded window Escape NEVER expands and leaves the geometry untouched (restore-only, no dead jump). */
    @Test
    fun escape_whenNotExpanded_neverExpands_geometryUntouched() = runComposeUiTest {
        val state = singleWindowState()
        setContent { MaterialTheme { WindowHost(state = state, windowContent = { Text("stub ${it.id}") }) } }
        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        waitForIdle()

        assertFalse(state.isExpanded("po"))
        val before = state.windows.first { it.id == "po" }
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(state.isExpanded("po"))
        assertEquals(before, state.windows.first { it.id == "po" })
    }

    /** §9-⑩: the Enter hint is always advertised; the Escape (restore) hint is appended ONLY while expanded. */
    @Test
    fun keyHints_enterAlways_escapeOnlyWhenExpanded() = runComposeUiTest {
        val state = singleWindowState()
        setContent { MaterialTheme { WindowHost(state = state, windowContent = { Text("stub ${it.id}") }) } }
        fun contentDesc(): String = onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: ""

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        waitForIdle()

        // Normal: a keyboard hint is appended to the base description; locale-robust (assert the base + growth,
        // not the exact copy, which the resource files pin).
        val base = "Agentenfenster Product Owner" // the window's title, not its id
        val normalDesc = contentDesc()
        assertTrue(normalDesc.startsWith(base), "keeps the base window description")
        assertTrue(normalDesc.length > "$base. ".length, "the Enter hint is appended in normal state")

        // Expanded: the restore hint is now appended too — the description extends the normal one.
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val expandedDesc = contentDesc()
        assertTrue(expandedDesc.startsWith(normalDesc), "expanded description extends the normal one")
        assertTrue(expandedDesc.length > normalDesc.length, "the restore hint is appended only while expanded")
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
