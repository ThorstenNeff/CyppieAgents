package com.tneff.cyppieagents.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-387 §3.1 — the input-history size stepper. Drives the ± buttons and checks the emitted value + the bound
 * disabling (`0` and 200). The clamp itself lives in App.kt / ThemePreferences (unit-tested there); here we prove
 * the control emits `size ± 1` and refuses to step past the ends.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp387HistorySizeStepperTest {

    @Test
    fun showsValue_andIncDecEmitNeighbourValues() {
        var changed: Int? = null
        runComposeUiTest {
            setContent { MaterialTheme { ComposerHistorySizeStepper(size = 20, onChange = { changed = it }) } }
            // The stepper Row merges its descendants for a11y (one "<label>: N" node), so the plain value Text is
            // only in the unmerged tree.
            onNodeWithTag(ComposerHistoryTags.VALUE, useUnmergedTree = true).assertTextEquals("20")
            onNodeWithTag(ComposerHistoryTags.INC).performClick()
        }
        assertEquals(21, changed, "+ emits size + 1")
    }

    @Test
    fun decEmitsOneLess() {
        var changed: Int? = null
        runComposeUiTest {
            setContent { MaterialTheme { ComposerHistorySizeStepper(size = 20, onChange = { changed = it }) } }
            onNodeWithTag(ComposerHistoryTags.DEC).performClick()
        }
        assertEquals(19, changed, "− emits size − 1")
    }

    /** At the floor `0` (history off) the − is disabled; + still works. Mutation: drop the `enabled` guard ⇒ − emits −1 ⇒
     *  App.kt would clamp, but the control should not offer an out-of-range step ⇒ this pins the disable. */
    @Test
    fun atZero_decrementDisabled_incrementEnabled() {
        runComposeUiTest {
            setContent { MaterialTheme { ComposerHistorySizeStepper(size = 0, onChange = {}) } }
            onNodeWithTag(ComposerHistoryTags.DEC).assertIsNotEnabled()
            onNodeWithTag(ComposerHistoryTags.INC).assertIsEnabled()
        }
    }

    /** At the ceiling 200 the + is disabled; − still works. */
    @Test
    fun atMax_incrementDisabled_decrementEnabled() {
        runComposeUiTest {
            setContent { MaterialTheme { ComposerHistorySizeStepper(size = MAX_COMPOSER_HISTORY_SIZE, onChange = {}) } }
            onNodeWithTag(ComposerHistoryTags.INC).assertIsNotEnabled()
            onNodeWithTag(ComposerHistoryTags.DEC).assertIsEnabled()
        }
    }
}
