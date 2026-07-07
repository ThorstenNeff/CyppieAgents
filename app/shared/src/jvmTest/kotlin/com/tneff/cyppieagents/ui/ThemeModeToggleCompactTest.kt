package com.tneff.cyppieagents.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-281 — on a narrow bar the theme toggle renders ICON-ONLY (mode glyph + ▾, no "Thema/Theme: <mode>" word)
 * so it stops starving the active-project label. The dropdown, testTag, and (crucially) the button
 * contentDescription — which still names the mode — are unchanged; only the visible label collapses.
 *
 * Mutation proof: make the button text ignore [compact] (always the wide form) → [compact_isIconOnly_noThemeWord]
 * REDs (the glyph is absent and the "Theme" word is present).
 */
@OptIn(ExperimentalTestApi::class)
class ThemeModeToggleCompactTest {

    @Test
    fun compact_isIconOnly_noThemeWord() = runComposeUiTest {
        setContent { MaterialTheme { ThemeModeToggle(mode = ThemeMode.SYSTEM, onChange = {}, compact = true) } }
        waitForIdle()
        onNodeWithText("◐", substring = true).assertExists() // the SYSTEM glyph (icon-only)
        // The visible "Theme" word is gone (only the contentDescription still carries it — not matched by text).
        onNodeWithText("Theme", substring = true).assertDoesNotExist()
    }

    @Test
    fun wide_showsTheThemeWord() = runComposeUiTest {
        setContent { MaterialTheme { ThemeModeToggle(mode = ThemeMode.SYSTEM, onChange = {}, compact = false) } }
        waitForIdle()
        onNodeWithText("Theme", substring = true).assertExists()
    }
}
