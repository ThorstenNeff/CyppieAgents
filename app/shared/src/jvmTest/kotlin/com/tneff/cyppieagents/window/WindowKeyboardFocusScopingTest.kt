package com.tneff.cyppieagents.window

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-261 — committed focus-scoping guards for the window keyboard slice (CYP-245 Enter = toggle Expand/Restore,
 * CYP-248 Escape = restore-only). PO-Assistent flagged in the slice gate that the 11 branch teeth pin the key
 * WIRING but never the **focus precedence** — the security-relevant part of the §9 spec ("a focused modal / a
 * focused composer keeps its own key; the window key only fires when the window ROOT holds focus"). That claim
 * lived only in a code comment (WindowManager.kt:566-576) + a one-shot manual probe. These teeth commit it.
 *
 * The invariant is structural, not extra logic: the window's handler is a **bottom-up** `.onKeyEvent` on the
 * focusable window-root Box (WindowManager.kt:543-587). A key event is delivered to the current focus owner and
 * bubbles up **its** ancestor chain only — so:
 *   - §9-5: a focused child that consumes Enter (the composer's singleLine `onSend`) is deeper than the root →
 *     the root's `.onKeyEvent` never sees Enter → no expand. A regression to a top-down `.onPreviewKeyEvent` on
 *     the root would fire BEFORE the child and steal Enter → this tooth goes RED.
 *   - §9-8: a modal is a SEPARATE focus owner that is NOT a descendant of the window root → its Escape bubbles up
 *     its own (non-window) ancestors, never reaching the root's `.onKeyEvent` → the expanded window is NOT
 *     restored. A regression that hoists Escape to a host-level / global listener (not scoped to window focus)
 *     would restore from anywhere → this tooth goes RED.
 *
 * Harness note (from the ticket): a real `androidx.compose.ui.window.Dialog` cannot be driven here —
 * `runComposeUiTest` cannot inject a key into an open `Dialog`'s own focus scope (a harness limit, not a bug;
 * prod desktop is safe via the DialogWindow focus-grab). So §9-8 uses PO-Assistent's **structural** probe: a
 * focusable sibling OUTSIDE the window subtree stands in for the modal's separate focus scope — it exercises the
 * exact precedence mechanism (Escape reaches whoever owns focus, not the window root).
 */
@OptIn(ExperimentalTestApi::class)
class WindowKeyboardFocusScopingTest {

    private fun singleWindowState() = WindowManagerState(
        listOf(WindowState("po", "Product Owner", 100f, 100f, 300f, 200f)),
    )

    private val COMPOSER = "focus.composer"
    private val MODAL = "focus.modal"

    /**
     * A window whose CONTENT is the real composer shape (singleLine + `ImeAction.Send` + `KeyboardActions.onSend`),
     * exactly like the CommPanel / AgentWindow message input. `onSend` flips [sentFlag] so the tooth can prove the
     * Enter reached the composer, not the window.
     */
    @androidx.compose.runtime.Composable
    private fun composerContent(onSent: () -> Unit) {
        var draft by remember { mutableStateOf("hi") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.testTag(COMPOSER),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSent() }),
        )
    }

    // --- §9-5: Enter inside a focused composer stays with the composer; it does NOT expand the window. ---

    @Test
    fun enter_inFocusedComposer_sendsToComposer_doesNotExpandWindow() = runComposeUiTest {
        val state = singleWindowState()
        var sent = false
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { composerContent(onSent = { sent = true }) })
            }
        }

        // Focus the composer (a child of the window content), NOT the window root.
        onNodeWithTag(COMPOSER).requestFocus()
        waitForIdle()
        assertFalse(state.isExpanded("po"))

        onNodeWithTag(COMPOSER).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        // The composer consumed Enter (its own "send") → it never bubbled to the window root's onKeyEvent.
        assertTrue(sent, "Enter in the focused composer must trigger the composer's send, not the window")
        assertFalse(state.isExpanded("po"), "Enter in the composer must NOT expand the window (focus-scoped)")
    }

    /**
     * Positive control for the tooth above: with the SAME composer present in the content, focusing the window
     * ROOT and pressing Enter DOES expand — proving the non-expand above is purely a focus-ownership effect, not
     * a globally dead Enter. (Guards against a lazy "green" where Enter simply never expands anywhere.)
     */
    @Test
    fun enter_onWindowRoot_stillExpands_evenWithComposerInContent() = runComposeUiTest {
        val state = singleWindowState()
        setContent {
            MaterialTheme {
                WindowHost(state = state, windowContent = { composerContent(onSent = {}) })
            }
        }

        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        waitForIdle()
        assertFalse(state.isExpanded("po"))

        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(state.isExpanded("po"), "Enter on the focused window root expands (composer in content is irrelevant here)")
    }

    // --- §9-8: modal precedence — a focused modal catches Escape; the expanded window is NOT restored. ---

    /**
     * PO-Assistent's structural modal-precedence probe, committed. An expanded window + a focusable sibling
     * (the modal's separate focus scope) that consumes Escape. While the modal owns focus, Escape closes the
     * modal and the window stays expanded with untouched geometry. Then the window root re-takes focus and the
     * SAME Escape restores it — proving the difference is purely focus ownership (precedence), and that Escape
     * is not otherwise dead.
     */
    @Test
    fun escape_whileModalOwnsFocus_closesModal_leavesExpandedWindowUntouched() = runComposeUiTest {
        val state = singleWindowState()
        var modalClosed = false
        setContent {
            MaterialTheme {
                Box {
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                    // A sibling of the window subtree (as a real Dialog's focus scope is): consumes Escape itself.
                    Box(
                        Modifier
                            .size(1.dp)
                            .testTag(MODAL)
                            .focusable()
                            .onKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                    modalClosed = true
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
            }
        }

        // Expand the window via the window root (the normal path).
        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(state.isExpanded("po"))
        val geometryWhileExpanded = state.windows.first { it.id == "po" }

        // The modal grabs focus (as an opened Dialog does) and Escape is pressed.
        onNodeWithTag(MODAL).requestFocus()
        waitForIdle()
        onNodeWithTag(MODAL).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        // Escape went to the modal, NOT the window: modal closed, window still expanded, geometry unchanged.
        assertTrue(modalClosed, "the focused modal must catch Escape")
        assertTrue(state.isExpanded("po"), "Escape must NOT restore the window while a modal owns focus (precedence)")
        assertEquals(
            geometryWhileExpanded,
            state.windows.first { it.id == "po" },
            "the window geometry must be untouched while the modal handled Escape",
        )

        // Precedence, not deadness: with the window root focused, the SAME Escape restores it.
        onNodeWithTag(WindowTestTags.window("po")).requestFocus()
        onNodeWithTag(WindowTestTags.window("po")).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(state.isExpanded("po"), "Escape on the focused window root restores the expanded window")
    }
}
