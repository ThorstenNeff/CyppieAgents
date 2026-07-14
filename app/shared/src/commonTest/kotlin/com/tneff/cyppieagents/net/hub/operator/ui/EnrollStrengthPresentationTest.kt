package com.tneff.cyppieagents.net.hub.operator.ui

import com.tneff.cyppieagents.net.hub.operator.vault.StrengthVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — the D1/D3/D5 enroll-strength presentation: `tooWeak` = WARN-amber (advisory tone, but still BLOCKS —
 * the ② floor is fail-closed), `blocklisted` = ERROR tone (dampened meter), distinct causes/keys (H1). Both non-OK
 * verdicts block enrollment (F-#4). Tone ≠ the generic error path.
 */
class EnrollStrengthPresentationTest {

    @Test
    fun ok_isNeutral_doesNotBlock() {
        val ui = enrollStrengthUi(StrengthVerdict.OK)
        assertEquals(EnrollTone.NEUTRAL, ui.tone)
        assertTrue(!ui.blocks)
    }

    @Test
    fun tooWeak_isWarnAmber_butStillBlocks() {
        val ui = enrollStrengthUi(StrengthVerdict.TOO_WEAK)
        assertEquals(EnrollTone.WARN, ui.tone, "D1: tooWeak renders WARN-amber (advisory), NOT error-red")
        assertTrue(ui.blocks, "F-#4: a below-floor passphrase still blocks enrollment (fail-closed)")
        assertEquals("enroll_error_too_weak", ui.messageKey)
    }

    @Test
    fun blocklisted_isError_dampenedMeter_blocks() {
        val ui = enrollStrengthUi(StrengthVerdict.BLOCKLISTED)
        assertEquals(EnrollTone.ERROR, ui.tone, "D1: blocklisted = error tone (definitively bad)")
        assertTrue(ui.blocks)
        assertTrue(ui.meterFraction <= 0.15f, "D5: the blocklist meter fill is dampened (never 'strong')")
        assertEquals("enroll_error_blocklisted", ui.messageKey)
    }

    @Test
    fun tooWeak_and_blocklisted_areDistinctCauses() {
        assertNotEquals(enrollStrengthUi(StrengthVerdict.TOO_WEAK).tone, enrollStrengthUi(StrengthVerdict.BLOCKLISTED).tone)
        assertNotEquals(enrollStrengthUi(StrengthVerdict.TOO_WEAK).messageKey, enrollStrengthUi(StrengthVerdict.BLOCKLISTED).messageKey)
    }
}
