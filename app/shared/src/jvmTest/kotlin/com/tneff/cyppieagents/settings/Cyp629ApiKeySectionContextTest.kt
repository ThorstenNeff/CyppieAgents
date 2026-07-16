package com.tneff.cyppieagents.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-629 (2b) — the reused [ApiKeySection] takes ONE flag (`firstRunContext`) that ONLY suppresses the amber
 * restart `EFFECT_DEFERRED` hint (misleading in first-run: no running agents to restart). This tooth pins BOTH
 * contexts (PO condition ③) so neither can silently regress into the other: the default (Settings) STILL shows the
 * restart hint after a save; the first-run context suppresses it. (The net-new INFO confirmation + posture line are
 * the first-run wrapper's job, kept out of the shared section — tested with the wrapper.)
 *
 * Reddening mutation: drop the `&& !firstRunContext` guard → the first-run test sees the restart hint → red.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629ApiKeySectionContextTest {

    /** Minimal stub — the section reads the passed [SettingsUiState] directly; the VM only backs (unused) callbacks. */
    private class StubRepo : ConfigRepository {
        override suspend fun getRepo(): RepoConfigState = RepoConfigState.NotConfigured
        override suspend fun putRepo(url: String, branch: String) = RepoConfigState.Configured(url, branch)
        override suspend fun getApiKey() = ApiKeyState(set = false, masked = null)
        override suspend fun putApiKey(apiKey: String) = ApiKeyState(set = true, masked = "***1234")
    }

    /** State just after a successful key save (the amber "saved ≠ active" signal is set). */
    private fun savedState() = SettingsUiState(
        loading = false, editable = true, apiKeySet = true, apiKeyMasked = "***1234", apiKeyEffectHint = true,
    )

    @Test
    fun settingsContext_stillShowsRestartHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApiKeySection(savedState(), SettingsViewModel(StubRepo(), editable = true), firstRunContext = false)
            }
        }
        // Default (Settings): the existing amber restart hint — byte-identical, unchanged.
        onNodeWithTag(SettingsTags.API_KEY_EFFECT_HINT).assertExists()
    }

    @Test
    fun firstRunContext_suppressesRestartHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ApiKeySection(savedState(), SettingsViewModel(StubRepo(), editable = true), firstRunContext = true)
            }
        }
        // First-run: the misleading restart hint is suppressed (no running agents to restart).
        onNodeWithTag(SettingsTags.API_KEY_EFFECT_HINT).assertDoesNotExist()
    }
}
