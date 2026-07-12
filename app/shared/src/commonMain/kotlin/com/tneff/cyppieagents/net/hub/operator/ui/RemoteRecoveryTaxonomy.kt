package com.tneff.cyppieagents.net.hub.operator.ui

/**
 * CYP-479 — the recovery-input error taxonomy (CYP-480 §3.2), mirroring [OperatorAuthTaxonomy]'s
 * local-vs-terminal split but with the tone **inverted per the spec**: the retryable [InvalidCode] reads as an
 * error (try another code), while the terminal [Exhausted] is **neutral** — an honest "recover OOB at the hub"
 * redirect, **not** an alarm (there is no phantom path once codes run out; fail-closed).
 */
sealed interface RecoveryError {
    /** Wrong or already-used code — retryable (error tone; the field stays usable). */
    data object InvalidCode : RecoveryError
    /** No codes left — terminal, fail-closed (neutral OOB-at-hub redirect, no alarm). */
    data object Exhausted : RecoveryError
}

/** [RecoveryError.Exhausted] is terminal (no more codes); [RecoveryError.InvalidCode] is retryable. */
val RecoveryError.isTerminal: Boolean
    get() = this is RecoveryError.Exhausted
