package com.tneff.cyppieagents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_theme_menu
import kmpcyppieagents.app.shared.generated.resources.a11y_theme_mode
import kmpcyppieagents.app.shared.generated.resources.theme_menu_label
import kmpcyppieagents.app.shared.generated.resources.theme_mode_dark
import kmpcyppieagents.app.shared.generated.resources.theme_mode_light
import kmpcyppieagents.app.shared.generated.resources.theme_mode_system
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * `testTag` contract for the app theme switcher (CYP-268 R3). Prefixless `<area>.<element>` per the tags
 * convention; area `themeToggle`. **Shared API with QA — do not rename silently; coordinate via the PO.**
 */
object ThemeTags {
    const val TOGGLE = "themeToggle.menu"
    fun item(mode: ThemeMode) = "themeToggle.item.${mode.name}"
    fun itemActive(mode: ThemeMode) = "themeToggle.item.${mode.name}.active"
}

/** The localized label for a mode (also the a11y payload) — one place so the button + each menu item agree. */
private fun labelOf(mode: ThemeMode): StringResource = when (mode) {
    ThemeMode.SYSTEM -> Res.string.theme_mode_system
    ThemeMode.LIGHT -> Res.string.theme_mode_light
    ThemeMode.DARK -> Res.string.theme_mode_dark
}

/**
 * CYP-268 R3 — the compact app-global theme switcher: a "Thema: <mode> ▾" button opening a menu of
 * System / Light / Dark, the active mode marked with the form marker ● (never colour alone — WCAG 1.4.1).
 *
 * Theme is a **client-local, per-user** preference, so this is NOT operator-gated and NOT project-scoped; it
 * rides the always-visible [com.tneff.cyppieagents.project.ProjectSwitcherBar] trailing slot. It only *drives*
 * the mode via [onChange] — the `App.kt` seam owns the persistence + the recolour (this adds no colour).
 */
@Composable
fun ThemeModeToggle(mode: ThemeMode, onChange: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = stringResource(labelOf(mode))
    // Resolved outside the semantics lambda (stringResource is @Composable) and captured: the button a11y names
    // the current selection so a screen-reader user hears the state, not just "menu".
    val menuA11y = stringResource(Res.string.a11y_theme_menu, currentLabel)
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .testTag(ThemeTags.TOGGLE)
                .semantics { contentDescription = menuA11y },
        ) {
            Text(
                text = "${stringResource(Res.string.theme_menu_label)}: $currentLabel ▾",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeMode.entries.forEach { m ->
                val label = stringResource(labelOf(m))
                val itemA11y = stringResource(Res.string.a11y_theme_mode, label)
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Active marker: form (●), never colour alone (WCAG 1.4.1). Absent for the others.
                            if (m == mode) Text("●", modifier = Modifier.testTag(ThemeTags.itemActive(m)))
                            Text(label)
                        }
                    },
                    onClick = { onChange(m); open = false },
                    modifier = Modifier
                        .testTag(ThemeTags.item(m))
                        .semantics { contentDescription = itemA11y },
                )
            }
        }
    }
}
