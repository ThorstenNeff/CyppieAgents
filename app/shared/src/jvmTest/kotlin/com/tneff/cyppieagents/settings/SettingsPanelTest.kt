package com.tneff.cyppieagents.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-84/85 render gate: the panel renders the operator gate honestly (visible but disabled without a
 * token), the "agents cannot start" status when unconfigured, and the API key **only masked** — never
 * the clear value. testTags exactly per `docs/design/project-settings-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPanelTest {

    @Test
    fun operatorUnconfigured_showsUnsetStatus_inputsEnabled_noGateHints() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { SettingsViewModel(StubConfigRepository(), editable = true) }
                SettingsPanel(vm)
            }
        }
        onNodeWithTag(SettingsTags.PANEL).assertExists()
        // Honest "no repository configured — agents cannot start".
        onNodeWithTag(SettingsTags.REPO_STATUS).assertExists()
        // Operator → editable, no gate hints, saves are real controls.
        onNodeWithTag(SettingsTags.REPO_GATE_HINT).assertDoesNotExist()
        onNodeWithTag(SettingsTags.API_KEY_GATE_HINT).assertDoesNotExist()
        onNodeWithTag(SettingsTags.REPO_URL_INPUT).assertIsEnabled()
        onNodeWithTag(SettingsTags.API_KEY_INPUT).assertIsEnabled()
    }

    @Test
    fun noOperator_gateHintsShown_savesDisabled_failClosed() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { SettingsViewModel(StubConfigRepository(), editable = false) }
                SettingsPanel(vm)
            }
        }
        // The gate is observable (visible hint) but the controls are inert.
        onNodeWithTag(SettingsTags.REPO_GATE_HINT).assertExists()
        onNodeWithTag(SettingsTags.API_KEY_GATE_HINT).assertExists()
        onNodeWithTag(SettingsTags.REPO_SAVE).assertIsNotEnabled()
        onNodeWithTag(SettingsTags.API_KEY_SAVE).assertIsNotEnabled()
        onNodeWithTag(SettingsTags.REPO_URL_INPUT).assertIsNotEnabled()
        onNodeWithTag(SettingsTags.API_KEY_INPUT).assertIsNotEnabled()
    }

    @Test
    fun configuredKey_showsMasked_neverCleartext() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    SettingsViewModel(
                        StubConfigRepository(initialApiKey = ApiKeyState(set = true, masked = "***ef45")),
                        editable = true,
                    )
                }
                SettingsPanel(vm)
            }
        }
        onNodeWithTag(SettingsTags.API_KEY_MASKED).assertExists()
        // The masked status line appears after load; the clear key never could (only ***last4 is held).
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("***ef45", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("***ef45", substring = true).assertExists()
    }

    /**
     * CYP-149 — close the one real S3-tag-render gap: the API-key effect-hint render path
     * (`SettingsPanel.kt`, `SettingsTags.API_KEY_EFFECT_HINT`) was VM-covered ([SettingsViewModelTest]) but
     * had **no `runComposeUiTest` node-assertion**, unlike `REPO_STATUS` (#1) and `rowProject` (#3). This
     * locks the contract: the amber "saved ≠ active — restart" hint is **absent before a save** (set ONLY by
     * an explicit save action, never on load) and **present after** — mutation-style, so it proves the save
     * renders the node, not an always-on artifact. Unconfined scope settles the save synchronously.
     */
    @Test
    fun apiKeyEffectHint_absentBeforeSave_rendersAfterSave() = runComposeUiTest {
        val vm = SettingsViewModel(
            StubConfigRepository(),
            editable = true,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        setContent { MaterialTheme { SettingsPanel(vm) } }
        // Not present on load — the hint is an after-save effect, never implied while running agents use the old key.
        onNodeWithTag(SettingsTags.API_KEY_EFFECT_HINT).assertDoesNotExist()
        // Drive a real save through the VM's public surface → apiKeyEffectHint = true.
        vm.onApiKeyInputChange("sk-ant-supersecret1234")
        vm.saveApiKey()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(SettingsTags.API_KEY_EFFECT_HINT).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(SettingsTags.API_KEY_EFFECT_HINT).assertExists()
    }
}
