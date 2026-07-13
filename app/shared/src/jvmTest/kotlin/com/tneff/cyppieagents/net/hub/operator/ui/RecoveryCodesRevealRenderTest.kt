package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-479 §3.1 — the one-time backup-codes reveal renders the codes **readably** (HD, not masked), a copy
 * affordance, and an explicit leave-gate ("I've saved them") — the only way forward. No "view codes again".
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryCodesRevealRenderTest {

    @Test
    fun rendersReadableCodes_copy_andAckGate() = runComposeUiTest {
        setContent { MaterialTheme { RecoveryCodesReveal(codes = listOf("AAA-111", "BBB-222"), onAcknowledged = {}) } }
        onNodeWithTag(RemoteRecoveryTags.CODES, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODES_LIST, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODES_COPY, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODES_ACK, useUnmergedTree = true).assertExists()
        onNodeWithText("AAA-111", useUnmergedTree = true).assertExists() // codes are readable, not masked
        onNodeWithText("BBB-222", useUnmergedTree = true).assertExists()
    }

    @Test
    fun ge7_noCentralConsequence_presentOnReveal_besideTheAck() = runComposeUiTest {
        // CYP-525 GE7: the "no way back / no central login" consequence must render ON the reveal, before the ack —
        // so the user knows the stakes before quittancing (not only on the loss surface).
        setContent { MaterialTheme { RecoveryCodesReveal(codes = listOf("X-1"), onAcknowledged = {}) } }
        onNodeWithTag(RemoteRecoveryTags.CODES_NO_CENTRAL, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODES_ACK, useUnmergedTree = true).assertExists()
    }

    @Test
    fun ackButton_isTheLeaveGate() = runComposeUiTest {
        var acked = false
        setContent { MaterialTheme { RecoveryCodesReveal(codes = listOf("X-1"), onAcknowledged = { acked = true }) } }
        onNodeWithTag(RemoteRecoveryTags.CODES_ACK, useUnmergedTree = true).performClick()
        assertTrue(acked, "leaving is gated behind the explicit acknowledgement (HD)")
    }
}
