package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-460 — the operator-auth dialog **render honesty-teeth**. Pins the H2 split (terminal hub-reject ≠ retryable
 * local error), the biometric→PIN fallback (H1), and the Q5 session-only disclosure. (Colour discipline — neutral /
 * error-tone / errorContainer, never tertiary-green — lives in the composable via the theme roles; a tag test can't
 * read colour, so these pin the structural rails UIUX §-QA then checks visually.)
 */
@OptIn(ExperimentalTestApi::class)
class OperatorAuthDialogRenderTest {

    @Test
    fun pinStep_showsPathHintAndField() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Pin("App-PIN"), error = null, pin = "", {}, {})
            }
        }
        onNodeWithTag(OperatorAuthTags.PATH_HINT, useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.PIN_FIELD, useUnmergedTree = true).assertExists()
    }

    @Test
    fun wrongPin_isLocalRetryable_withAttempts_fieldEnabled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Pin("App-PIN"), OperatorAuthError.WrongPin(2), "", {}, {})
            }
        }
        // Local pinWrong tag + attempts tag; the field stays ENABLED (retryable) — never the terminal errorContainer.
        onNodeWithTag(OperatorAuthTags.error("pinWrong"), useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.ATTEMPTS, useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.error("authRejected"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(OperatorAuthTags.PIN_FIELD, useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun hubRejected_isTerminal_noRetry_fieldDisabled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Pin("App-PIN"), OperatorAuthError.HubRejected, "", {}, {})
            }
        }
        // Terminal hub reject: its own tag; NO local attempts affordance; the field is DISABLED (no silent retry, H2).
        onNodeWithTag(OperatorAuthTags.error("authRejected"), useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.ATTEMPTS, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(OperatorAuthTags.PIN_FIELD, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun biometricFailed_showsPinFallback() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Biometric("Touch ID"), OperatorAuthError.BiometricFailed, "", {}, {})
            }
        }
        onNodeWithTag(OperatorAuthTags.BIOMETRIC_PROMPT, useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.PIN_FIELD, useUnmergedTree = true).assertExists() // H1 fallback visible
    }

    @Test
    fun enroll_showsSessionOnlyDisclosure() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Enroll(sessionOnly = true), error = null, pin = "", {}, {})
            }
        }
        onNodeWithTag(OperatorAuthTags.ENROLL_PIN_SET, useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.ENROLL, useUnmergedTree = true).assertExists() // Q5 "this session only"
    }

    @Test
    fun ge6_sessionOnly_hasSeparateWarnGlyphNode() = runComposeUiTest {
        // CYP-525 GE6: the session-only downgrade is WARN-amber with a SEPARATE `▲` node (WCAG 1.4.1) — colour is
        // never the sole carrier. (The amber tone itself is UIUX §-QA-visual; the tag test pins the glyph node.)
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Enroll(sessionOnly = true), error = null, pin = "", {}, {})
            }
        }
        onNodeWithTag(OperatorAuthTags.ENROLL, useUnmergedTree = true).assertExists()
        onNodeWithText("▲", useUnmergedTree = true).assertExists()
    }

    @Test
    fun ge3_sessionOnly_absentOnHardwarePath() = runComposeUiTest {
        // GE3: the session-only disclosure renders ONLY on the Raw software path (sessionOnly==true). A hardware
        // passkey (Fido2, sessionOnly==false) never shows it — no DEVICE_SECURE over- or under-statement.
        setContent {
            MaterialTheme {
                OperatorAuthDialog(OperatorAuthStep.Enroll(sessionOnly = false), error = null, pin = "", {}, {})
            }
        }
        onNodeWithTag(OperatorAuthTags.ENROLL, useUnmergedTree = true).assertDoesNotExist()
    }
}
