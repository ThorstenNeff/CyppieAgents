package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.operator.ui.OperatorAuthTags
import com.tneff.cyppieagents.net.hub.operator.vault.EnrollOutcome
import kotlin.test.Test

/**
 * CYP-542 / B1 (WS-UIUX2 a11y-coverage) — the **F1 regression lock** for the passphrase-enroll UI
 * ([SetPassphraseStep] in `RemoteOperatorAuthSteps.kt`). CYP-542's B1-final-tip audit found the whole enroll/operator
 * render had **no `liveRegion`** → strength changes + enroll refusals + mismatch were **silent to a screen reader**
 * (WCAG 4.1.3 Status Messages, AA). The Dev fix wired the live-regions; this test **discriminates** the fix from the
 * regression it replaces:
 *  - the live **strength** readout is **Polite** (announces the verdict without stealing focus while typing);
 *  - an enroll **refusal** / **mismatch** is **Assertive** (the SR interrupts to read it) and carries the **specific**
 *    cause (`blocklisted` ≠ `tooWeak` ≠ `mismatch`), never a generic "invalid".
 * A build that drops any live-region (the F1 state) fails here — the announce carries the already-distinct string, so
 * the honesty (distinct cause DATA) becomes a11y-audible (the SR actually HEARS which cause). Render-only; hermetic VM.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp542EnrollA11yLiveRegionTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun state(phase: EnrollPhase, outcome: EnrollOutcome? = null) =
        HubConnectUiState.SetPassphrase(hub, phase, outcome)
    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    // F1/② — a BLOCKLISTED refusal is an ASSERTIVE announce carrying its SPECIFIC cause (tooWeak NOT present).
    @Test
    fun blocklistedRefusal_isAssertive_specificCause() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(state(EnrollPhase.ERROR, EnrollOutcome.Blocklisted), vm()) } }
        onNodeWithTag(OperatorAuthTags.error("blocklisted"), useUnmergedTree = true)
            .assert(liveRegion(LiveRegionMode.Assertive))
        onNodeWithTag(OperatorAuthTags.error("tooWeak"), useUnmergedTree = true).assertDoesNotExist() // specific, not generic
    }

    // F1/② — a TOO_WEAK refusal is ASSERTIVE (liveRegion on the wrapping row) + its own distinct cause.
    @Test
    fun tooWeakRefusal_isAssertive_specificCause() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(state(EnrollPhase.ERROR, EnrollOutcome.TooWeak), vm()) } }
        onNodeWithTag(OperatorAuthTags.error("tooWeak"), useUnmergedTree = true)
            .onParent().assert(liveRegion(LiveRegionMode.Assertive)) // the Assertive region wraps the ▲+label row
        onNodeWithTag(OperatorAuthTags.error("blocklisted"), useUnmergedTree = true).assertDoesNotExist()
    }

    // F1 — the live strength readout is a POLITE region (announces the verdict, never focus-stealing on each keystroke).
    @Test
    fun strengthReadout_isPolite() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(state(EnrollPhase.ENTERING), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput("weak")
        onNodeWithTag(OperatorAuthTags.ENROLL_STRENGTH, useUnmergedTree = true)
            .assert(liveRegion(LiveRegionMode.Polite))
    }

    // F1 — a set≠confirm mismatch is an ASSERTIVE announce (not a silent red outline).
    @Test
    fun mismatch_isAssertive() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(state(EnrollPhase.ENTERING), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput("alpha")
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_CONFIRM).performTextInput("bravo")
        onNodeWithTag(OperatorAuthTags.error("mismatch"), useUnmergedTree = true)
            .assert(liveRegion(LiveRegionMode.Assertive))
    }
}
