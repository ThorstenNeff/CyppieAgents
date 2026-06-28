package com.tneff.cyppieagents.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the Project-Settings panel (S15: CYP-84 repo + CYP-85 API-key). Disclosure is
 * carried in the state, not inferred in the view: an [repoEffectHint]/[apiKeyEffectHint] is set ONLY
 * after a successful save (the amber "saved ≠ active" hint) and cleared on the next edit; errors are
 * i18n keys mapped from the server's reason code. The clear API key is never held here — only the input
 * the user is currently typing and the server-masked [apiKeyMasked].
 */
data class SettingsUiState(
    val loading: Boolean = true,
    /** Operator token present → fields editable + saves enabled; else read-only + gate hint (fail-closed). */
    val editable: Boolean = false,
    // --- CYP-84 repo ---
    val repoConfigured: Boolean = false,
    val repoUrl: String = "",
    val repoBranch: String = "main",
    /** Amber "saved ≠ active — applies to new worktrees / next boot" hint after a successful repo save. */
    val repoEffectHint: Boolean = false,
    /** i18n key for the repo field error (`settings_repo_url_invalid` / gate), or null. */
    val repoError: String? = null,
    // --- CYP-85 API-key ---
    val apiKeySet: Boolean = false,
    /** Server-masked `***<last4>` only — NEVER the clear key (PROJECT-SETTINGS §1.2). */
    val apiKeyMasked: String? = null,
    /** The key the user is currently typing (write-only; never the stored value). */
    val apiKeyInput: String = "",
    /** Reveal toggle un-masks ONLY [apiKeyInput], never the stored key. */
    val apiKeyReveal: Boolean = false,
    /** Amber "saved ≠ active — restart the affected agents" hint after a successful key save. */
    val apiKeyEffectHint: Boolean = false,
    /** i18n key for the key save error (`settings_apikey_save_failed` / gate), or null. */
    val apiKeyError: String? = null,
) {
    /** Repo save is offered only to an operator with a non-blank URL (server validates; §6.4). */
    val canSaveRepo: Boolean get() = editable && repoUrl.isNotBlank()

    /** Key save is offered only to an operator with a non-blank new key. */
    val canSaveApiKey: Boolean get() = editable && apiKeyInput.isNotBlank()
}

/**
 * Drives the Project-Settings panel (S15) over a [ConfigRepository] (stub today; live REST after the
 * CYP-96 backend seam — no VM/UI change). Loads repo + API-key state, then applies operator-gated saves
 * with the spec-exact disclosure: success → amber effect hint (never success-green, never "already
 * active"); failure → the server's reason mapped to a field/gate message. **Fail-closed:** a save is a
 * no-op without [SettingsUiState.editable] (defence in depth — the server also enforces the operator
 * gate, and the UI disables the controls).
 */
class SettingsViewModel(
    private val repository: ConfigRepository,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(SettingsUiState(editable = editable))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { runScope.launch { load() } }

    private suspend fun load() {
        // Fail-closed: a failed load leaves the honest defaults (not configured / no key), never a
        // fabricated value. The clear key never arrives here — getApiKey returns only {set, masked}.
        val repo = runCatching { repository.getRepo() }.getOrNull()
        val key = runCatching { repository.getApiKey() }.getOrNull()
        _state.update { s ->
            val withRepo = when (repo) {
                is RepoConfigState.Configured ->
                    s.copy(repoConfigured = true, repoUrl = repo.url, repoBranch = repo.branch)
                RepoConfigState.NotConfigured, null ->
                    s.copy(repoConfigured = false)
            }
            withRepo.copy(
                apiKeySet = key?.set ?: false,
                apiKeyMasked = key?.masked,
                loading = false,
            )
        }
    }

    // --- CYP-84 repo ---

    fun onRepoUrlChange(value: String) =
        _state.update { it.copy(repoUrl = value, repoError = null, repoEffectHint = false) }

    fun onRepoBranchChange(value: String) =
        _state.update { it.copy(repoBranch = value, repoError = null, repoEffectHint = false) }

    fun saveRepo() {
        val s = _state.value
        if (!s.editable) return // fail-closed (server also 403s; controls disabled)
        runScope.launch {
            runCatching { repository.putRepo(s.repoUrl, s.repoBranch) }
                .onSuccess { saved ->
                    _state.update {
                        it.copy(
                            repoConfigured = true, repoUrl = saved.url, repoBranch = saved.branch,
                            repoError = null, repoEffectHint = true,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(repoError = repoErrorKey(e), repoEffectHint = false) }
                }
        }
    }

    // --- CYP-85 API-key ---

    fun onApiKeyInputChange(value: String) =
        _state.update { it.copy(apiKeyInput = value, apiKeyError = null, apiKeyEffectHint = false) }

    fun toggleApiKeyReveal() = _state.update { it.copy(apiKeyReveal = !it.apiKeyReveal) }

    fun saveApiKey() {
        val s = _state.value
        if (!s.editable) return
        runScope.launch {
            runCatching { repository.putApiKey(s.apiKeyInput) }
                .onSuccess { saved ->
                    // Drop the write-only input + re-mask on success; show only the masked value + the
                    // amber "restart to apply" hint (never imply the running agents already use it).
                    _state.update {
                        it.copy(
                            apiKeySet = saved.set, apiKeyMasked = saved.masked,
                            apiKeyInput = "", apiKeyReveal = false,
                            apiKeyError = null, apiKeyEffectHint = true,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(apiKeyError = apiKeyErrorKey(e), apiKeyEffectHint = false) }
                }
        }
    }

    // Map the server's reason code to the spec-defined disclosure (PROJECT-SETTINGS §2/§3.2/§4.2). The
    // contract enumerates exactly these codes; a gate denial surfaces the operator-required message.
    private fun repoErrorKey(e: Throwable): String = when ((e as? ConfigException)?.code) {
        "operator_required", "unauthorized" -> "settings_operator_required"
        else -> "settings_repo_url_invalid"
    }

    private fun apiKeyErrorKey(e: Throwable): String = when ((e as? ConfigException)?.code) {
        "operator_required", "unauthorized" -> "settings_operator_required"
        else -> "settings_apikey_save_failed"
    }
}
