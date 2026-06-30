package com.tneff.cyppieagents

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-151 — fail-closed operator-token carry on Android.
 *
 * [defaultShellConfig] reads the launch-intent value stashed in [AndroidLaunchEnv]. The security
 * property: only a present, **non-blank** value promotes to an operator config; absent / null / blank
 * / whitespace-only all stay `operatorToken == null` (fail-closed), mirroring the iOS CYP-114 shape.
 */
class AndroidOperatorTokenTest {

    @AfterTest
    fun reset() {
        // The holder is a process-global singleton — clear it so tests don't leak into each other.
        AndroidLaunchEnv.operatorToken = null
    }

    @Test
    fun noToken_failsClosed() {
        AndroidLaunchEnv.operatorToken = null
        assertNull(defaultShellConfig().operatorToken, "absent token must stay null (fail-closed)")
    }

    @Test
    fun blankToken_failsClosed() {
        AndroidLaunchEnv.operatorToken = ""
        assertNull(defaultShellConfig().operatorToken, "empty token must read as null (fail-closed)")
    }

    @Test
    fun whitespaceToken_failsClosed() {
        AndroidLaunchEnv.operatorToken = "   \t "
        assertNull(defaultShellConfig().operatorToken, "whitespace-only token must read as null (fail-closed)")
    }

    @Test
    fun presentToken_promotesToOperator() {
        AndroidLaunchEnv.operatorToken = "op-tok-123"
        assertEquals("op-tok-123", defaultShellConfig().operatorToken, "a non-blank token must promote to operator")
    }
}
