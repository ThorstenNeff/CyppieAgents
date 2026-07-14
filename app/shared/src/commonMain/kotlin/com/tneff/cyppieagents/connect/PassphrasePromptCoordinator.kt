package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.operator.vault.PassphrasePrompt
import kotlin.concurrent.Volatile // multiplatform @Volatile (kotlin.jvm.Volatile is JVM-only)
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
    /**
     * P1 (zeroize): drop **and zeroize** a pre-arm that was never consumed (the flow aborts before the first auth
     * prompt). The VM calls this on any teardown/reset (switch/leave/cancel) so a crown-jewel passphrase never
     * lingers in memory to GC. Idempotent, safe when nothing is armed. NOT called on the normal consume path —
     * [prompt] hands the SAME array to the UV, which zeroizes it after use (P2: the flow never double-zeroizes it).
     */
    fun clearPreArm()
}

class LivePassphrasePromptCoordinator : PassphrasePromptCoordinator {
    private val _state = MutableStateFlow<PassphrasePromptState>(PassphrasePromptState.Idle)
    override val state: StateFlow<PassphrasePromptState> = _state.asStateFlow()
    // P3 (concurrency): @Volatile — the prompt coroutine sets these while submit/cancel/preArm/clearPreArm may run on
    // another thread; @Volatile guarantees the cross-thread visibility (mirrors LiveEnrollConfirmCoordinator's intent).
    @Volatile private var waiter: CompletableDeferred<CharArray?>? = null
    @Volatile private var preArmed: CharArray? = null

    override suspend fun prompt(reason: UvReason): CharArray? {
        preArmed?.let { pre -> preArmed = null; return pre } // AC-2: reuse the in-hand enroll passphrase (P2: NOT zeroized here — the UV owns+zeroizes it)
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
    override fun cancel() { clearPreArm(); waiter?.complete(null) } // cancel aborts a pending pre-arm too (P1)
    override fun preArm(passphrase: CharArray) { clearPreArm(); preArmed = passphrase } // never leak a prior un-consumed arm
    override fun clearPreArm() {
        preArmed?.fill('\u0000') // P1: zeroize the un-consumed crown-jewel (NUL), never GC-reliance (H-1)
        preArmed = null
    }
}
