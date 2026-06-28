package com.tneff.cyppieagents.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-84/85: the Project-Settings VM logic, driven over [StubConfigRepository] on an Unconfined scope so
 * the (non-suspending) stub calls settle synchronously. Covers the security/disclosure guarantees the
 * reviewer gate asks for — each is mutation-provable:
 * - **fail-closed:** a save without operator rights is a no-op (drop the `editable` guard → RED).
 * - **never cleartext:** a saved key surfaces only `***<last4>`; the typed input is dropped (drop the
 *   input-clear / the masked-only storage → RED).
 * - **"saved ≠ active":** a successful save sets the amber effect hint; a new edit clears it.
 * - **honest errors:** the server reason maps to the spec field/gate message.
 */
class SettingsViewModelTest {

    private fun vm(repo: ConfigRepository, editable: Boolean = true) =
        SettingsViewModel(repo, editable = editable, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun load_unconfigured_isHonestEmpty_notFabricated() {
        val s = vm(StubConfigRepository()).state.value
        assertFalse(s.loading)
        assertFalse(s.repoConfigured)
        assertFalse(s.apiKeySet)
        assertNull(s.apiKeyMasked)
    }

    @Test
    fun load_configured_populatesRepoAndMaskedKey() {
        val s = vm(
            StubConfigRepository(
                initialRepo = RepoConfigState.Configured("git@github.com:o/r.git", "dev"),
                initialApiKey = ApiKeyState(set = true, masked = "***ef45"),
            ),
        ).state.value
        assertTrue(s.repoConfigured)
        assertEquals("git@github.com:o/r.git", s.repoUrl)
        assertEquals("dev", s.repoBranch)
        assertTrue(s.apiKeySet)
        assertEquals("***ef45", s.apiKeyMasked)
    }

    // --- CYP-84 repo ---

    @Test
    fun saveRepo_operator_setsConfigured_amberEffectHint_noError() {
        val vm = vm(StubConfigRepository())
        vm.onRepoUrlChange("git@github.com:o/r.git")
        vm.onRepoBranchChange("main")
        vm.saveRepo()
        val s = vm.state.value
        assertTrue(s.repoConfigured)
        assertTrue(s.repoEffectHint) // "saved ≠ active"
        assertNull(s.repoError)
    }

    @Test
    fun saveRepo_notEditable_isNoOp_failClosed() {
        val vm = vm(StubConfigRepository(), editable = false)
        vm.onRepoUrlChange("git@github.com:o/r.git")
        vm.saveRepo()
        val s = vm.state.value
        assertFalse(s.repoConfigured)
        assertFalse(s.repoEffectHint)
    }

    @Test
    fun saveRepo_serverRejectsUrl_surfacesInvalidUrl_noEffectHint() {
        val vm = vm(StubConfigRepository(denyWrites = "invalid_repo_url"))
        vm.onRepoUrlChange("not-a-url")
        vm.saveRepo()
        val s = vm.state.value
        assertEquals("settings_repo_url_invalid", s.repoError)
        assertFalse(s.repoEffectHint)
        assertFalse(s.repoConfigured)
    }

    @Test
    fun edit_clearsStaleEffectHint() {
        val vm = vm(StubConfigRepository())
        vm.onRepoUrlChange("git@github.com:o/r.git")
        vm.saveRepo()
        assertTrue(vm.state.value.repoEffectHint)
        vm.onRepoUrlChange("git@github.com:o/other.git")
        assertFalse(vm.state.value.repoEffectHint) // a fresh edit is no longer "saved"
    }

    // --- CYP-85 API-key ---

    @Test
    fun saveApiKey_operator_storesMaskedOnly_dropsInput_amberHint_neverCleartext() {
        val vm = vm(StubConfigRepository())
        vm.onApiKeyInputChange("sk-ant-supersecret1234")
        vm.saveApiKey()
        val s = vm.state.value
        assertTrue(s.apiKeySet)
        assertEquals("***1234", s.apiKeyMasked)
        assertEquals("", s.apiKeyInput) // write-only input dropped after save
        assertFalse(s.apiKeyReveal)
        assertTrue(s.apiKeyEffectHint)
        // The clear key never survives anywhere in the state.
        assertFalse(s.apiKeyMasked!!.contains("supersecret"))
    }

    @Test
    fun saveApiKey_notEditable_isNoOp_failClosed() {
        val vm = vm(StubConfigRepository(), editable = false)
        vm.onApiKeyInputChange("sk-ant-xxxx")
        vm.saveApiKey()
        assertFalse(vm.state.value.apiKeySet)
    }

    @Test
    fun saveApiKey_gateDenied_surfacesOperatorRequired() {
        val vm = vm(StubConfigRepository(denyWrites = "operator_required"))
        vm.onApiKeyInputChange("sk-ant-xxxx")
        vm.saveApiKey()
        assertEquals("settings_operator_required", vm.state.value.apiKeyError)
    }

    @Test
    fun reveal_togglesInputVisibilityFlag() {
        val vm = vm(StubConfigRepository())
        assertFalse(vm.state.value.apiKeyReveal)
        vm.toggleApiKeyReveal()
        assertTrue(vm.state.value.apiKeyReveal)
    }

    @Test
    fun canSave_gatedByOperatorAndNonBlank() {
        val locked = vm(StubConfigRepository(), editable = false)
        locked.onRepoUrlChange("git@x:y.git")
        assertFalse(locked.state.value.canSaveRepo) // no operator → never savable

        val open = vm(StubConfigRepository(), editable = true)
        assertFalse(open.state.value.canSaveRepo) // operator but blank URL → not yet
        open.onRepoUrlChange("git@x:y.git")
        assertTrue(open.state.value.canSaveRepo)
    }
}
