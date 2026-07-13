package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT

/**
 * CYP-525 §2 — the first-enroll **user-saved** confirmation seam. During RR3 auth, when the hub grant says
 * `firstEnroll` and the `EnrollResponse` codes arrive, [ClientOperatorAuth] calls this to surface the
 * `RecoveryCodesReveal` and **suspend** until the operator confirms they saved the codes (→ `true`, send `SavedAck`)
 * or aborts (→ `false`, fail-closed, NO `SavedAck` ⇒ the hub discards the provisional ⇒ clean re-TOFU). The live
 * impl is the ViewModel (sets `RevealCodes`, awaits `acknowledgeCodes`); the default is a fail-closed no-op (INERT —
 * first-enroll unsupported ⇒ abort). The codes are H3-validated **before** this is called (never shown / acked on an
 * invalid set).
 */
fun interface EnrollConfirmer {
    suspend fun confirmSavedCodes(codes: List<String>): Boolean
}

/**
 * CYP-525 H3 (Reviewer) — a first-enroll code set is valid iff it is **exactly [BACKUP_CODE_COUNT]** non-blank codes
 * (the count single-sourced in `:core`, hub `mint(BACKUP_CODE_COUNT)` ↔ this validation). Fail-closed: any other
 * shape (empty, short, over-count, blank) ⇒ `false` ⇒ no reveal / no `SavedAck` ⇒ the hub never finalizes against
 * codes the user never had ⇒ clean re-TOFU with fresh codes.
 */
internal fun isValidCodeSet(codes: List<String>): Boolean =
    codes.size == BACKUP_CODE_COUNT && codes.all { it.isNotBlank() }
