package com.tneff.cyppieagents.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-268 R3 — the [ThemeModeToggle] menu drives the mode + marks the active choice. Renders in isolation
 * (no shell); the AgentShell wiring is proven separately by AgentShellThemeToggleTest.
 *
 * Mutation proof: neuter the item `onClick = { onChange(m); ... }` → `pickingDark…` REDs; drop the
 * `if (m == mode)` active marker → `activeMode_isMarked…` REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeModeToggleTest {

    @Test
    fun pickingDark_invokesOnChange_withDark() = runComposeUiTest {
        var picked: ThemeMode? = null
        setContent { MaterialTheme { ThemeModeToggle(mode = ThemeMode.SYSTEM, onChange = { picked = it }) } }
        waitForIdle()
        assertNull(picked, "no selection until the user picks one")

        onNodeWithTag(ThemeTags.TOGGLE).performClick() // open the menu
        waitForIdle()
        onNodeWithTag(ThemeTags.item(ThemeMode.DARK)).performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, picked, "clicking 'Dark' drives onChange(DARK)")
    }

    @Test
    fun activeMode_isMarked_othersAreNot() = runComposeUiTest {
        setContent { MaterialTheme { ThemeModeToggle(mode = ThemeMode.LIGHT, onChange = {}) } }
        waitForIdle()
        onNodeWithTag(ThemeTags.TOGGLE).performClick()
        waitForIdle()
        // The active mode (LIGHT) carries the ● form marker; a non-active mode (DARK) does not (WCAG 1.4.1).
        // The marker is a child of the clickable (merged) menu item → read it on the unmerged tree.
        onNodeWithTag(ThemeTags.itemActive(ThemeMode.LIGHT), useUnmergedTree = true).assertExists()
        onNodeWithTag(ThemeTags.itemActive(ThemeMode.DARK), useUnmergedTree = true).assertDoesNotExist()
    }
}
