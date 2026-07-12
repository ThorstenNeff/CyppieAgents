package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.trust.HubFingerprintDisplay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-482 S-B §3 — the OOB-confirm screen honesty teeth: the fingerprint is **real-derived** (PGP 11-token
 * even/odd word list from [HubFingerprintDisplay], never a placeholder — HB); the screen is
 * **mandatory-blocking** with exactly two exits, confirm (→pin) and reject (→fail-closed), no skip (HA); the
 * provisional disclosure is gated (§5/Q3).
 */
@OptIn(ExperimentalTestApi::class)
class OobFingerprintConfirmScreenTest {

    private val dhPubKey = ByteArray(32) { (it * 3 + 1).toByte() }

    @Test
    fun rendersRealFingerprint_11NumberedWords_hex_qr_oob() = runComposeUiTest {
        setContent { MaterialTheme { OobFingerprintConfirmScreen("mein-hub", dhPubKey, onConfirm = {}, onReject = {}) } }
        onNodeWithTag(RemoteConnectTags.TRUST_FIRST, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_FINGERPRINT, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_WORDLIST, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_HEX, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_QR, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_OOB_CONSOLE, useUnmergedTree = true).assertExists()
        // the words are the REAL derived PGP sequence (11 tokens), rendered numbered 1..11.
        val words = HubFingerprintDisplay.words(dhPubKey)
        assertTrue(words.size == 11, "PGP fingerprint is 11 tokens (88 bit)")
        onNodeWithText("1. ${words[0]}", useUnmergedTree = true).assertExists()
        onNodeWithText("11. ${words[10]}", useUnmergedTree = true).assertExists()
    }

    @Test
    fun mandatoryBlocking_confirmAndReject_fireCallbacks() = runComposeUiTest {
        var confirmed = false
        var rejected = false
        setContent {
            MaterialTheme {
                OobFingerprintConfirmScreen("h", dhPubKey, onConfirm = { confirmed = true }, onReject = { rejected = true })
            }
        }
        // the two — and only two — exits are present (HA: no skip/dismiss).
        onNodeWithTag(RemoteConnectTags.TRUST_CONFIRM, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_REJECT, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteConnectTags.TRUST_CONFIRM, useUnmergedTree = true).performClick()
        assertTrue(confirmed, "confirm → approve()/pin")
        onNodeWithTag(RemoteConnectTags.TRUST_REJECT, useUnmergedTree = true).performClick()
        assertTrue(rejected, "reject → fail-closed abort")
    }

    @Test
    fun provisionalDisclosure_isGated() = runComposeUiTest {
        setContent { MaterialTheme { OobFingerprintConfirmScreen("h", dhPubKey, {}, {}, provisional = true) } }
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun provisionalDisclosure_absentWhenLive() = runComposeUiTest {
        setContent { MaterialTheme { OobFingerprintConfirmScreen("h", dhPubKey, {}, {}, provisional = false) } }
        onNodeWithTag(RemoteConnectTags.TRUST_PROVISIONAL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun abortedView_rendersRejectedResult() = runComposeUiTest {
        setContent { MaterialTheme { TrustAbortedView() } }
        onNodeWithTag(RemoteConnectTags.TRUST_ABORTED, useUnmergedTree = true).assertExists()
    }
}
