package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.net.hub.operator.ui.OperatorAuthTags
import kotlin.test.Test

/**
 * CYP-542 / B1 render-QA (S-Tester, verdict-RENDER lane) — the **live strength-meter GLYPH-GRAMMAR** teeth for the
 * enroll step ([SetPassphraseStep] → `StrengthMeter` in `RemoteOperatorAuthSteps.kt`), asserting the render-oracle
 * `b03d4b7c` 1:1 on the REAL passphrase→`PassphraseStrength.verdict`→render path (typed input, not a planted verdict).
 *
 * ## Why this file exists (gap, not a dup)
 * The MODEL mapper (`enrollStrengthUi` tuple: tone/messageKey/blocks/meterFraction/fillDamped) is covered by
 * `EnrollStrengthPresentationTest` (L1, covers R2/R3/R4/R7-model). The a11y announce/liveRegion is covered by
 * `Cyp542EnrollA11yLiveRegionTest`. **Neither asserts the RENDERED glyph grammar on the LIVE meter** — that a strong
 * phrase shows the neutral-affirmative `●` (never a WARN `▲`), a too-weak phrase shows the WARN `▲` + its cause tag,
 * and a **blocklisted phrase carries NO glyph** (the tone-distinction IS the glyph grammar: `▲`=WARN/advisory,
 * `●`=neutral-affirmative, BLOCKLISTED=none — its error-tone + self-describing copy are the two non-colour carriers).
 * These are the L2 render-honesty teeth (R1/R8 + R5-absence). Skiko can't read pixel COLOUR ([[cmp-render-test-infra]])
 * → the headless discriminators are **glyph + copy (EN, default locale) + tag**, which fully separate all 3 states.
 *
 * Passphrases drive the REAL verdict: "weak" → TOO_WEAK (18 structural bits < 64); a 20-char mixed string → OK
 * (≥64 bits, not blocklisted); "correct horse battery staple" → BLOCKLISTED (on the common-phrase blocklist AND
 * structurally long — the exact case the optics must never render "strong").
 */
@OptIn(ExperimentalTestApi::class)
class Cyp542StrengthMeterRenderTest {

    private val hub = HubDescriptor("hub-r", "host-r", online = true, defaultPort = 8787, lastSeen = 1L)
    private fun vm() = HubConnectViewModel(StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed())
    private fun entering() = HubConnectUiState.SetPassphrase(hub, EnrollPhase.ENTERING)

    private val STRONG = "9xKqmZ7vwLpR3nBhJ2tD"   // ≥64 structural bits, not blocklisted → OK
    private val WEAK = "weak"                       // 18 bits < 64 → TOO_WEAK
    private val BLOCKED = "correct horse battery staple" // on the blocklist (structurally long) → BLOCKLISTED

    // ★ R1(OK) — a STRONG phrase renders the neutral-affirmative ● (NOT the WARN ▲), no error cause tag.
    @Test
    fun strong_showsNeutralAffirmativeGlyph_noWarnGlyph_noErrorTag() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(entering(), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput(STRONG)
        onNodeWithTag(OperatorAuthTags.ENROLL_STRENGTH, useUnmergedTree = true).assertExists()
        onNodeWithText("●", substring = true, useUnmergedTree = true).assertExists()          // neutral-affirmative
        onNodeWithText("▲", substring = true, useUnmergedTree = true).assertDoesNotExist()    // never WARN on a strong phrase
        onNodeWithTag(OperatorAuthTags.error("tooWeak"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(OperatorAuthTags.error("blocklisted"), useUnmergedTree = true).assertDoesNotExist()
    }

    // ★ R1(TOO_WEAK)/R2 — a WEAK phrase renders the WARN ▲ + the tooWeak cause tag + the tooWeak EN copy; NOT the ● and
    //   NOT the blocklisted presentation (distinct cause).
    @Test
    fun weak_showsWarnGlyph_tooWeakTagAndCopy_notBlocklisted() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(entering(), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput(WEAK)
        onNodeWithText("▲", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithTag(OperatorAuthTags.error("tooWeak"), useUnmergedTree = true).assertExists()
        onNodeWithText("too weak", substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        onNodeWithText("●", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(OperatorAuthTags.error("blocklisted"), useUnmergedTree = true).assertDoesNotExist()
    }

    // ★ R1(BLOCKLISTED)/R3 — a BLOCKLISTED phrase carries NO glyph (both ▲ AND ● absent) + the blocklisted cause tag +
    //   the "too common"/"known-weak" EN copy. The headless tone-distinction from tooWeak IS the glyph grammar
    //   (tooWeak has ▲, blocklisted has none). A blocklisted that borrows the ▲ WARN glyph reds this.
    @Test
    fun blocklisted_hasNoGlyph_blocklistedTagAndCopy_distinctFromWeak() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(entering(), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput(BLOCKED)
        onNodeWithTag(OperatorAuthTags.error("blocklisted"), useUnmergedTree = true).assertExists()
        onNodeWithText("too common", substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        onNodeWithText("▲", substring = true, useUnmergedTree = true).assertDoesNotExist() // borrows NO WARN glyph
        onNodeWithText("●", substring = true, useUnmergedTree = true).assertDoesNotExist() // and NOT the affirmative glyph
        onNodeWithTag(OperatorAuthTags.error("tooWeak"), useUnmergedTree = true).assertDoesNotExist()
    }

    // R8 — the FORBIDDEN warning glyph `⚠` (terminal hub-reject tone) NEVER appears in the enroll strength surface
    //   (the enroll advisory uses `▲`, not `⚠` — tone-ladder discipline, oracle §checklist #4/#6).
    @Test
    fun enrollMeter_neverUsesForbiddenWarningGlyph() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(entering(), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput(WEAK)
        onNodeWithText("▲", substring = true, useUnmergedTree = true).assertExists() // positive anchor: the advisory DID render
        onNodeWithText("⚠", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    // R5 — a below-floor entry keeps the operator ON the enroll step (the verdict shows inline); it is NOT a terminal
    //   hub-reject / retry-reloop. Assert NO `authRejected` cause node while the enroll field + live meter DO render
    //   (positive anchor so the absence is meaningful — [[qa-reverify-discipline]] axis 21).
    @Test
    fun weakEntry_staysOnEnrollStep_noTerminalAuthRejected() = runComposeUiTest {
        setContent { MaterialTheme { SetPassphraseStep(entering(), vm()) } }
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD).performTextInput(WEAK)
        onNodeWithTag(OperatorAuthTags.ENROLL_PASSPHRASE_FIELD, useUnmergedTree = true).assertExists() // still on enroll
        onNodeWithTag(OperatorAuthTags.ENROLL_STRENGTH, useUnmergedTree = true).assertExists()         // meter shown inline
        onNodeWithTag(OperatorAuthTags.error("authRejected"), useUnmergedTree = true).assertDoesNotExist()
    }
}
