package com.tneff.cyppieagents.workspace

import com.tneff.cyppieagents.auth.UserTier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-186 — the one operator-access derivation (hybrid C), mutation-teethed: OPERATOR → editable, MEMBER
 * (no token) → gated (fail-closed), a break-glass token → editable regardless of tier. A mutation that drops
 * the tier check, drops the token check, or hard-codes true/false reddens on one of these.
 */
class WorkspaceAccessTest {

    @Test
    fun operatorTier_isEditable() {
        assertTrue(isOperatorAccess(UserTier.OPERATOR, operatorToken = null))
        assertTrue(isOperatorAccess(UserTier.OPERATOR, operatorToken = "tok"))
    }

    @Test
    fun memberTier_noToken_isGated_failClosed() {
        // ⭐ the load-bearing MEMBER default: no operator role + no token ⇒ read-only.
        assertFalse(isOperatorAccess(UserTier.MEMBER, operatorToken = null))
    }

    @Test
    fun memberTier_withBreakGlassToken_isEditable() {
        // Hybrid C: a break-glass operator token overrides the tier (bootstrap / override path).
        assertTrue(isOperatorAccess(UserTier.MEMBER, operatorToken = "op-token"))
    }

    @Test
    fun showRoster_onlyForOperatorTier() {
        // Roster mount is tier-gated (NOT the token) — matches the backend's OPERATOR-only GET. A mutation
        // that shows it for MEMBER, or hides it for OPERATOR, reddens (§3.2 enumeration seam).
        assertTrue(showRoster(UserTier.OPERATOR))
        assertFalse(showRoster(UserTier.MEMBER))
    }
}
