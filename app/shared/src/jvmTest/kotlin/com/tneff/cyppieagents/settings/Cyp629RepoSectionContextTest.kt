package com.tneff.cyppieagents.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-629 (2c) — the reused [RepoSection] takes ONE flag (`firstRunContext`) that ONLY suppresses the amber
 * "applies to new worktrees / next boot" hint (misleading in first-run: the hub clones NOW). Pins BOTH contexts
 * (PO condition ③): the default (Settings) STILL shows the hint after a save; the first-run context suppresses it.
 * (The net-new "cloning now" confirmation + clone-status states are the first-run wrapper's job — the section does
 * not know first-run.)
 *
 * Reddening mutation: drop the `&& !firstRunContext` guard → the first-run test sees the hint → red.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629RepoSectionContextTest {

    private class StubRepo : ConfigRepository {
        override suspend fun getRepo(): RepoConfigState = RepoConfigState.NotConfigured
        override suspend fun putRepo(url: String, branch: String) = RepoConfigState.Configured(url, branch)
        override suspend fun getApiKey() = ApiKeyState(set = false, masked = null)
        override suspend fun putApiKey(apiKey: String) = ApiKeyState(set = true, masked = "***1234")
    }

    /** State just after a successful repo save (the amber "saved ≠ active" signal is set). */
    private fun savedState() = SettingsUiState(
        loading = false, editable = true, repoConfigured = true,
        repoUrl = "git@github.com:org/p.git", repoBranch = "main", repoEffectHint = true,
    )

    @Test
    fun settingsContext_stillShowsEffectHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RepoSection(savedState(), SettingsViewModel(StubRepo(), editable = true), firstRunContext = false)
            }
        }
        onNodeWithTag(SettingsTags.REPO_EFFECT_HINT).assertExists()
    }

    @Test
    fun firstRunContext_suppressesEffectHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RepoSection(savedState(), SettingsViewModel(StubRepo(), editable = true), firstRunContext = true)
            }
        }
        onNodeWithTag(SettingsTags.REPO_EFFECT_HINT).assertDoesNotExist()
    }
}
