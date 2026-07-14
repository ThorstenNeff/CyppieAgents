package com.tneff.cyppieagents.net.hub.operator.vault

import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvFailReason
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CYP-542 / B1 (§4.4 — the load-bearing seam) — the real [UserVerification]: prompt the operator passphrase → open the
 * [OperatorSecretVault] (Argon2id → KEK → AES-GCM decrypt). On success the decrypted key is placed in the shared
 * [DecryptedKeyHold] for the **bounded reuse window** (the vault-backed keystore signs from there), and `Verified` is
 * returned. This is the piece the F⑥-1 [com.tneff.cyppieagents.net.hub.operator.CachingUserVerification] wraps — so
 * **1 real passphrase prompt authorizes N tunnels** (the cache replays `Verified`, the hold serves the key), for the
 * SAME window, then both expire (the key zeroizes, the next tunnel re-prompts). No correct passphrase ⇒ no key ⇒ no
 * signature (the no-shortcut invariant, end-to-end).
 *
 * Honesty (CYP-443 rails): wrong passphrase ⇒ `Denied(WRONG_PIN)`, cancel ⇒ `Denied(CANCELLED)`, lockout ⇒
 * `Denied(LOCKED_OUT)` — all **local + retryable**, NEVER a hub reject. A corrupt/missing vault ⇒ `Unavailable`
 * (fail-closed → the flow routes to OOB-recovery / enroll, ③). The passphrase `CharArray` is zeroized after use (H-1).
 */
class PassphraseUserVerification(
    private val vault: OperatorSecretVault,
    private val prompt: PassphrasePrompt,
    private val keyHold: DecryptedKeyHold,
    private val nowMs: () -> Long,
    private val reuseWindowMs: Long,
) : UserVerification {

    override suspend fun verify(reason: UvReason): UvOutcome {
        val passphrase = prompt.prompt(reason) ?: return UvOutcome.Denied(UvFailReason.CANCELLED)
        try {
            return when (val opened = vault.open(passphrase)) {
                is VaultOpen.Unlocked -> {
                    keyHold.put(opened.privKeyPkcs8, expiresAtMs = nowMs() + reuseWindowMs) // held for the reuse window
                    UvOutcome.Verified
                }
                VaultOpen.WrongPassphrase -> UvOutcome.Denied(UvFailReason.WRONG_PIN)
                is VaultOpen.LockedOut -> UvOutcome.Denied(UvFailReason.LOCKED_OUT)
                VaultOpen.Corrupt -> UvOutcome.Unavailable // ③ fail-closed → OOB-recovery (never a bypass, never re-enroll)
                VaultOpen.Missing -> UvOutcome.Unavailable // raced deletion — the enroll path handles the normal case
            }
        } finally {
            passphrase.fill('\u0000') // H-1: zeroize (NUL) — never let the passphrase linger
        }
    }
}

/**
 * The operator passphrase/PIN entry seam (the CYP-460 `OperatorAuthDialog` wires the real Compose prompt at the
 * composition root; tests inject a stub). Returns the entered secret as a `CharArray` (H-1, never a `String`), or
 * `null` if the operator cancelled. **Never silent** — a returned value reflects real operator presence + input.
 */
fun interface PassphrasePrompt {
    suspend fun prompt(reason: UvReason): CharArray?
}

/**
 * CYP-542 / B1 (§4.4) — the **bounded, single-slot** hold for the decrypted device key between the passphrase
 * ceremony and the N signings it authorizes. Reconciles 1-UV-for-N with encrypted-at-rest: the key lives in memory
 * ONLY for the reuse window, then [get] returns `null` and the bytes are **zeroized** (never GC-reliance, H-1). [get]
 * returns a defensive copy the caller zeroizes after signing, so a concurrent window-expiry can't clear the bytes
 * mid-sign.
 */
class DecryptedKeyHold(
    private val nowMs: () -> Long,
    /**
     * Assist BLOCK-1 (part 2 — the idle path): when present, [put] schedules a **proactive** zeroize at window-expiry
     * so a hold that is never [get]-touched and never torn down (operator idles) still clears at ≤120s — otherwise the
     * "≤120s bounded exposure" (§4.4) is false for idle (the key would linger until GC). `null` ⇒ lazy-only (tests that
     * don't exercise the timer). The scope is the per-connect scope, so teardown cancels the timer too.
     */
    private val scope: CoroutineScope? = null,
) {
    private var key: ByteArray? = null
    private var expiresAtMs: Long = 0L
    private var expiryJob: Job? = null

    fun put(k: ByteArray, expiresAtMs: Long) {
        clear()
        this.key = k
        this.expiresAtMs = expiresAtMs
        // Proactive expiry (idle path): zeroize when the window elapses even with no get()/teardown. A fresh put()
        // cancelled the prior job in clear(); teardown's clear() cancels this one. Idempotent with the lazy get()-path.
        val delayMs = expiresAtMs - nowMs()
        expiryJob = scope?.launch {
            if (delayMs > 0) delay(delayMs)
            zeroizeNow() // window elapsed untouched ⇒ never let the crown-jewel key linger past the bound (H-1)
        }
    }

    /** A defensive copy of the held key, or `null` if none / the window expired (which also zeroizes the original). */
    fun get(): ByteArray? {
        if (nowMs() >= expiresAtMs) { clear(); return null }
        return key?.copyOf()
    }

    /**
     * Zeroize + drop the held key. Idempotent. Reached on: a fresh [put]; window-expiry — both **lazy** (via [get])
     * AND **proactive** (the [scope] timer, so an idle hold clears at the bound); and **session teardown** (the VM
     * invokes it via `RemoteConnectComponents.keyHold` on every teardown — switch/leave/cancel/onCleared, Assist
     * BLOCK-1) so the crown-jewel key never lingers GC-reachable past the connection (H-1, §4.4 bounded exposure).
     */
    fun clear() {
        expiryJob?.cancel()
        expiryJob = null
        zeroizeNow()
    }

    private fun zeroizeNow() {
        key?.fill(0)
        key = null
        expiresAtMs = 0L
    }
}
