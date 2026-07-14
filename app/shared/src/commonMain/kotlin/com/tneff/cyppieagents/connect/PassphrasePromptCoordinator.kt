package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.operator.vault.PassphrasePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CYP-542 / B1 — the live [PassphrasePrompt] the CYP-460 dialog is driven by: it bridges the auth-time UV
 * ([com.tneff.cyppieagents.net.hub.operator.vault.PassphraseUserVerification], which calls [prompt] and suspends)
 * ↔ the ViewModel/dialog (which surfaces [state] and calls [submit]/[cancel]). Mirrors [LiveEnrollConfirmCoordinator].
 * `null` (cancel) ⇒ `Denied(CANCELLED)` (fail-closed, retryable — never a hub reject).
 *
 * **AC-2** (enroll→auth, no immediate 2nd prompt): after a successful set-passphrase the flow [preArm]s the coordinator
 * with the just-entered passphrase, so the FIRST auth [prompt] resolves with it directly (no re-prompt) → the vault
 * opens → the ≤120s key-hold serves the N tunnels. The pre-armed array is the SAME one the UV zeroizes after use (H-1).
 */
sealed interface PassphrasePromptState {
    data object Idle : PassphrasePromptState
    data class Prompting(val reason: UvReason) : PassphrasePromptState
}

interface PassphrasePromptCoordinator : PassphrasePrompt {
    val state: StateFlow<PassphrasePromptState>
    /** The operator entered their passphrase → resolve the pending [prompt] with it. */
    fun submit(passphrase: CharArray)
    /** The operator cancelled → resolve the pending [prompt] with `null` (Denied(CANCELLED), fail-closed). */
    fun cancel()
    /** AC-2: arm the NEXT [prompt] to auto-resolve with [passphrase] (the just-set enroll secret) — no re-prompt. */
    fun preArm(passphrase: CharArray)
}

class LivePassphrasePromptCoordinator : PassphrasePromptCoordinator {
    private val _state = MutableStateFlow<PassphrasePromptState>(PassphrasePromptState.Idle)
    override val state: StateFlow<PassphrasePromptState> = _state.asStateFlow()
    private var waiter: CompletableDeferred<CharArray?>? = null
    private var preArmed: CharArray? = null

    override suspend fun prompt(reason: UvReason): CharArray? {
        preArmed?.let { pre -> preArmed = null; return pre } // AC-2: reuse the in-hand enroll passphrase, no prompt
        waiter?.complete(null) // resolve any stale waiter fail-closed (exactly-one prompt in flight)
        val deferred = CompletableDeferred<CharArray?>()
        waiter = deferred
        _state.value = PassphrasePromptState.Prompting(reason)
        return try {
            deferred.await()
        } finally {
            _state.value = PassphrasePromptState.Idle
            if (waiter === deferred) waiter = null
        }
    }

    override fun submit(passphrase: CharArray) { waiter?.complete(passphrase) }
    override fun cancel() { waiter?.complete(null) }
    override fun preArm(passphrase: CharArray) { preArmed = passphrase }
}
