package com.tneff.cyppieagents.net.hub.operator.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-460 — the H2 honesty rail as a unit tooth: **only a hub reject is terminal**; every local failure is
 * retryable and carries its own tag (never merged into `authRejected`). Pins the frozen tag contract.
 */
class OperatorAuthTaxonomyTest {

    private val locals = listOf(
        OperatorAuthError.WrongPin(attemptsLeft = 2),
        OperatorAuthError.LockedOut(retryAfter = "30 s"),
        OperatorAuthError.BiometricFailed,
        OperatorAuthError.KeystoreUnavailable,
        OperatorAuthError.NeedsEnroll,
        OperatorAuthError.Cancelled,
    )

    @Test
    fun onlyHubReject_isTerminal_localsAreRetryable() {
        assertTrue(OperatorAuthError.HubRejected.isTerminal, "a hub reject is terminal (H2)")
        locals.forEach { assertFalse(it.isTerminal, "$it is a LOCAL failure — retryable, never terminal (H2)") }
    }

    @Test
    fun tags_matchFrozenContract_lockedOutIsNotAnErrorCause() {
        assertEquals("remote.authStep.error.pinWrong", OperatorAuthError.WrongPin(1).tag())
        assertEquals("remote.authStep.lockedOut", OperatorAuthError.LockedOut("x").tag()) // own tag, NOT error.<cause>
        assertEquals("remote.authStep.error.biometricFailed", OperatorAuthError.BiometricFailed.tag())
        assertEquals("remote.authStep.error.keystoreUnavailable", OperatorAuthError.KeystoreUnavailable.tag())
        assertEquals("remote.authStep.error.needsEnroll", OperatorAuthError.NeedsEnroll.tag())
        assertEquals("remote.authStep.error.cancelled", OperatorAuthError.Cancelled.tag())
        assertEquals("remote.authStep.error.authRejected", OperatorAuthError.HubRejected.tag())
    }
}
