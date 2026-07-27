package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-835 (QA F-I3) — the **revoke × protocol-skew banner PRECEDENCE**, rendered.
 *
 * `ConnectionBanner` checks `accessRevoked` FIRST and `return`s, then `protocolSkew` — so on a security
 * revoke the ✕ revoked banner wins and the ≠ skew banner is suppressed ("if both, the auth-revoke (security)
 * wins", `CommPanel.kt:321-323`). Both terminal flags are independently representable (`CommUiState.accessRevoked`
 * + `.protocolSkew` are two Booleans; runtime exclusivity rests only on `collectLive`'s `liveJob.cancel()` after
 * the first terminal event — a race could set both). The precedence is the standing defense, and it was
 * UNTESTED: no test drove both flags true, so a reorder of the two `if` blocks (skew before revoke) would show
 * the WRONG banner on a security event and stay green. This pins it.
 *
 * The single-flag cases are the non-vacuity controls: they prove each banner CAN render, so the both-true
 * `PROTOCOL_SKEW` absence is the precedence SUPPRESSING it — not skew simply never rendering.
 *
 * Renders `ConnectionBanner` directly (the both-true state is unreachable through `CommViewModel`, which cancels
 * after the first terminal event). Tags, not copy — the headless default locale is EN.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp835RevokeSkewPrecedenceRenderTest {

    @Test
    fun bothRevokedAndSkew_theRevokeBannerWins_skewSuppressed() = runComposeUiTest {
        setContent {
            MaterialTheme { ConnectionBanner(ConnectionStatus.DISCONNECTED, accessRevoked = true, protocolSkew = true) }
        }
        // ★ the precedence: revoke (security) wins; the skew banner must NOT also render (exactly-one, revoke-first).
        onNodeWithTag(CommTags.ACCESS_REVOKED, useUnmergedTree = true)
            .assertExists()
            // …and the winning revoked banner is announced ASSERTIVE (unsolicited + terminal), not the skew's.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        onNodeWithTag(CommTags.PROTOCOL_SKEW, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun skewOnly_showsTheSkewBanner_nonVacuityControl() = runComposeUiTest {
        setContent {
            MaterialTheme { ConnectionBanner(ConnectionStatus.DISCONNECTED, accessRevoked = false, protocolSkew = true) }
        }
        // Proves the skew banner CAN render → the both-true absence above is the precedence suppressing it.
        onNodeWithTag(CommTags.PROTOCOL_SKEW, useUnmergedTree = true).assertExists()
        onNodeWithTag(CommTags.ACCESS_REVOKED, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun revokedOnly_showsTheRevokeBanner_nonVacuityControl() = runComposeUiTest {
        setContent {
            MaterialTheme { ConnectionBanner(ConnectionStatus.DISCONNECTED, accessRevoked = true, protocolSkew = false) }
        }
        onNodeWithTag(CommTags.ACCESS_REVOKED, useUnmergedTree = true).assertExists()
        onNodeWithTag(CommTags.PROTOCOL_SKEW, useUnmergedTree = true).assertDoesNotExist()
    }
}
